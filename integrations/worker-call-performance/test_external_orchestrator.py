"""Exercise the real external controller with local SSH/systemd stand-ins only."""
from __future__ import annotations

from argparse import Namespace
from contextlib import nullcontext
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import signal
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("external_performance_under_test", ROOT / ".github/scripts/run_external_performance.py")
ORCHESTRATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORCHESTRATOR)
RUN = "12345678"
TAG = "v4.4.0"
COMMIT = "a" * 40
WORKER_ID = "12345678-1234-1234-1234-123456789abc"
TASK_ID = "perf-rpc-" + "b" * 32
ACCESS_KEY = "xak_private_operator_key_123456"
PRIVATE_PATH = "/run/user/1001/xa-mass-performance/12345678/access-key.txt"


def configuration():
    return {"runtimeUrl": "https://runtime.example", "workerRuntimeUrl": "https://worker.example",
            "project": "default", "endpointManager": "default", "groups": ["perf-a", "perf-b"],
            "workersPerGroup": 5000, "consumerId": "public43-performance", "assignmentBatchLimit": 100,
            "serverDeployment": {"version": TAG[1:], "gitCommit": COMMIT, "configurationId": "consumer"}}


def collection(status="passed"):
    return {"summary.json": {"status": status}, "summary.md": "# External Performance\n",
            "consumption.json": {"tag": TAG, "gitCommit": COMMIT},
            "orchestration.json": {"state": "finished", "receipt": {"status": status}}}


