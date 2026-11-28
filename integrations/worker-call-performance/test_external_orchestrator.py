"""Exercise the real external controller with local SSH/systemd stand-ins only."""
from __future__ import annotations

from argparse import Namespace
from contextlib import contextmanager, nullcontext
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("external_performance_under_test", ROOT / ".github/scripts/run_external_performance.py")
ORCHESTRATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORCHESTRATOR)
REPORT_PATH = ROOT / "integrations/worker-call-performance/reporting.py"
REPORT_SPEC = importlib.util.spec_from_file_location("performance_reporting_under_test", REPORT_PATH)
REPORTING = importlib.util.module_from_spec(REPORT_SPEC)
REPORT_SPEC.loader.exec_module(REPORTING)
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
        self.copied_bytes = {}

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
        if source.is_file():
            self.copied_bytes[Path(destination).name] = source.read_bytes()


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / "public"
        self.args = Namespace(repository="owner/repository", workers_per_group=5000, repetitions=1,
                              output_dir=self.output, action="run")
        self.environment = {"GITHUB_RUN_ID": RUN, "GITHUB_RUN_ATTEMPT": "1", "RELEASE_TAG": TAG,
                            "GITHUB_SHA": "c" * 40,
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
        consumed.update(tag=self.environment.get("RELEASE_TAG"), gitCommit=self.environment.get("RELEASE_COMMIT"))
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
        self.assertEqual(["reporting.py", "orchestrator.py", "kit.zip"], [Path(path).name for path in fake.copies])
        for name in ("reporting.py", "orchestrator.py"):
            self.assertEqual(ORCHESTRATOR.hashlib.sha256(fake.copied_bytes[name]).hexdigest(), fake.envelope["helpers"][name])
        self.assertEqual(fake.envelope["github"]["controllerCommit"], fake.envelope["helpers"]["controllerCommit"])
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
        self.environment = {"GITHUB_RUN_ID": RUN, "GITHUB_RUN_ATTEMPT": "2"}
        fake = FakeSSH(state="finished")
        self.assertEqual(0, self.execute(fake, attempt=2))
        self.assertEqual(["bootstrap:query", "stop", "collect"], fake.actions)
        self.assertEqual([], fake.copies)
        self.downloader.prepare.assert_not_called()

    def test_new_run_requires_controller_commit_before_copy_or_private_preparation(self):
        for value in (None, "main", "a" * 39):
            with self.subTest(commit=value):
                self.environment.pop("GITHUB_SHA", None)
                if value is not None:
                    self.environment["GITHUB_SHA"] = value
                fake = FakeSSH(attached=False)
                with self.assertRaises(ORCHESTRATOR.Failure):
                    self.execute(fake)
                self.assertEqual([], fake.copies)
                self.assertEqual(["bootstrap:create"], fake.actions)

    def test_report_collection_failure_is_nonzero_without_changing_final_receipt(self):
        fake = FakeSSH()
        original = fake.ssh

        def unavailable(command, **kwargs):
            value = original(command, **kwargs)
            if command[1] != "-c" and command[3] == "collect":
                value["summary.json"] = {"status": "unknown", "reportCollection": {"status": "unavailable", "failureType": "FileNotFoundError"}}
            return value

        fake.ssh = unavailable
        self.assertEqual(1, self.execute(fake, attempt=2))
        self.assertEqual("passed", json.loads((self.output / "orchestration.json").read_text())["receipt"]["status"])
        self.assertEqual([], fake.copies)
        self.assertEqual(["bootstrap:query", "status", "collect"], fake.actions)

    def test_bad_release_pins_reject_before_download_prepare_or_start(self):
        config = configuration()
        config["serverDeployment"]["gitCommit"] = "d" * 40
        self.environment["DEPLOYMENT_JSON"] = json.dumps(config)
        fake = FakeSSH(attached=False)
        with self.assertRaises(ORCHESTRATOR.Failure):
            self.execute(fake)
        self.downloader.prepare.assert_not_called()
        self.assertEqual(["bootstrap:create"], fake.actions)

    def test_incompatible_new_admission_rejects_before_copy_or_credentials(self):
        config = configuration()
        config["serverDeployment"]["version"] = "4.8.0"
        self.environment.update(SERVER_RELEASE_TAG="v4.8.0", DEPLOYMENT_JSON=json.dumps(config))
        for field in ("RUNTIME_ACCESS_KEY", "WORKER_GATEWAY_KEY_ID", "WORKER_GATEWAY_SECRET"):
            self.environment.pop(field)
        fake = FakeSSH(attached=False)
        with self.assertRaisesRegex(ORCHESTRATOR.Failure, "older RPC preparation API was removed"):
            self.execute(fake)
        self.assertEqual(["bootstrap:create"], fake.actions)
        self.assertEqual([], fake.copies)
        self.downloader.prepare.assert_not_called()

    def test_attach_and_cleanup_ignore_current_consumer_pins(self):
        for action, attempt in (("run", 1), ("run", 2), ("stop", 2), ("collect", 2)):
            with self.subTest(action=action, attempt=attempt):
                self.args.action = action
                self.environment = {"GITHUB_RUN_ID": RUN}
                fake = FakeSSH(attached=True)
                with patch.object(ORCHESTRATOR, "consumer_preflight", side_effect=AssertionError("Revalidated original Run")):
                    self.assertEqual(0, self.execute(fake, attempt=attempt))
                self.assertEqual([], fake.copies)
                self.assertNotIn("prepare", fake.actions)
                self.assertNotIn("start", fake.actions)
                self.downloader.prepare.assert_not_called()


class ConsumerPreflightTest(unittest.TestCase):
    def config(self, **changes):
        value = configuration()
        value.update(rpcTaskIds={"perf-a": TASK_ID, "perf-b": "legacy-channel-b"}, **changes)
        return value

    def test_only_known_one_way_break_is_rejected(self):
        for kit, server in (("v4.4.4", "v4.7.0"), ("v4.8.0", "v4.8.0"),
                            ("v4.8.1", "v4.8.0"), ("v4.8.0", "v4.4.4")):
            with self.subTest(kit=kit, server=server):
                ORCHESTRATOR.validate_consumer_contract(self.config(), kit, server)
        for kit, server in (("v4.4.4", "v4.8.0"), ("v4.7.9", "v4.8.1")):
            with self.subTest(kit=kit, server=server):
                with self.assertRaisesRegex(ORCHESTRATOR.Failure, "older RPC preparation API was removed"):
                    ORCHESTRATOR.validate_consumer_contract(self.config(), kit, server)

    def test_new_kit_requires_exact_distinct_nonempty_task_mapping_without_leaking_values(self):
        for tasks in (None, {}, {"perf-a": TASK_ID}, {"perf-a": TASK_ID, "other": "private-id"},
                      {"perf-a": TASK_ID, "perf-b": TASK_ID}, {"perf-a": TASK_ID, "perf-b": "  "},
                      {"perf-a": TASK_ID, "perf-b": 17}):
            with self.subTest(tasks=tasks):
                config = self.config()
                if tasks is None:
                    config.pop("rpcTaskIds")
                else:
                    config["rpcTaskIds"] = tasks
                with self.assertRaises(ORCHESTRATOR.Failure) as rejected:
                    ORCHESTRATOR.validate_consumer_contract(config, "v4.8.0", "v4.7.0")
                self.assertNotIn(TASK_ID, str(rejected.exception))
                self.assertNotIn("private-id", str(rejected.exception))
        ORCHESTRATOR.validate_consumer_contract(configuration(), "v4.4.4", "v4.7.0")

    def test_preflight_action_is_local_without_credentials_or_output_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "unused"
            config = self.config()
            environment = {"RELEASE_TAG": "v4.8.0", "RELEASE_COMMIT": COMMIT,
                           "SERVER_RELEASE_TAG": TAG, "SERVER_RELEASE_COMMIT": COMMIT,
                           "DEPLOYMENT_JSON": json.dumps(config)}
            argv = ["--action", "preflight", "--repository", "owner/repository", "--workers-per-group", "5000",
                    "--repetitions", "1", "--output-dir", str(output)]
            with patch.dict(os.environ, environment, clear=True), \
                    patch.object(ORCHESTRATOR, "Transport") as transport, \
                    patch.object(ORCHESTRATOR, "controller") as controller:
                self.assertEqual(0, ORCHESTRATOR.main(argv))
                config["serverDeployment"]["version"] = "4.8.0"
                os.environ.update(RELEASE_TAG="v4.4.4", SERVER_RELEASE_TAG="v4.8.0", DEPLOYMENT_JSON=json.dumps(config))
                with self.assertRaisesRegex(ORCHESTRATOR.Failure, "older RPC preparation API was removed"):
                    ORCHESTRATOR.main(argv)
                os.environ.clear()
                os.environ["GITHUB_RUN_ATTEMPT"] = "2"
                self.assertEqual(0, ORCHESTRATOR.main(argv))
                transport.assert_not_called()
                controller.assert_not_called()
            self.assertFalse(output.exists())

    def test_ineligible_observation_remains_controller_missing_not_contract_rejection(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(ORCHESTRATOR, "observation_slot", return_value={"status": "missing"}), \
                patch.object(ORCHESTRATOR, "consumer_preflight") as preflight, \
                patch.object(ORCHESTRATOR, "Transport") as transport:
            self.assertEqual(0, ORCHESTRATOR.main([
                "--action", "preflight", "--repository", "owner/repository", "--workers-per-group", "5000",
                "--repetitions", "1", "--output-dir", "unused", "--observation-plan", "existing-plan.json"]))
            preflight.assert_not_called()
            transport.assert_not_called()

    def test_cli_rejects_bad_json_nonobject_and_kit_commit_with_fixed_safe_output(self):
        config = self.config()
        environment = {**os.environ, "RELEASE_TAG": "v4.8.0", "RELEASE_COMMIT": COMMIT,
                       "SERVER_RELEASE_TAG": TAG, "SERVER_RELEASE_COMMIT": COMMIT,
                       "DEPLOYMENT_JSON": json.dumps(config), "GITHUB_RUN_ATTEMPT": "1"}
        for field in ("SSH_HOST", "SSH_USER", "SSH_PRIVATE_KEY", "SSH_KNOWN_HOSTS", "RUNTIME_ACCESS_KEY",
                      "WORKER_GATEWAY_KEY_ID", "WORKER_GATEWAY_SECRET"):
            environment.pop(field, None)
        private_value = "private-config-fragment-" + TASK_ID
        for change, reason in (({"DEPLOYMENT_JSON": '{"' + private_value}, "Deployment configuration must be a JSON object"),
                               ({"DEPLOYMENT_JSON": json.dumps([private_value])}, "Deployment configuration must be a JSON object"),
                               ({"DEPLOYMENT_JSON": "null"}, "Deployment configuration must be a JSON object"),
                               ({"RELEASE_COMMIT": private_value}, "Invalid Performance kit Release identity")):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "unused"
                completed = subprocess.run([sys.executable, str(ROOT / ".github/scripts/run_external_performance.py"),
                                            "--action", "preflight", "--repository", "owner/repository",
                                            "--workers-per-group", "5000", "--repetitions", "1", "--output-dir", str(output)],
                                           env={**environment, **change}, capture_output=True, text=True, timeout=10)
                self.assertEqual(1, completed.returncode)
                self.assertEqual("", completed.stdout)
                self.assertEqual({"status": "failed", "failureType": "Failure", "reason": reason}, json.loads(completed.stderr))
                self.assertNotIn(private_value, completed.stderr)
                self.assertNotIn(TASK_ID, completed.stderr)
                self.assertFalse(output.exists())


class RemoteLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "runs" / RUN
        self.private = Path(self.temporary.name) / "private" / RUN
        self.root.mkdir(parents=True)
        self.private.mkdir(parents=True)
        (self.root / "orchestrator.py").write_text("# local helper fixture")
        (self.root / "reporting.py").write_bytes(REPORT_PATH.read_bytes())
        self.addCleanup(patch.stopall)
        patch.object(ORCHESTRATOR, "paths", return_value=(self.root, self.private)).start()
        patch.object(ORCHESTRATOR, "locked", side_effect=lambda _, **kwargs: nullcontext()).start()
        patch.object(ORCHESTRATOR, "user_environment", return_value={}).start()
        patch.object(ORCHESTRATOR, "current_boot_id", return_value="fixture-boot").start()
        patch.object(ORCHESTRATOR, "prepare_timer_status", return_value={"LoadState": "not-found", "ActiveState": "inactive"}).start()

    def write(self, name, value):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        ORCHESTRATOR.write_json(target, value)

    def lease(self, *, expired=False, boot="fixture-boot"):
        self.write("prepare-lease.json", {"runId": RUN, "ttlSeconds": 300, "armConfirmed": True,
                   "bootId": boot, "expiresAtMonotonic": ORCHESTRATOR.time.monotonic() + (-1 if expired else 300)})

    def envelope(self):
        return {"deployment": configuration(), "tag": TAG, "commit": COMMIT, "serverTag": TAG,
                "serverCommit": COMMIT, "workers": 5000, "repetitions": 1, "attempt": 1,
                "budget": {"perCredential": 20000, "total": 30000, "reserved": 20}, "accessKey": ACCESS_KEY,
                "gateway": {"keyId": "fixture-gateway", "secret": "private-gateway-secret"},
                "kitSha256": "c" * 64, "consumption": {"tag": TAG},
                "github": {"controllerCommit": "c" * 40},
                "helpers": {"controllerCommit": "c" * 40, **{name: ORCHESTRATOR.digest(self.root / name)
                            for name in ("reporting.py", "orchestrator.py")}}}

    def prepare_patches(self):
        return (patch.object(ORCHESTRATOR.os, "getuid", return_value=self.root.stat().st_uid, create=True),
                patch.object(ORCHESTRATOR, "preflight", return_value={"connections": 10000}),
                patch.object(ORCHESTRATOR, "extract_kit", return_value=(self.root / "kit", {"schemaVersion": 3})))

    def test_preparation_timer_is_confirmed_before_keys_and_same_run_never_renews(self):
        self.private.rmdir()
        events = []

        def systemd(command, **kwargs):
            self.assertFalse(self.private.exists())
            events.append(command)
            return b""

        identity, preflight, extract = self.prepare_patches()
        with identity, preflight, extract, patch.object(ORCHESTRATOR, "call", side_effect=systemd), \
                patch.object(ORCHESTRATOR, "prepare_timer_status", return_value={"LoadState": "loaded", "ActiveState": "active",
                             "Unit": ORCHESTRATOR.prepare_cleanup_name(RUN, "service")}), \
                patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}):
            self.assertEqual("prepared", ORCHESTRATOR.remote_prepare(RUN, self.envelope())["state"])
            original = (self.root / "prepare-lease.json").read_bytes()
            self.assertEqual("prepared", ORCHESTRATOR.remote_prepare(RUN, self.envelope())["state"])
            self.assertEqual(original, (self.root / "prepare-lease.json").read_bytes())
        self.assertEqual(1, len(events))
        command = events[0]
        for option in ("--on-active=300s", "--timer-property=AccuracySec=1s", "--property=TimeoutStartSec=120s",
                       "--unit=" + ORCHESTRATOR.prepare_cleanup_name(RUN, "service"), "expire-preparation"):
            self.assertIn(option, command)
        self.assertNotIn(ACCESS_KEY, " ".join(command))
        self.assertNotIn("private-gateway-secret", " ".join(command))
        self.assertTrue(ORCHESTRATOR.read_json(self.root / "prepare-lease.json")["armConfirmed"])
        self.assertEqual(ACCESS_KEY, (self.private / "access-key.txt").read_text().strip())
        self.assertEqual(self.envelope()["helpers"], ORCHESTRATOR.read_json(self.root / "state.json")["helpers"])

    def test_changed_helper_fails_before_timer_or_credentials(self):
        self.private.rmdir()
        envelope = self.envelope()
        (self.root / "reporting.py").write_text("# changed after copy", encoding="utf-8")
        identity, preflight, extract = self.prepare_patches()
        with identity, preflight, extract, patch.object(ORCHESTRATOR, "call") as systemd:
            with self.assertRaisesRegex(ORCHESTRATOR.Failure, "helper digest differs"):
                ORCHESTRATOR.remote_prepare(RUN, envelope)
        systemd.assert_not_called()
        self.assertFalse(self.private.exists())
        self.assertFalse((self.root / "state.json").exists())

    def test_lost_timer_ack_writes_no_keys_and_does_not_retry_arming(self):
        self.private.rmdir()
        identity, preflight, extract = self.prepare_patches()
        with identity, preflight, extract, \
                patch.object(ORCHESTRATOR, "call", side_effect=ORCHESTRATOR.Failure("lost timer acknowledgement")) as systemd:
            with self.assertRaises(ORCHESTRATOR.Failure):
                ORCHESTRATOR.remote_prepare(RUN, self.envelope())
            self.assertEqual("unknown", ORCHESTRATOR.remote_prepare(RUN, self.envelope())["state"])
        self.assertEqual(1, systemd.call_count)
        self.assertFalse(self.private.exists())
        self.assertFalse((self.root / "state.json").exists())
        self.assertFalse(ORCHESTRATOR.read_json(self.root / "prepare-lease.json")["armConfirmed"])

    def test_unarmed_timer_after_ack_is_rejected_before_private_input(self):
        self.private.rmdir()
        identity, preflight, extract = self.prepare_patches()
        with identity, preflight, extract, patch.object(ORCHESTRATOR, "call", return_value=b""):
            with self.assertRaisesRegex(ORCHESTRATOR.Failure, "timer was not confirmed"):
                ORCHESTRATOR.remote_prepare(RUN, self.envelope())
        self.assertFalse(self.private.exists())
        self.assertFalse((self.root / "deployment.json").exists())

    def test_expired_preparation_removes_keys_and_fences_late_start_without_replaying(self):
        self.lease(expired=True)
        self.write("state.json", {"repetitions": 1})
        (self.private / "access-key.txt").write_text(ACCESS_KEY)
        (self.private / "gateway.json").write_text("private-gateway-secret")
        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}), \
                patch.object(ORCHESTRATOR, "call") as systemd:
            self.assertEqual("expired", ORCHESTRATOR.remote_expire_preparation(RUN)["outcome"])
            receipt = ORCHESTRATOR.read_json(self.root / "receipt.json")
            self.assertEqual("cancelled", receipt["status"])
            self.assertEqual("prepare-expired", receipt["stopReason"])
            self.assertTrue(receipt["privateInputsRemoved"])
            self.assertEqual(receipt, ORCHESTRATOR.remote_start(RUN)["receipt"])
            self.assertEqual("already-finished", ORCHESTRATOR.remote_expire_preparation(RUN)["outcome"])
            self.assertEqual(receipt, ORCHESTRATOR.read_json(self.root / "receipt.json"))
        systemd.assert_not_called()
        self.assertFalse(list(self.private.iterdir()))
        self.assertTrue((self.root / "stop-request.json").exists())

    def test_late_start_checks_expiry_even_before_the_timer_handler_runs(self):
        self.lease(expired=True)
        self.write("state.json", {"repetitions": 1})
        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}), \
                patch.object(ORCHESTRATOR, "call") as systemd:
            receipt = ORCHESTRATOR.remote_start(RUN)["receipt"]
        self.assertEqual("prepare-expired", receipt["stopReason"])
        self.assertFalse((self.root / "start-request.json").exists())
        systemd.assert_not_called()

    def test_a_previously_queued_unit_cannot_execute_after_preparation_expiry(self):
        self.lease(expired=True)
        self.write("state.json", {"repetitions": 1})
        self.write("start-request.json", {"runId": RUN})
        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}):
            ORCHESTRATOR.remote_expire_preparation(RUN)
        receipt = (self.root / "receipt.json").read_bytes()
        with patch.object(ORCHESTRATOR.subprocess, "Popen") as process:
            self.assertEqual(1, ORCHESTRATOR.remote_execute(RUN))
        process.assert_not_called()
        self.assertEqual(receipt, (self.root / "receipt.json").read_bytes())

    def test_expiry_snapshot_and_queued_execute_spawn_share_the_control_lock(self):
        self.lease(expired=True)
        self.write("state.json", {"repetitions": 1, "kitRoot": str(self.root / "kit")})
        self.write("start-request.json", {"runId": RUN})
        (self.private / "access-key.txt").write_text(ACCESS_KEY)
        control_lock, performance_lock = threading.Lock(), threading.Lock()
        execute_waiting = threading.Event()
        snapshot_returned = False
        observations, results, failures, global_waits = {}, [], [], []

        def execute():
            try:
                results.append(ORCHESTRATOR.remote_execute(RUN))
            except BaseException as error:
                failures.append(type(error).__name__)

        worker = threading.Thread(target=execute, name="queued-execute-race")

        @contextmanager
        def serialized(path, *, wait_seconds=0):
            if path.name == "performance.lock":
                global_waits.append(wait_seconds)
                acquired = performance_lock.acquire(blocking=False)
                target = performance_lock
            else:
                if threading.current_thread() is worker:
                    execute_waiting.set()
                target = control_lock
                acquired = target.acquire(timeout=3) if wait_seconds else target.acquire(blocking=False)
            if not acquired:
                raise ORCHESTRATOR.Failure("Fixture lock unavailable")
            try:
                yield
            finally:
                target.release()

        def snapshot(_):
            nonlocal snapshot_returned
            snapshot_returned = True
            return {"LoadState": "not-found"}

        original_write = ORCHESTRATOR.write_json
        child = Mock(returncode=0)
        child.poll.return_value = 0
        with patch.object(ORCHESTRATOR, "locked", side_effect=serialized), \
                patch.object(ORCHESTRATOR, "unit_status", side_effect=snapshot), \
                patch.object(ORCHESTRATOR.signal, "signal", return_value=None), \
                patch.object(ORCHESTRATOR.subprocess, "Popen", return_value=child) as process:
            def write(path, value, **kwargs):
                if path.name == "prepare-expired.json":
                    # Activate execute after the not-found snapshot returned, before expiry writes its fence.
                    self.assertTrue(snapshot_returned)
                    self.assertFalse((self.root / "stop-request.json").exists())
                    worker.start()
                    self.assertTrue(execute_waiting.wait(3))
                    observations["spawnedBeforeFence"] = process.called
                return original_write(path, value, **kwargs)

            try:
                with patch.object(ORCHESTRATOR, "write_json", side_effect=write):
                    result = ORCHESTRATOR.remote_expire_preparation(RUN)
            finally:
                worker.join(3)
            self.assertFalse(worker.is_alive())
            self.assertEqual("expired", result["outcome"])
            self.assertFalse(observations["spawnedBeforeFence"])
            process.assert_not_called()
        self.assertEqual([], failures)
        self.assertEqual([1], results)
        self.assertEqual([0], global_waits)
        self.assertFalse((self.private / "access-key.txt").exists())
        self.assertEqual("prepare-expired", ORCHESTRATOR.read_json(self.root / "receipt.json")["stopReason"])

    def test_execute_holds_control_for_spawn_but_releases_it_before_waiting_for_runner(self):
        self.write("state.json", {"repetitions": 1, "kitRoot": str(self.root / "kit")})
        self.write("start-request.json", {"runId": RUN})
        depth, global_waits = 0, []

        @contextmanager
        def serialized(path, *, wait_seconds=0):
            nonlocal depth
            if path.name == "control.lock":
                depth += 1
            else:
                global_waits.append(wait_seconds)
            try:
                yield
            finally:
                if path.name == "control.lock":
                    depth -= 1

        child = Mock(returncode=0)

        def poll():
            self.assertEqual(0, depth)
            return 0

        child.poll.side_effect = poll

        def spawn(*args, **kwargs):
            self.assertEqual(1, depth)
            self.write("result/evidence/lane-summary.json", {"status": "passed"})
            return child

        with patch.object(ORCHESTRATOR, "locked", side_effect=serialized), \
                patch.object(ORCHESTRATOR.signal, "signal", return_value=None), \
                patch.object(ORCHESTRATOR.subprocess, "Popen", side_effect=spawn):
            self.assertEqual(0, ORCHESTRATOR.remote_execute(RUN))
        self.assertEqual([0], global_waits)
        self.assertEqual(0, depth)

    def test_private_input_removal_failure_never_claims_successful_expiry_cleanup(self):
        self.lease(expired=True)
        key = self.private / "access-key.txt"
        key.write_text(ACCESS_KEY)
        unlink = Path.unlink

        def remove(path, *args, **kwargs):
            if path == key:
                raise PermissionError("private removal rejected")
            return unlink(path, *args, **kwargs)

        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "not-found"}), \
                patch.object(Path, "unlink", remove):
            result = ORCHESTRATOR.remote_expire_preparation(RUN)
        self.assertEqual("unconfirmed", result["outcome"])
        self.assertFalse(result["privateInputsRemoved"])
        self.assertEqual("PrivateInputRemoval", result["failureType"])
        self.assertTrue(key.is_file())

    def test_expiry_and_timer_stop_failure_preserve_an_existing_successful_receipt(self):
        self.lease(expired=True)
        self.write("receipt.json", {"status": "passed", "privateInputsRemoved": True})
        original = (self.root / "receipt.json").read_bytes()
        with patch.object(ORCHESTRATOR, "prepare_timer_status", return_value={"LoadState": "loaded", "ActiveState": "active"}), \
                patch.object(ORCHESTRATOR, "unit_status", side_effect=AssertionError("A final receipt must be authoritative")), \
                patch.object(ORCHESTRATOR, "call", side_effect=ORCHESTRATOR.Failure("timer stop not confirmed")):
            self.assertEqual("already-finished", ORCHESTRATOR.remote_expire_preparation(RUN)["outcome"])
        self.assertEqual(original, (self.root / "receipt.json").read_bytes())
        self.assertFalse(ORCHESTRATOR.read_json(self.root / "prepare-cleanup.json")["timerStopped"])

    def test_expiry_leaves_the_actual_running_unit_in_charge_after_lost_start_ack(self):
        self.lease(expired=True)
        self.write("state.json", {"repetitions": 1})
        self.write("start-request.json", {"runId": RUN})
        (self.private / "access-key.txt").write_text(ACCESS_KEY)
        with patch.object(ORCHESTRATOR, "unit_status", return_value={"LoadState": "loaded", "ActiveState": "activating", "SubState": "start"}), \
                patch.object(ORCHESTRATOR, "call") as systemd:
            self.assertEqual("handed-off", ORCHESTRATOR.remote_expire_preparation(RUN)["outcome"])
        self.assertTrue((self.private / "access-key.txt").is_file())
        self.assertFalse((self.root / "stop-request.json").exists())
        self.assertFalse((self.root / "receipt.json").exists())
        systemd.assert_not_called()

    def test_cross_boot_lease_stays_unknown_without_restart_or_false_expiration(self):
        self.lease(boot="previous-boot")
        self.write("state.json", {"repetitions": 1})
        with patch.object(ORCHESTRATOR, "call") as systemd:
            self.assertEqual("unknown", ORCHESTRATOR.remote_status(RUN)["state"])
            with self.assertRaisesRegex(ORCHESTRATOR.Failure, "another boot"):
                ORCHESTRATOR.remote_start(RUN)
            self.assertEqual("unconfirmed", ORCHESTRATOR.remote_expire_preparation(RUN)["outcome"])
        self.assertFalse((self.root / "start-request.json").exists())
        self.assertFalse((self.root / "prepare-expired.json").exists())
        self.assertFalse((self.root / "receipt.json").exists())
        systemd.assert_not_called()
        public = ORCHESTRATOR.remote_collect(RUN)
        self.assertNotIn("previous-boot", json.dumps(public))
        self.assertNotIn("expiresAtMonotonic", json.dumps(public))

    def test_guard_lock_failure_records_unconfirmed_without_claiming_key_removal(self):
        self.lease(expired=True)
        (self.private / "access-key.txt").write_text(ACCESS_KEY)
        with patch.object(ORCHESTRATOR, "locked", side_effect=ORCHESTRATOR.Failure("control lock unavailable")):
            self.assertEqual("unconfirmed", ORCHESTRATOR.remote_expire_preparation(RUN)["outcome"])
        self.assertTrue((self.private / "access-key.txt").exists())
        self.assertFalse((self.root / "receipt.json").exists())
        failure = ORCHESTRATOR.read_json(self.root / "prepare-cleanup-failure.json")
        self.assertEqual("unconfirmed", failure["outcome"])
        self.assertNotIn(ACCESS_KEY, json.dumps(failure))

    def test_cancel_stops_only_current_campaign_and_timer_outside_the_control_lock(self):
        self.lease()
        self.write("state.json", {"repetitions": 1})
        self.write("start-request.json", {"runId": RUN})
        state = {"LoadState": "loaded", "ActiveState": "active"}
        depth = 0

        @contextmanager
        def control(path, **kwargs):
            nonlocal depth
            depth += 1
            try:
                yield
            finally:
                depth -= 1

        def stop(command, **kwargs):
            self.assertEqual(0, depth)
            if command[-1] == ORCHESTRATOR.unit_name(RUN):
                state["ActiveState"] = "inactive"
            return b""

        with patch.object(ORCHESTRATOR, "locked", side_effect=control), \
                patch.object(ORCHESTRATOR, "unit_status", side_effect=lambda _: dict(state)), \
                patch.object(ORCHESTRATOR, "prepare_timer_status", return_value={"LoadState": "loaded", "ActiveState": "active"}), \
                patch.object(ORCHESTRATOR, "call", side_effect=stop) as systemd:
            receipt = ORCHESTRATOR.remote_stop(RUN)["receipt"]
            self.assertEqual("cancelled", receipt["status"])
            self.assertEqual(receipt, ORCHESTRATOR.remote_stop(RUN)["receipt"])
        self.assertEqual([ORCHESTRATOR.unit_name(RUN), ORCHESTRATOR.prepare_cleanup_name(RUN)],
                         [call.args[0][-1] for call in systemd.call_args_list])
        self.assertTrue(ORCHESTRATOR.read_json(self.root / "prepare-cleanup.json")["timerStopped"])

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
        envelope = {**self.envelope(), "deployment": configuration(), "tag": TAG, "commit": COMMIT,
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

    def test_missing_or_changed_reporter_cannot_block_stop_or_rewrite_cleanup(self):
        for damage in ("missing", "digest", "syntax"):
            with self.subTest(damage=damage):
                reporter = self.root / "reporting.py"
                reporter.write_bytes(REPORT_PATH.read_bytes())
                state = {"helpers": {"reporting.py": ORCHESTRATOR.digest(reporter)}}
                if damage == "missing":
                    reporter.unlink()
                else:
                    reporter.write_text("broken python!", encoding="utf-8")
                    if damage == "syntax":
                        state["helpers"]["reporting.py"] = ORCHESTRATOR.digest(reporter)
                self.write("state.json", state)
                self.write("receipt.json", {"status": "passed", "privateInputsRemoved": True, "finalizationFailures": []})
                with patch.object(ORCHESTRATOR, "unit_status", return_value={}), patch.object(ORCHESTRATOR, "call"):
                    receipt = ORCHESTRATOR.remote_stop(RUN)["receipt"]
                    public = ORCHESTRATOR.remote_collect(RUN)
                self.assertEqual("passed", receipt["status"])
                self.assertEqual(receipt, public["orchestration.json"]["receipt"])
                self.assertEqual("unavailable", public["summary.json"]["reportCollection"]["status"])
                self.assertEqual("unknown", public["summary.json"]["status"])
                self.assertNotIn("submissionEvidence", public["summary.json"])

    def test_missing_reporter_does_not_block_initial_finalize_or_private_cleanup(self):
        (self.root / "reporting.py").unlink()
        (self.private / "access-key.txt").write_text(ACCESS_KEY)
        self.write("execution.json", {"exitCode": 0})
        self.write("result/evidence/lane-summary.json", {"status": "passed", "cases": []})
        receipt = ORCHESTRATOR.remote_finalize(RUN)
        self.assertEqual("passed", receipt["status"])
        self.assertTrue(receipt["privateInputsRemoved"])
        self.assertFalse((self.private / "access-key.txt").exists())
        public = ORCHESTRATOR.remote_collect(RUN)
        self.assertEqual(receipt, public["orchestration.json"]["receipt"])
        self.assertEqual("unavailable", public["summary.json"]["reportCollection"]["status"])

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
                          "httpVersions": {"HTTP_2": 96},
                          "invalidReasons": ["worker-observation-unavailable"], "generatorLimited": True,
                          "acceptedResultsAfterDrain": {"succeeded": 3476, "failed": 2, "not_observed": 0},
                          "metrics": {"unknownSubmissions": 11764, "clientTimeouts": 96, "http429": 11668,
                                      "unresolvedAcceptedIds": [TASK_ID], "secret": "gateway-secret"},
                          "samples": [{"id": WORKER_ID}], "taskIds": [TASK_ID]}]}
        original = deepcopy(raw)
        public = ORCHESTRATOR.scrub(REPORTING.safe_result(raw))
        self.assertEqual(original, raw)
        row = public["cases"][0]
        self.assertEqual("invalid", public["status"])
        self.assertEqual("invalid", row["status"])
        self.assertEqual(10000, row["workerSamples"])
        self.assertEqual({"HTTP_2": 96}, row["httpVersions"])
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
            evidence = REPORTING.sample_counts(result)
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

    def test_sample_timeout_projection_keeps_safe_phase_without_diagnostic_text(self):
        rows = [{"sentOffsetMillis": 0, "httpStatus": 0, "outcome": "unknown", "detail": "client-timeout",
                 "timeoutPhase": phase, "exceptionType": exception, "messageId": WORKER_ID,
                 "exceptionMessage": ACCESS_KEY}
                for phase, exception in (("connect-tls", "HttpConnectTimeoutException"),
                                         ("before-headers", "TimeoutException"),
                                         ("response-body", "TimeoutException"),
                                         (WORKER_ID, ACCESS_KEY))]
        rows.append({"sentOffsetMillis": 1, "httpStatus": 200, "outcome": "succeeded",
                     "httpVersion": "HTTP_2", "messageId": WORKER_ID, "observedResult": "succeeded"})
        with tempfile.TemporaryDirectory() as temporary:
            result = Path(temporary)
            target = result / "evidence/harness/r1/direct-2000/samples.jsonl"
            target.parent.mkdir(parents=True)
            target.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            evidence = REPORTING.sample_counts(result)
        totals = evidence["totals"]
        self.assertEqual(4, totals["clientTimeouts"])
        self.assertEqual(4, totals["sentHttp0Unknown"])
        self.assertEqual(1, totals["httpVersion:HTTP_2"])
        for phase in ("connect-tls", "before-headers", "response-body", "unclassified"):
            self.assertEqual(1, totals["timeoutPhase:" + phase])
        self.assertEqual(2, totals["exceptionType:TimeoutException"])
        self.assertEqual(1, totals["exceptionType:other"])
        for private in (WORKER_ID, ACCESS_KEY, "exceptionMessage"):
            self.assertNotIn(private, json.dumps(evidence))


if __name__ == "__main__":
    unittest.main()
