from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location("deployment_performance", Path(__file__).with_name("run_worker_call_performance.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DeploymentRunnerTest(unittest.TestCase):
    def test_deployment_dispatch_has_no_docker_build_or_reference_host_dependency(self):
        with patch.object(runner.sys, "argv", ["runner", "--deployment-config", "consumer.json"]), \
                patch.object(runner.sys, "platform", "linux"), \
                patch.object(runner, "main_deployment", return_value=0) as deployment, \
                patch.object(runner, "command", side_effect=AssertionError("local orchestration reached")), \
                patch.object(runner, "build", side_effect=AssertionError("build reached")):
            self.assertEqual(0, runner.main())
            self.assertEqual(Path("consumer.json"), deployment.call_args.args[0].deployment_config)

    def test_unsupported_server_operations_are_rejected_before_deployment(self):
        for extra in (("--lane-modes", "saturation"), ("--lane-attribution=on",), ("--diagnostics", "jfr"),
                      ("--lane-history", "history"), ("--experiment-config", "experiment.json")):
            with self.subTest(extra=extra), patch.object(runner.sys, "argv", ["runner", "--deployment-config", "consumer.json", *extra]), \
                    patch.object(runner, "main_deployment") as deployment, patch("sys.stderr", new=io.StringIO()):
                with self.assertRaises(SystemExit):
                    runner.main()
                deployment.assert_not_called()

    def test_published_classpaths_and_external_output_need_no_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "kit"
            (root / "acceptance/performance/harness/lib").mkdir(parents=True)
            with patch.object(runner, "ROOT", root):
                self.assertEqual(root / "acceptance/performance/harness/lib/*", runner.harness_classpath())
                self.assertEqual(root / "lib/*", runner.host_classpath())
                with self.assertRaisesRegex(ValueError, "outside the immutable"):
                    runner.fresh_output(root / "runs/run-1", deployment=True)
                output = runner.fresh_output(Path(directory) / "runs/run-1", deployment=True)
                self.assertTrue(output.is_dir())
                with self.assertRaises(ValueError):
                    runner.fresh_output(output, deployment=True)

    def test_consumer_sampler_never_manufactures_remote_resource_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "resources.jsonl"
            sampler = runner.Sampler(path, None)
            process = Mock(pid=123)
            process.poll.return_value = None
            sampler.register("host", process)
            sample = {"cpuSeconds": 1, "nativeThreads": 20, "openFileDescriptors": 30, "rssBytes": 1024}
            with patch.object(runner, "process_sample", return_value=sample), \
                    patch.object(sampler.stopped, "wait", side_effect=lambda _: sampler.stopped.set()):
                sampler.run()
            self.assertIsNone(sampler.failure)
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(["host"], [row["role"] for row in rows])

    def test_cleanup_uses_exact_owned_ids_and_never_mutates_remote_state(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key.txt"
            key.write_text("xak_private_test_key")
            config = {"accessKeyFile": str(key), "runtimeUrl": "https://runtime.example", "endpointManager": "public"}
            responses = [io.BytesIO(json.dumps({"statesByWorkerId": {"a": "unknown", "b": "disconnected"}}).encode())]
            with patch.object(runner, "deployment_open", side_effect=responses) as request:
                result = runner.observe_deployment_disconnect(config, ["a", "b"])
            self.assertEqual("confirmed", result["status"])
            sent = request.call_args.args[0]
            self.assertTrue(sent.full_url.endswith("/workers:network-observe"))
            self.assertEqual(["a", "b"], json.loads(sent.data))
            self.assertTrue(key.is_file())
            with patch.object(runner, "deployment_open", return_value=io.BytesIO(b'{"statesByWorkerId":{}}')):
                mismatch = runner.observe_deployment_disconnect(config, ["a"])
            self.assertEqual("unconfirmed", mismatch["status"])
            self.assertEqual({"identity-set-mismatch": 1}, mismatch["observation"]["errorTypes"])
            self.assertEqual(0, mismatch["observation"]["observedIdentityCount"])

    def test_cleanup_bounds_eight_parallel_pages_and_merges_out_of_order_exact_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.cleanup_config(directory)
            identities = ["worker-" + str(i) for i in range(1001)]
            lock, first_wave, later = threading.Lock(), threading.Barrier(8, timeout=3), threading.Event()
            active, maximum, requests = 0, 0, []

            def observe(request, *, timeout):
                nonlocal active, maximum
                page = json.loads(request.data)
                self.assertLessEqual(len(page), 100)
                self.assertGreater(timeout, 0)
                self.assertLessEqual(timeout, 5)
                with lock:
                    active += 1
                    maximum = max(maximum, active)
                    requests.append(page)
                    order = len(requests)
                try:
                    if order <= 8:
                        first_wave.wait()
                    if page[0] == "worker-0":
                        self.assertTrue(later.wait(3))
                    else:
                        later.set()
                    return io.BytesIO(json.dumps({"statesByWorkerId": {identity: "disconnected" for identity in reversed(page)}}).encode())
                finally:
                    with lock:
                        active -= 1

            with patch.object(runner, "deployment_open", side_effect=observe):
                result = runner.observe_deployment_disconnect(config, identities)
            self.assertEqual("confirmed", result["status"])
            self.assertEqual(8, maximum)
            self.assertEqual(11, len(requests))
            self.assertEqual(set(identities), {identity for page in requests for identity in page})
            self.assertEqual(1001, result["states"]["disconnected"])
            self.assertTrue(result["observation"]["complete"])
            self.assertEqual(11, result["observation"]["completedPages"])

    def test_cleanup_deadline_returns_without_waiting_for_or_accepting_late_pages(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.cleanup_config(directory)
            release, finished = threading.Event(), threading.Event()
            started = []

            def delayed(request, *, timeout):
                started.append(json.loads(request.data))
                release.wait(3)
                finished.set()
                return io.BytesIO(json.dumps({"statesByWorkerId": {identity: "disconnected" for identity in started[0]}}).encode())

            try:
                with patch.object(runner, "deployment_open", side_effect=delayed):
                    before = time.monotonic()
                    result = runner.observe_deployment_disconnect(config, ["only-owned-worker"], seconds=.05)
                    elapsed = time.monotonic() - before
                    self.assertLess(elapsed, 1)
                    self.assertEqual("unconfirmed", result["status"])
                    self.assertTrue(result["observation"]["deadlineExpired"])
                    self.assertEqual(0, result["observation"]["completedPages"])
                    self.assertFalse(result["observation"]["complete"])
                    snapshot = json.dumps(result, sort_keys=True)
                    release.set()
                    self.assertTrue(finished.wait(3))
                    self.assertEqual(snapshot, json.dumps(result, sort_keys=True))
            finally:
                release.set()

    def test_cleanup_expired_budget_issues_no_pages_and_partial_errors_are_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.cleanup_config(directory)
            with patch.object(runner, "deployment_open") as observe:
                result = runner.observe_deployment_disconnect(config, ["owned"], seconds=0)
            observe.assert_not_called()
            self.assertTrue(result["observation"]["deadlineExpired"])
            private = "private-transport-detail-and-worker-identity"

            def fail_page(request, *, timeout):
                page = json.loads(request.data)
                if page[0] == "worker-100":
                    raise runner.urllib.error.URLError(private)
                return io.BytesIO(json.dumps({"statesByWorkerId": {identity: "unknown" for identity in page}}).encode())

            with patch.object(runner, "deployment_open", side_effect=fail_page):
                result = runner.observe_deployment_disconnect(config, ["worker-" + str(i) for i in range(201)])
            self.assertEqual("unconfirmed", result["status"])
            self.assertEqual(1, result["observation"]["errorTypes"]["URLError"])
            self.assertLess(result["observation"]["completedPages"], 3)
            self.assertFalse(result["observation"]["complete"])
            self.assertNotIn(private, json.dumps(result))
            self.assertNotIn("xak_private_test_key", json.dumps(result))

    def test_cleanup_does_not_carry_disconnected_evidence_between_scans(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.cleanup_config(directory)
            responses = [io.BytesIO(json.dumps({"statesByWorkerId": {"a": "connected", "b": "disconnected"}}).encode()),
                         io.BytesIO(json.dumps({"statesByWorkerId": {"a": "disconnected", "b": "unknown"}}).encode())]
            with patch.object(runner, "deployment_open", side_effect=responses), patch.object(runner.time, "sleep"):
                result = runner.observe_deployment_disconnect(config, ["a", "b"])
            self.assertEqual("confirmed", result["status"])
            self.assertEqual({"disconnected": 1, "unknown": 1}, result["states"])
            self.assertEqual(2, result["observation"]["scanCount"])

    @staticmethod
    def cleanup_config(directory):
        key = Path(directory) / "key.txt"
        key.write_text("xak_private_test_key")
        return {"accessKeyFile": str(key), "runtimeUrl": "https://runtime.example", "endpointManager": "public"}

    def test_authenticated_cleanup_does_not_follow_redirects(self):
        handler = runner.DeploymentNoRedirect()
        request = runner.urllib.request.Request("https://runtime.example/path", headers={"Authorization": "Bearer private"})
        self.assertIsNone(handler.redirect_request(request, None, 302, "redirect", {}, "https://other.example/path"))

    def test_existing_deployment_report_identifies_missing_remote_evidence(self):
        result = {"status": "passed", "cases": [], "executionEnvironment": "existing-server", "groups": ["a", "b"],
                  "workersPerGroup": 5000, "assignmentBatchLimit": 100, "hostMaxHeap": "3g", "serverDeployment": {"version": "4.4.0"}}
        report = runner.lane_markdown(result)
        self.assertIn("assignment ceiling 100.", report)
        self.assertIn("not a same-host reference", report)
        self.assertIn("were not collected", report)
        self.assertNotIn("legacy world", report)

    def test_invalid_measurement_preserves_original_next_case_quiesce_flow(self):
        with tempfile.TemporaryDirectory() as directory:
            result = {"cases": [], "timingsMillis": {}}
            plan = runner.lane_plan((500,), 1, ("open",))
            def phase(name, *args, **kwargs):
                return {"status": "passed"} if name == "quiesce" else {"status": "invalid", "invalidReasons": ["workers-exhausted"]}
            with patch.object(runner, "run_lane_harness", side_effect=phase) as harness, patch("sys.stdout", new=io.StringIO()):
                runner.run_lane_cases(Path(directory), 100, plan, None, {}, {}, Mock(), result,
                                      deployment=Path(directory) / "config.json", lab_url="http://127.0.0.1:1")
            self.assertEqual(3, len(result["cases"]))
            self.assertEqual(["quiesce", "case"] * 3, [call.args[0] for call in harness.call_args_list])


if __name__ == "__main__":
    unittest.main()