class FakeSSH:
    """One remote Run, including a start whose acknowledgement can be lost."""
    def __init__(self, *, attached=True, state="finished", lose_start_ack=False, on_status=None):
        self.attached = attached
        self.state = state
        self.lose_start_ack = lose_start_ack
        self.on_status = on_status
        self.actions = []
        self.copies = []

    def ssh(self, command, data=None, timeout=60):
        if command[1] == "-c":
            self.actions.append("bootstrap:" + command[-1])
            return {"root": "/home/xa-mass-performance/runs/" + RUN, "helper": self.attached}
        action = command[3]
        self.actions.append(action)
        if action == "prepare":
            self.envelope = json.loads(data)
            self.state = "prepared"
        elif action == "start":
            self.state = "running"
            if self.lose_start_ack:
                raise ORCHESTRATOR.Failure("Command or transport was not confirmed")
        elif action == "status":
            if self.on_status:
                self.on_status()
                self.on_status = None
            elif self.state == "running":
                self.state = "finished"
        elif action == "stop":
            self.state = "finished"
        elif action == "collect":
            result = collection()
            if hasattr(self, "envelope"):
                result["consumption.json"] = self.envelope["consumption"]
            return result
        return {"state": self.state}

    def copy(self, source, destination):
        self.copies.append(destination)


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / "public"
        self.args = Namespace(repository="owner/repository", workers_per_group=5000, repetitions=1,
                              output_dir=self.output, action="run")
        self.environment = {"GITHUB_RUN_ID": RUN, "GITHUB_RUN_ATTEMPT": "1", "RELEASE_TAG": TAG,
                            "RELEASE_COMMIT": COMMIT, "SERVER_RELEASE_TAG": TAG, "SERVER_RELEASE_COMMIT": COMMIT,
                            "DEPLOYMENT_JSON": json.dumps(configuration()),
                            "CONNECTION_BUDGET_JSON": json.dumps({"perCredential": 20000, "total": 30000, "reserved": 20}),
                            "RUNTIME_ACCESS_KEY": ACCESS_KEY, "WORKER_GATEWAY_KEY_ID": "operator-gate",
                            "WORKER_GATEWAY_SECRET": "private-gateway-secret"}
        self.old_umask = os.umask(0o077)
        self.addCleanup(os.umask, self.old_umask)

    def execute(self, fake, *, attempt=1, handlers=None):
        self.environment["GITHUB_RUN_ATTEMPT"] = str(attempt)
        consumed = dict(collection()["consumption.json"], release="release-url", releaseId=44,
                        kitSha256="c" * 64, manifest={"schemaVersion": 3})
        consumed.update(tag=self.environment["RELEASE_TAG"], gitCommit=self.environment["RELEASE_COMMIT"])
        self.downloader = SimpleNamespace(prepare=Mock(return_value=consumed))
        loader = SimpleNamespace(exec_module=lambda module: None)
        signals = handlers if handlers is not None else {}

        def install(sig, handler):
            previous = signals.get(sig, signal.SIG_DFL)
            signals[sig] = handler
            return previous

        with patch.dict(os.environ, self.environment, clear=True), \
                patch.object(ORCHESTRATOR, "Transport", return_value=fake), \
                patch.object(ORCHESTRATOR.importlib.util, "spec_from_file_location", return_value=SimpleNamespace(loader=loader)), \
                patch.object(ORCHESTRATOR.importlib.util, "module_from_spec", return_value=self.downloader), \
                patch.object(ORCHESTRATOR.signal, "signal", side_effect=install), \
                patch.object(ORCHESTRATOR.time, "sleep"):
            return ORCHESTRATOR.controller(self.args)

    def test_same_run_retry_attaches_and_collects_without_preparing_or_starting(self):
        for attempt, state in ((1, "running"), (2, "finished")):
            with self.subTest(attempt=attempt, state=state):
                fake = FakeSSH(state=state)
                self.assertEqual(0, self.execute(fake, attempt=attempt))
                self.assertEqual([], fake.copies)
                self.downloader.prepare.assert_not_called()
                self.assertEqual(["bootstrap:" + ("create" if attempt == 1 else "query"), "status", "collect"], fake.actions)
                public = json.loads((self.output / "orchestration.json").read_text())
                self.assertTrue(public["attached"])
                self.assertEqual(attempt, public["controllerAttempt"])

    def test_lost_start_ack_only_observes_original_run(self):
        fake = FakeSSH(attached=False, lose_start_ack=True)
        self.assertEqual(0, self.execute(fake))
        self.assertEqual(["bootstrap:create", "prepare", "start", "status", "collect"], fake.actions)
        self.assertEqual(1, fake.actions.count("start"))
        self.assertEqual(2, len(fake.copies))
        self.assertEqual(5000, fake.envelope["workers"])
        self.assertEqual({"perCredential": 20000, "total": 30000, "reserved": 20}, fake.envelope["budget"])
        self.assertEqual(1, fake.envelope["repetitions"])
        self.assertEqual(COMMIT, fake.envelope["commit"])

    def test_cancellation_stops_same_run_and_still_collects(self):
        handlers = {}
        fake = FakeSSH(state="running", on_status=lambda: handlers[signal.SIGTERM](signal.SIGTERM, None))
        self.assertEqual(1, self.execute(fake, handlers=handlers))
        self.assertEqual(["bootstrap:create", "status", "stop", "collect"], fake.actions)
        receipt = json.loads((self.output / "orchestration.json").read_text())
        self.assertTrue(receipt["controllerCancelled"])
        self.assertEqual(set(ORCHESTRATOR.FILES), {path.name for path in self.output.iterdir()})

    def test_explicit_consumer_kit_pin_does_not_relabel_deployed_server(self):
        self.environment.update(RELEASE_TAG="v4.4.1", RELEASE_COMMIT="d" * 40)
        fake = FakeSSH(attached=False)
        self.assertEqual(0, self.execute(fake))
        self.assertEqual("v4.4.1", self.downloader.prepare.call_args.args[1])
        self.assertEqual("d" * 40, self.downloader.prepare.call_args.args[2])
        self.assertEqual(TAG, fake.envelope["serverTag"])
        self.assertEqual(COMMIT, fake.envelope["serverCommit"])
        self.assertEqual(TAG[1:], fake.envelope["deployment"]["serverDeployment"]["version"])
        artifact = json.loads((self.output / "consumption.json").read_text())
        self.assertEqual("v4.4.1", artifact["tag"])
        self.assertEqual("d" * 40, artifact["gitCommit"])

    def test_finished_stop_only_collects_original_without_restarting(self):
        self.args.action = "stop"
        fake = FakeSSH(state="finished")
        self.assertEqual(0, self.execute(fake, attempt=2))
        self.assertEqual(["bootstrap:query", "stop", "collect"], fake.actions)
        self.assertEqual([], fake.copies)
        self.downloader.prepare.assert_not_called()

    def test_bad_release_pins_reject_before_download_prepare_or_start(self):
        config = configuration()
        config["serverDeployment"]["gitCommit"] = "d" * 40
        self.environment["DEPLOYMENT_JSON"] = json.dumps(config)
        fake = FakeSSH(attached=False)
        with self.assertRaises(ORCHESTRATOR.Failure):
            self.execute(fake)
        self.downloader.prepare.assert_not_called()
        self.assertEqual(["bootstrap:create"], fake.actions)


class RemoteLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "runs" / RUN
        self.private = Path(self.temporary.name) / "private" / RUN
        self.root.mkdir(parents=True)
        self.private.mkdir(parents=True)
        self.addCleanup(patch.stopall)
        patch.object(ORCHESTRATOR, "paths", return_value=(self.root, self.private)).start()
        patch.object(ORCHESTRATOR, "locked", side_effect=lambda _, **kwargs: nullcontext()).start()
        patch.object(ORCHESTRATOR, "user_environment", return_value={}).start()

    def write(self, name, value):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        ORCHESTRATOR.write_json(target, value)

    def test_start_marker_prevents_second_systemd_start_after_lost_ack(self):
        self.write("state.json", {"repetitions": 1})
        with patch.object(ORCHESTRATOR, "call", side_effect=ORCHESTRATOR.Failure("lost acknowledgement")) as systemd, \
                patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "loaded", "ActiveState": "active"}):
            with self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.remote_start(RUN)
            self.assertTrue((self.root / "start-request.json").exists())
            self.assertEqual("running", ORCHESTRATOR.remote_start(RUN)["state"])
        self.assertEqual(1, systemd.call_count)
        command = systemd.call_args.args[0]
        self.assertIn("--unit=" + ORCHESTRATOR.unit_name(RUN), command)
        self.assertIn("--property=Restart=no", command)
        self.assertTrue(any(value.startswith("--property=ExecStopPost=") for value in command))

    def test_unknown_after_start_marker_is_observation_only(self):
        self.write("state.json", {"repetitions": 1})
        self.write("start-request.json", {"runId": RUN})
        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}), \
                patch.object(ORCHESTRATOR, "call") as systemd:
            self.assertEqual("unknown", ORCHESTRATOR.remote_start(RUN)["state"])
        systemd.assert_not_called()

    def test_stop_removes_original_and_runner_credentials_only_for_current_unit(self):
        self.write("state.json", {"repetitions": 1})
        self.write("start-request.json", {"runId": RUN})
        for target in (self.private / "access-key.txt", self.private / "gateway.json",
                       self.root / "result/private/access-key.txt", self.root / "result/private/worker-gateway.json"):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("private-input")
        state = {"LoadState": "loaded", "ActiveState": "active"}
        with patch.object(ORCHESTRATOR, "unit_status", side_effect=lambda _: dict(state)), \
                patch.object(ORCHESTRATOR, "call", side_effect=lambda *args, **kwargs: state.update(ActiveState="inactive")) as systemd:
            receipt = ORCHESTRATOR.remote_stop(RUN)["receipt"]
            self.assertEqual("cancelled", receipt["status"])
            self.assertTrue(receipt["privateInputsRemoved"])
            self.assertTrue((self.root / "stop-request.json").exists())
            self.assertFalse(list(self.private.iterdir()))
            self.assertFalse(list((self.root / "result/private").iterdir()))
            self.assertEqual(receipt, ORCHESTRATOR.remote_stop(RUN)["receipt"])
        self.assertEqual(["systemctl", "--user", "stop", ORCHESTRATOR.unit_name(RUN)], systemd.call_args.args[0])
        self.assertEqual(1, systemd.call_count)

    def test_successful_final_receipt_is_immutable_when_stop_arrives_later(self):
        self.write("execution.json", {"exitCode": 0})
        self.write("result/evidence/lane-summary.json", {"status": "passed"})
        receipt = ORCHESTRATOR.remote_finalize(RUN)
        state = {"LoadState": "loaded", "ActiveState": "active", "SubState": "exited"}
        with patch.object(ORCHESTRATOR, "unit_status", side_effect=lambda _: dict(state)), \
                patch.object(ORCHESTRATOR, "call", side_effect=lambda *args, **kwargs: state.update(ActiveState="inactive")) as systemd:
            self.assertEqual(receipt, ORCHESTRATOR.remote_stop(RUN)["receipt"])
            self.assertEqual(receipt, ORCHESTRATOR.remote_stop(RUN)["receipt"])
        self.assertEqual("passed", receipt["status"])
        self.assertEqual(1, systemd.call_count)
        self.assertEqual(["systemctl", "--user", "stop", ORCHESTRATOR.unit_name(RUN)], systemd.call_args.args[0])

    def test_rejected_preflight_never_extracts_kit_or_writes_private_input(self):
        envelope = {"deployment": configuration(), "tag": TAG, "commit": COMMIT,
                    "serverTag": TAG, "serverCommit": COMMIT, "workers": 5000,
                    "budget": {"perCredential": 9999, "total": 30000, "reserved": 20}}
        with patch.object(ORCHESTRATOR.os, "getuid", return_value=self.root.stat().st_uid, create=True), \
                patch.object(ORCHESTRATOR, "preflight", side_effect=ORCHESTRATOR.Failure("Connection budget insufficient")), \
                patch.object(ORCHESTRATOR, "extract_kit") as extract:
            with self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.remote_prepare(RUN, envelope)
        extract.assert_not_called()
        self.assertEqual([], list(self.private.iterdir()))
        self.assertFalse((self.root / "deployment.json").exists())
        self.assertFalse((self.root / "state.json").exists())

    def test_all_four_collected_files_exclude_private_paths_keys_and_worker_ids(self):
        self.write("state.json", {"runId": RUN, "tag": TAG, "commit": COMMIT, "consumption": {"tag": TAG}})
        self.write("receipt.json", {"status": "passed", "privateInputsRemoved": True})
        self.write("result/evidence/lane-summary.json", {
            "status": "passed", "bootstrap": {"workerIds": [WORKER_ID]},
            "cases": [{"case": "task-any-500", "repetition": 1, "status": "invalid", "workerSamples": 10000,
                       "invalidReasons": ["worker-observation-unavailable"]}]})
        self.write("result/evidence/harness/r1/task-any-500/case.json", {
            "metrics": {"unknownSubmissions": 96, "http429": 5546, "unresolvedAcceptedIds": [TASK_ID]}})
        markdown = self.root / "result/evidence/summary.md"
        markdown.write_text("# Original Performance\ninvalid " + WORKER_ID + " " + PRIVATE_PATH + " " + ACCESS_KEY, encoding="utf-8")
        public = ORCHESTRATOR.remote_collect(RUN)
        self.assertEqual(set(ORCHESTRATOR.FILES), set(public))
        self.assertEqual("passed", public["summary.json"]["status"])
        self.assertEqual("invalid", public["summary.json"]["cases"][0]["status"])
        self.assertEqual(96, public["summary.json"]["cases"][0]["originalMetrics"]["unknownSubmissions"])
        self.assertEqual(5546, public["summary.json"]["cases"][0]["originalMetrics"]["http429"])
        self.assertTrue(public["orchestration.json"]["receipt"]["privateInputsRemoved"])
        for hidden in (WORKER_ID, PRIVATE_PATH, ACCESS_KEY, "bootstrap"):
            self.assertNotIn(hidden, json.dumps(public))

    def test_malformed_summary_still_cleans_credentials_and_remains_collectable(self):
        summary = self.root / "result/evidence/lane-summary.json"
        summary.parent.mkdir(parents=True)
        summary.write_text("{unfinished", encoding="utf-8")
        (self.private / "access-key.txt").write_text(ACCESS_KEY)
        self.write("execution.json", {"exitCode": 0})
        receipt = ORCHESTRATOR.remote_finalize(RUN)
        self.assertEqual("unknown", receipt["status"])
        self.assertTrue(receipt["privateInputsRemoved"])
        self.assertFalse((self.private / "access-key.txt").exists())
        public = ORCHESTRATOR.remote_collect(RUN)
        self.assertEqual("unknown", public["orchestration.json"]["receipt"]["status"])
        self.assertTrue(public["summary.json"]["summaryParseFailure"])

    def test_cancelled_unprepared_run_cannot_later_prepare_or_start(self):
        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}), \
                patch.object(ORCHESTRATOR.os, "getuid", return_value=self.root.stat().st_uid, create=True), \
                patch.object(ORCHESTRATOR, "preflight") as preflight, patch.object(ORCHESTRATOR, "call") as systemd:
            receipt = ORCHESTRATOR.remote_stop(RUN)["receipt"]
            self.assertEqual("cancelled", receipt["status"])
            self.assertEqual(receipt, ORCHESTRATOR.remote_prepare(RUN, {})["receipt"])
            self.assertEqual(receipt, ORCHESTRATOR.remote_start(RUN)["receipt"])
        preflight.assert_not_called()
        systemd.assert_not_called()


