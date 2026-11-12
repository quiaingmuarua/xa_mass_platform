"""Outcome classification of the shipped one-round acceptance command, against a fake Runtime."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from distribution.acceptance import finite_round as kit

KEY = "xak_" + "k" * 43


class FakeRuntime:
    """A minimal finite-Task Runtime. Faults are keyed by 'METHOD /path' prefixes."""

    def __init__(self, *, terminal_after=1, faults=None, wrong_md5=False, item_status="succeeded"):
        self.calls, self.items, self.reads = [], {}, 0
        self.terminal_after, self.faults = terminal_after, dict(faults or {})
        self.wrong_md5, self.item_status = wrong_md5, item_status
        self.descriptor = None

    def __call__(self, method, path, body, headers):
        self.calls.append((method, path))
        for prefix, fault in self.faults.items():
            if f"{method} {path}".startswith(prefix):
                if fault == "lost":
                    raise kit.TransportError()
                return fault, json.dumps({"code": 18008, "message": "private", "requestId": "req-1"}).encode()
        payload = json.loads(body) if body else None
        if (method, path) == ("POST", "/api/v1/tasks"):
            self.descriptor = {"projectId": payload["projectId"], "workerGroupId": payload["workerGroupId"],
                               "idleDisposition": "CLOSE_WHEN_IDLE", "metadata": {"server.inputOwner": "raw"},
                               "refill": payload["refill"]}
            return 200, json.dumps({"taskId": "task-1"}).encode()
        if path == "/api/v1/tasks/task-1/items":
            self.items = {row["messageId"]: row for row in payload}
            return 200, json.dumps({key: {"status": "applied"} for key in self.items}).encode()
        if path == "/api/v1/projects/default/tasks/task-1":
            band = "terminal" if self.reads >= self.terminal_after else "running_visible"
            return 200, json.dumps({"task": self.descriptor, "scoreBand": band}).encode()
        if path.endswith("/approve"):
            return 200, b'{"status":"applied"}'
        if path.endswith("/results:load"):
            self.reads += 1
            done = self.reads >= self.terminal_after
            return 200, json.dumps({key: self._result(key) if done else {"status": "not_observed"}
                                    for key in self.items}).encode()
        if path.endswith("/items:states"):
            return 200, json.dumps({key: {"band": "terminal"} for key in self.items}).encode()
        if path.endswith("/results:preview"):
            return 200, json.dumps({"truncated": False, "results": [
                {"messageId": key, "item": row, "result": self._result(key)} for key, row in self.items.items()]}).encode()
        if path.endswith("/results:export"):
            return 200, "".join(json.dumps({"messageId": key, "opaqueResultPayload": self._result(key)["opaqueResultPayload"]})
                                + "\n" for key in self.items).encode()
        raise AssertionError(f"unexpected {method} {path}")

    def _result(self, key):
        seed = self.items[key]["payload"]["value"]
        digest = "0" * 32 if self.wrong_md5 else hashlib.md5(seed.encode("utf-8")).hexdigest()
        return {"status": self.item_status, "opaqueResultPayload": json.dumps(
            {"input": seed, "valid": True, "md5": digest})}


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def run(runtime, **overrides):
    clock = Clock()
    options = {"project": "default", "group": "g1", "items": 4, "timeout_seconds": 10,
               "clock": clock, "sleep": clock.sleep, **overrides}
    return kit.run_round(runtime, **options)


class FiniteRoundTest(unittest.TestCase):
    def test_passed_round_checks_every_result_and_reports_no_payload(self):
        runtime = FakeRuntime(terminal_after=3)
        record = run(runtime)
        self.assertEqual(record["outcome"], "passed", record)
        self.assertEqual((record["phase"], record["taskId"], record["items"]), ("done", "task-1", 4))
        self.assertEqual(len(record["exportSha256"]), 64)
        self.assertIn("approveToTerminal", record["durationsMillis"])
        self.assertEqual(sum(call == ("POST", "/api/v1/tasks") for call in runtime.calls), 1)
        self.assertLessEqual(set(record), {"outcome", "phase", "projectId", "group", "items", "requestId", "taskId",
                                           "startedAt", "exportSha256", "durationsMillis"})
        text = json.dumps(record, ensure_ascii=False)
        for seed in ("hello", "号码检查", "md5", "opaqueResultPayload"):
            self.assertNotIn(seed, text)

    def test_seeds_cover_the_utf8_vectors_and_stay_distinct(self):
        values = kit.seeds(7)
        self.assertEqual(values[:3], list(kit.VECTORS))
        self.assertEqual(len(set(values)), 7)

    def test_lost_create_response_is_unconfirmed_and_never_resubmitted(self):
        runtime = FakeRuntime(faults={"POST /api/v1/tasks": "lost"})
        record = run(runtime)
        self.assertEqual((record["outcome"], record["phase"], record["taskId"]), ("unconfirmed", "create", None))
        self.assertTrue(record["requestId"].startswith("round-"))
        self.assertEqual(runtime.calls, [("POST", "/api/v1/tasks")])

    def test_rejected_write_is_failed_with_only_code_and_server_request_id(self):
        record = run(FakeRuntime(faults={"POST /api/v1/tasks": 401}))
        self.assertEqual((record["outcome"], record["httpStatus"], record["errorCode"], record["serverRequestId"]),
                         ("failed", 401, 18008, "req-1"))
        self.assertNotIn("private", json.dumps(record))

    def test_server_error_on_approve_is_unconfirmed_with_task_identity(self):
        record = run(FakeRuntime(faults={"POST /api/v1/tasks/task-1/approve": 503}))
        self.assertEqual((record["outcome"], record["phase"], record["taskId"], record["httpStatus"]),
                         ("unconfirmed", "approve", "task-1", 503))

    def test_wait_timeout_is_unconfirmed(self):
        record = run(FakeRuntime(terminal_after=10 ** 6), timeout_seconds=5)
        self.assertEqual((record["outcome"], record["phase"], record["taskId"]), ("unconfirmed", "wait", "task-1"))

    def test_transient_read_failures_during_wait_are_retried(self):
        runtime = FakeRuntime(terminal_after=2)
        original = runtime.__call__
        failures = iter([kit.TransportError(), None])

        def flaky(method, path, body, headers):
            if path.endswith("/results:load"):
                failure = next(failures, None)
                if failure:
                    raise failure
            return original(method, path, body, headers)
        self.assertEqual(run(flaky)["outcome"], "passed")

    def test_failed_item_and_wrong_digest_are_failed(self):
        record = run(FakeRuntime(item_status="failed"))
        self.assertEqual((record["outcome"], record["phase"], record["reason"]), ("failed", "wait", "An Item failed"))
        record = run(FakeRuntime(wrong_md5=True))
        self.assertEqual((record["outcome"], record["phase"]), ("failed", "verify"))

    def test_unexpected_shape_is_failed_without_a_traceback(self):
        runtime = FakeRuntime()
        original = runtime.__call__

        def odd(method, path, body, headers):
            if path.endswith("/results:preview"):
                return 200, b'{"truncated": false, "results": [{"result": {}}]}'
            return original(method, path, body, headers)
        record = run(odd, items=1)
        self.assertEqual(record["outcome"], "failed")


class OracleTest(unittest.TestCase):
    """Moved unchanged from the staging proof: the kit is now the one owner of these checks."""

    def setUp(self):
        self.expected = {"item-a": "a", "item-unicode": "号码检查 ☕"}
        self.results = {identity: {"status": "succeeded", "opaqueResultPayload": json.dumps(kit.expected_result(seed))}
                        for identity, seed in self.expected.items()}

    def test_independent_utf8_digest_and_exact_result_identity_set(self):
        kit.assert_results(self.results, self.expected)
        for wrong in ({"item-a": self.results["item-a"]}, {**self.results, "extra": self.results["item-a"]},
                      {**self.results, "item-a": {"status": "failed"}},
                      {**self.results, "item-a": {"status": "not_observed"}}):
            with self.assertRaises(kit._Outcome):
                kit.assert_results(wrong, self.expected)
        for field, value in (("input", "another seed"), ("md5", "0" * 32), ("valid", False), ("valid", 1)):
            content = {**kit.expected_result("a"), field: value}
            wrong = {**self.results, "item-a": {"status": "succeeded", "opaqueResultPayload": json.dumps(content)}}
            with self.assertRaises(kit._Outcome):
                kit.assert_results(wrong, self.expected)
        self.assertEqual(kit.expected_result("号码检查 ☕")["md5"],
                         hashlib.md5("号码检查 ☕".encode("utf-8")).hexdigest())

    def test_preview_must_preserve_stored_item_input_event_and_actual_pool_query(self):
        def preview():
            return {"truncated": False, "results": [
                {"messageId": identity, "item": {"messageId": identity, "eventCode": kit.EVENT,
                 "payload": {"value": seed}, "workerSelector": kit.SELECTOR}, "result": self.results[identity]}
                for identity, seed in self.expected.items()]}
        kit.assert_preview(preview(), self.expected)
        for key, value in (("workerSelector", {"executorName": "workerId", "input": "a-worker"}),
                           ("eventCode", "another.event"), ("payload", {"value": "wrong"})):
            wrong = preview()
            wrong["results"][0]["item"][key] = value
            with self.assertRaises(kit._Outcome):
                kit.assert_preview(wrong, self.expected)
        wrong = preview()
        wrong["results"][1] = wrong["results"][0]
        for bad in (wrong, {**preview(), "truncated": True}, {"results": "x"}, []):
            with self.assertRaises(kit._Outcome):
                kit.assert_preview(bad, self.expected)


class CommandTest(unittest.TestCase):
    def main(self, *arguments, key=KEY, runtime=None):
        with tempfile.TemporaryDirectory() as directory:
            key_file = Path(directory) / "key.txt"
            key_file.write_text(key + "\n", encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            factory = (lambda url, access_key: runtime or FakeRuntime())
            with patch.object(kit, "http_request", side_effect=factory) as made, \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                try:
                    code = kit.main(["--runtime-url", "https://runtime.example", "--access-key-file", str(key_file),
                                     "--group", "g1", "--timeout-seconds", "5", *arguments])
                except SystemExit as exit_:
                    code = exit_.code
            return code, stdout.getvalue(), stderr.getvalue(), made

    def test_one_json_line_and_exit_code_per_outcome(self):
        code, out, _, made = self.main()
        self.assertEqual(code, 0)
        self.assertEqual(len(out.splitlines()), 1)
        record = json.loads(out)
        self.assertEqual((record["outcome"], record["kitVersion"], record["runtimeUrl"]),
                         ("passed", "source", "https://runtime.example"))
        self.assertEqual(made.call_args.args, ("https://runtime.example", KEY))
        self.assertNotIn(KEY, out)
        code, out, _, _ = self.main(runtime=FakeRuntime(faults={"POST /api/v1/tasks": 403}))
        self.assertEqual((code, json.loads(out)["outcome"]), (1, "failed"))
        code, out, _, _ = self.main(runtime=FakeRuntime(faults={"POST /api/v1/tasks": "lost"}))
        self.assertEqual((code, json.loads(out)["outcome"]), (3, "unconfirmed"))

    def test_configuration_errors_exit_2_without_json_or_key(self):
        for arguments, key in ((("--items", "0"), KEY), ((), "not-a-key"), (("--timeout-seconds", "0"), KEY)):
            code, out, err, _ = self.main(*arguments, key=key)
            self.assertEqual((code, out), (2, ""))
            self.assertNotIn(KEY, err)

    def test_runtime_url_rejects_plain_remote_http_and_embedded_credentials(self):
        for url in ("http://runtime.example", "https://user:pass@runtime.example", "https://runtime.example/?a=1",
                    "ftp://runtime.example"):
            with self.assertRaises(Exception):
                kit._runtime_url(url)
        for url in ("http://127.0.0.1:18182", "http://localhost:8080", "https://staging.example/base"):
            self.assertEqual(kit._runtime_url(url), url)


if __name__ == "__main__":
    unittest.main()