class InputsAndProjectionTest(unittest.TestCase):
    def test_release_identity_topology_and_host_bounds_are_checked(self):
        self.assertEqual(configuration(), ORCHESTRATOR.validate_config(configuration(), TAG, COMMIT, 5000))
        for field, value in (("consumerId", "different"), ("groups", ["same", "same"]), ("project", "")):
            config = configuration()
            config[field] = value
            with self.subTest(field=field), self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.validate_config(config, TAG, COMMIT, 5000)
        for workers in (0, 15001, True):
            with self.subTest(workers=workers), self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.validate_config(configuration(), TAG, COMMIT, workers)
        for tag, commit in (("4.4.0", COMMIT), (TAG, "short"), ("v4.4.1", COMMIT)):
            with self.subTest(tag=tag, commit=commit), self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.validate_config(configuration(), tag, commit, 5000)

    def test_connection_budget_rejects_before_business_execution(self):
        resource = SimpleNamespace(RLIMIT_NOFILE=7, getrlimit=lambda _: (65535, 65535))
        reads = {"/proc/sys/net/ipv4/ip_local_port_range": "32768 60999\n",
                 "/proc/sys/net/ipv4/ip_local_reserved_ports": "40000-40099\n",
                 "/proc/meminfo": "MemTotal: 16777216 kB\nMemAvailable: 8388608 kB\n"}
        original = Path.read_text

        def read(path, *args, **kwargs):
            return reads[str(path).replace("\\", "/")] if str(path).replace("\\", "/") in reads else original(path, *args, **kwargs)

        with patch.dict("sys.modules", resource=resource), patch.object(Path, "read_text", read), \
                patch.object(ORCHESTRATOR.shutil, "disk_usage", return_value=SimpleNamespace(free=2 * 1024**3)), \
                patch.object(ORCHESTRATOR, "user_environment", return_value={}), \
                patch.object(ORCHESTRATOR, "call", return_value=b"") as systemd:
            for budget in ({"perCredential": 9999, "total": 30000, "reserved": 20},
                           {"perCredential": 20000, "total": 10000, "reserved": 20},
                           {"perCredential": True, "total": 30000, "reserved": 20}):
                with self.subTest(budget=budget), self.assertRaises(ORCHESTRATOR.Failure):
                    ORCHESTRATOR.preflight(configuration(), 5000, budget)
            systemd.assert_not_called()
            evidence = ORCHESTRATOR.preflight(configuration(), 5000, {"perCredential": 20000, "total": 30000, "reserved": 20})
        self.assertEqual(10000, evidence["connections"])
        self.assertEqual(28132, evidence["availableLocalPorts"])
        self.assertEqual(32768, evidence["effectiveFdBudget"])

    def test_archive_requires_exact_manifest_entry_and_confined_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "kit.zip"
            prefix = "xa-mass-worker-simulator-4.4.0"
            manifest = {"schemaVersion": 3, "version": "4.4.0", "gitCommit": COMMIT, "performanceEntry": ORCHESTRATOR.ENTRY}
            for bad in (None, "../escape", "/absolute", "symlink", "wrong-manifest", "missing-entry"):
                with self.subTest(member=bad):
                    with zipfile.ZipFile(archive, "w") as target:
                        target.writestr(prefix + "/manifest.json", json.dumps(dict(manifest, schemaVersion=2) if bad == "wrong-manifest" else manifest))
                        if bad != "missing-entry":
                            target.writestr(prefix + "/" + ORCHESTRATOR.ENTRY, "# published entry")
                        if bad == "symlink":
                            member = zipfile.ZipInfo(prefix + "/link")
                            member.external_attr = (stat.S_IFLNK | 0o777) << 16
                            target.writestr(member, "/etc/passwd")
                        elif bad in ("../escape", "/absolute"):
                            target.writestr(bad, "outside")
                    if bad is None:
                        kit, actual = ORCHESTRATOR.extract_kit(archive, root, TAG, COMMIT, ORCHESTRATOR.digest(archive))
                        self.assertEqual(manifest, actual)
                        self.assertTrue((kit / ORCHESTRATOR.ENTRY).is_file())
                    else:
                        with self.assertRaises(ORCHESTRATOR.Failure):
                            ORCHESTRATOR.extract_kit(archive, root, TAG, COMMIT, ORCHESTRATOR.digest(archive))
            with self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.extract_kit(archive, root, TAG, COMMIT, "0" * 64)

    def test_public_projection_preserves_verdicts_and_counts_without_private_identity(self):
        raw = {"status": "invalid", "bootstrap": {"workers": [WORKER_ID]}, "private": PRIVATE_PATH,
               "cleanup": {"ownedProcessesStopped": True, "privateCredentialCopiesRemoved": True,
                           "workerConnections": {"status": "confirmed", "identityCount": 10000, "states": {"disconnected": 10000}}},
               "resourcePeaks": {"host": {"rssBytes": 123456}},
               "runnerFailure": "read " + PRIVATE_PATH + " with " + ACCESS_KEY,
               "cases": [{"case": "task-any-2000", "status": "invalid", "workerSamples": 10000,
                          "invalidReasons": ["worker-observation-unavailable"], "generatorLimited": True,
                          "acceptedResultsAfterDrain": {"succeeded": 3476, "failed": 2, "not_observed": 0},
                          "metrics": {"unknownSubmissions": 11764, "clientTimeouts": 96, "http429": 11668,
                                      "unresolvedAcceptedIds": [TASK_ID], "secret": "gateway-secret"},
                          "samples": [{"id": WORKER_ID}], "taskIds": [TASK_ID]}]}
        original = deepcopy(raw)
        public = ORCHESTRATOR.safe_result(raw)
        self.assertEqual(original, raw)
        row = public["cases"][0]
        self.assertEqual("invalid", public["status"])
        self.assertEqual("invalid", row["status"])
        self.assertEqual(10000, row["workerSamples"])
        self.assertEqual(96, row["metrics"]["clientTimeouts"])
        self.assertEqual(11764, row["metrics"]["unknownSubmissions"])
        self.assertEqual(11668, row["metrics"]["http429"])
        self.assertEqual(2, row["acceptedResultsAfterDrain"]["failed"])
        self.assertEqual(123456, public["resourcePeaks"]["host"]["rssBytes"])
        self.assertTrue(public["cleanup"]["privateCredentialCopiesRemoved"])
        self.assertEqual(10000, public["cleanup"]["workerConnections"]["states"]["disconnected"])
        rendered = json.dumps(public)
        for hidden in (WORKER_ID, TASK_ID, ACCESS_KEY, PRIVATE_PATH, "gateway-secret", "bootstrap", "unresolvedAcceptedIds"):
            self.assertNotIn(hidden, rendered)

    def test_sample_aggregation_keeps_not_sent_separate_from_sent_without_http_response(self):
        rows = [{"sentOffsetMillis": -1, "httpStatus": 0, "outcome": "not_sent", "messageId": WORKER_ID, "accepted": False, "observedResult": "not_observed"},
                {"sentOffsetMillis": 0, "httpStatus": 0, "outcome": "unknown", "detail": "client-timeout", "messageId": WORKER_ID, "accepted": False, "observedResult": "not_observed"},
                {"sentOffsetMillis": 5, "httpStatus": 429, "outcome": "unknown", "messageId": WORKER_ID, "accepted": False, "observedResult": "not_observed"},
                {"sentOffsetMillis": 8, "httpStatus": 200, "outcome": "succeeded", "messageId": WORKER_ID, "accepted": True, "observedResult": "succeeded"}]
        with tempfile.TemporaryDirectory() as temporary:
            result = Path(temporary)
            target = result / "evidence/harness/r1/task-any-2000/samples.jsonl"
            target.parent.mkdir(parents=True)
            target.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            for relative in ("r1/task-any-2000/warmup-samples.jsonl", "warmup/task-any-2000/samples.jsonl", "r2/task-any-2000/samples.jsonl"):
                extra = result / "evidence/harness" / relative
                extra.parent.mkdir(parents=True, exist_ok=True)
                extra.write_text(json.dumps(rows[2]) + "\n", encoding="utf-8")
            evidence = ORCHESTRATOR.sample_counts(result)
        self.assertEqual(5, evidence["totals"]["samples"])
        self.assertEqual(4, evidence["totals"]["sent"])
        self.assertEqual(1, evidence["totals"]["notSent"])
        self.assertEqual(1, evidence["totals"]["sentHttp0Unknown"])
        self.assertEqual(2, evidence["totals"]["http429"])
        self.assertEqual(3, evidence["totals"]["outcome:unknown"])
        self.assertEqual(1, evidence["totals"]["clientTimeouts"])
        self.assertEqual(1, evidence["phases"]["discardedWarmup"]["samples"])
        self.assertEqual(1, evidence["phases"]["innerWarmup"]["samples"])
        self.assertEqual(4, len(evidence["cases"]))
        self.assertNotIn(WORKER_ID, json.dumps(evidence))


if __name__ == "__main__":
    unittest.main()
