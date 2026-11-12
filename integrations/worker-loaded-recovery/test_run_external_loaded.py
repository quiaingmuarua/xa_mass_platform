import contextlib
import argparse
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("external_loaded_runner", Path(__file__).with_name("run_external_loaded.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


def arguments(root: Path):
    return ["--runtime-url", "https://api.example.test", "--worker-runtime-url", "https://worker.example.test",
            "--project", "test-project", "--group", "test-group", "--endpoint-manager", "runtime-websocket",
            "--access-key-file", str(root / "access.txt"), "--gateway-credential-file", str(root / "gateway.json"),
            "--output-dir", str(root / "output")]


def inputs(root: Path):
    (root / "access.txt").write_text("xak_test-access-key\n", encoding="utf-8")
    (root / "gateway.json").write_text(json.dumps({"keyId": "test-host", "secret": "private-credential-value"}), encoding="utf-8")


class ExternalOptionsTest(unittest.TestCase):
    def test_fixed_public_profile_and_reduced_fixture_are_distinct(self):
        value = RUNNER.options(arguments(Path("/test")))
        self.assertEqual(15_000, value.prepared_workers)
        self.assertEqual(10_000, value.retained_workers)
        self.assertEqual(5_000, value.workload_items_per_task)
        self.assertFalse(value.diagnostic)
        change = ["--prepared-workers", "6", "--retained-workers", "4",
                  "--minimum-initial-connected", "6", "--minimum-retained-connected", "4"]
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            RUNNER.options(arguments(Path("/test")) + change)
        self.assertTrue(RUNNER.options(arguments(Path("/test")) + change + ["--diagnostic"]).diagnostic)

    def test_remote_origins_cannot_redirect_credentials_or_use_cleartext(self):
        for origin in ("http://example.test", "https://user:secret@example.test", "https://example.test/path",
                       "https://example.test?token=x", "https://example.test#x", "https://example.test:0"):
            with self.subTest(origin=origin), self.assertRaises(argparse.ArgumentTypeError):
                RUNNER._origin(origin)
        self.assertEqual("https://example.test:443", RUNNER._origin("https://example.test:443/"))

    def test_explicit_truststore_is_diagnostic_only_and_never_disables_tls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = root / "fixture.p12"
            store.write_bytes(b"test-store")
            extra = ["--java-truststore", str(store), "--java-truststore-password", "public-fixture-password"]
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                RUNNER.options(arguments(root) + extra)
            value = RUNNER.options(arguments(root) + extra + ["--diagnostic"])
            host = RUNNER._host_command("java", root / "kit", root / "host.json", RUNNER._tls_options(value))
            harness = RUNNER._harness_command("java", root / "kit", value, "http://127.0.0.1:1234", root, root / "evidence")
            for command in (host, harness):
                self.assertIn("-Djavax.net.ssl.trustStore=" + str(store.resolve()), command)
                self.assertFalse(any("insecure" in part.lower() or "trustall" in part.lower() for part in command))

    def test_child_environment_does_not_inherit_business_or_jvm_overrides(self):
        with patch.dict(os.environ, {"PATH": "/java", "JAVA_TOOL_OPTIONS": "untrusted", "XA_MASS_REDIS_URL": "private",
                                     "RUNTIME_ACCESS_KEY": "secret", "HTTP_PROXY": "http://proxy"}, clear=True):
            self.assertEqual({"PATH": "/java"}, RUNNER._environment())

    def test_host_waits_for_java_owned_paced_starts_and_uses_only_release_libraries(self):
        root = Path("/temporary")
        value = RUNNER.options(arguments(root))
        config = RUNNER._host_configuration(value, root)
        self.assertEqual({"initialWorkers": [], "scheduledStops": []}, config["startupPlan"])
        self.assertEqual(root / "data/scenario-workers", Path(config["sandboxRoot"]))
        self.assertEqual({"count": 15000, "events": ["extension.worker.string.md5"], "propertiesTemplate": {},
                          "newEnvironment": False, "requestTimeoutMillis": 60000,
                          "reconnectPolicy": {"maxUnstableAttempts": 600, "reconnectIntervalMillis": 500,
                                              "stableConnectionDurationMillis": 10000}},
                         config["workerGroups"]["test-group"])
        self.assertNotIn("secret", json.dumps(config))
        command = RUNNER._harness_command("java", root / "kit", value, "http://127.0.0.1:1234", root, root / "evidence")
        self.assertIn(str(root / "kit/acceptance/loaded/lib/*"), command)
        self.assertIn("--prepared-workers=15000", command)
        self.assertIn("--project-id=test-project", command)
        self.assertFalse(any("gradle" in x.lower() or "redis" in x.lower() or "ssh" in x.lower() for x in command))

    def test_private_input_failures_are_bounded_strict_and_do_not_echo_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs(root)
            self.assertEqual("test-host", RUNNER._private_inputs(root / "access.txt", root / "gateway.json")[1]["keyId"])
            for content in ('{"keyId":"a","keyId":"b","secret":"private-credential-value"}',
                            '{"keyId":"a","secret":"private-credential-value","extra":true}',
                            '{"keyId":"a","secret":null}'):
                (root / "gateway.json").write_text(content)
                with self.assertRaises(ValueError) as error:
                    RUNNER._private_inputs(root / "access.txt", root / "gateway.json")
                self.assertNotIn("private-credential-value", str(error.exception))

    def test_existing_or_wrong_kit_is_not_a_build_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text(json.dumps({"schemaVersion": 1}))
            with self.assertRaises(ValueError):
                RUNNER._kit(root)

    def test_platform_record_keeps_cpu_memory_toolchain_and_optional_cgroup_values(self):
        with patch.object(Path, "read_text", return_value="MemTotal:       8000000 kB\n"), \
             patch.object(RUNNER, "_optional_cgroup", side_effect=[{"status": "observed", "value": "400000 100000"},
                                                                 {"status": "unavailable", "value": None}]):
            result = RUNNER._platform_metadata("/opt/java/bin/java", 'openjdk version "21.0.3"')
        self.assertEqual(8000000 * 1024, result["memTotalBytes"])
        self.assertIsInstance(result["logicalCpuCount"], int)
        self.assertTrue(result["os"])
        self.assertTrue(result["kernel"])
        self.assertTrue(result["architecture"])
        self.assertTrue(result["pythonVersion"])
        self.assertEqual('openjdk version "21.0.3"', result["javaVersion"])
        self.assertEqual("400000 100000", result["cgroupCpuMax"]["value"])
        self.assertEqual("unavailable", result["cgroupMemoryMax"]["status"])

    def test_optional_cgroup_read_failure_is_not_a_new_capacity_gate(self):
        with patch.object(Path, "read_text", side_effect=FileNotFoundError):
            self.assertEqual({"value": None, "status": "unavailable"}, RUNNER._optional_cgroup("cpu.max"))


class ExternalLifecycleTest(unittest.TestCase):
    def sampler(self):
        sampler = Mock()
        sampler.sample_now.return_value = {}
        sampler.summary.return_value = {owner: {"periodicSampleCount": 2, "maximumNativeThreads": 10,
                                               "maximumOpenFileDescriptors": 100, "averageCpuCores": 0.2}
                                        for owner in RUNNER.MAXIMUM_FDS}
        sampler.diagnostics.return_value = {"status": "complete", "failure": None}
        return sampler

    def execute(self, root, *, launch_failure=False, sampling_failure=False, diagnostic=False):
        inputs(root)
        value = RUNNER.options(arguments(root) + (["--diagnostic"] if diagnostic else []))
        host, harness = Mock(pid=123), Mock(pid=456)
        host.poll.return_value = None
        harness.poll.return_value = 0
        sampler = self.sampler()
        if sampling_failure:
            sampler.diagnostics.return_value = {"status": "incomplete", "failure": {"type": "OSError"}}
        stopped = []
        def stop(process, force):
            stopped.append(process.pid)
            process.poll.return_value = 0
        launches = [host, RuntimeError("private child failure") if launch_failure else harness]
        with patch.object(RUNNER, "_preflight", return_value=({"version": "5.0.0", "gitCommit": "a" * 40}, "java", {})), \
             patch.object(RUNNER, "_ProcessSampler", return_value=sampler), \
             patch.object(RUNNER, "start_process", side_effect=launches), \
             patch.object(RUNNER, "stop_process", side_effect=stop), \
             patch.object(RUNNER, "_wait_host", return_value="http://127.0.0.1:1234"), \
             patch.object(RUNNER, "_wait_harness", return_value=0), \
             patch.object(RUNNER, "_public_summary", return_value={"status": "passed"}), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            code = RUNNER.run(value, root / "kit")
        result = json.loads((root / "output/summary.json").read_text())
        self.assertTrue((root / "access.txt").exists(), "Caller-owned key file must remain")
        self.assertFalse((root / "output/private/access-key.txt").exists())
        self.assertFalse((root / "output/private/worker-gateway.json").exists())
        self.assertNotIn("private-credential-value", output.getvalue() + json.dumps(result))
        return code, result, stopped

    def test_success_requires_resource_evidence_and_cleans_both_children(self):
        with tempfile.TemporaryDirectory() as directory:
            code, result, stopped = self.execute(Path(directory))
        self.assertEqual(0, code)
        self.assertEqual("passed", result["status"])
        self.assertEqual([456, 123], stopped)
        self.assertTrue(result["cleanup"]["ownedProcessesStopped"])
        self.assertEqual({"requestTimeoutMillis": 60000,
                          "reconnectPolicy": {"maxUnstableAttempts": 600, "reconnectIntervalMillis": 500,
                                              "stableConnectionDurationMillis": 10000}}, result["workerConnection"])

    def test_second_launch_failure_stops_the_already_started_host(self):
        with tempfile.TemporaryDirectory() as directory:
            code, result, stopped = self.execute(Path(directory), launch_failure=True)
        self.assertEqual(1, code)
        self.assertEqual("failed", result["status"])
        self.assertEqual([123], stopped)
        self.assertEqual("RuntimeError", result["reason"])
        self.assertEqual("unconfirmed", result["remoteTaskCleanup"])

    def test_late_sampler_failure_cannot_be_published_as_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            code, result, _ = self.execute(Path(directory), sampling_failure=True)
        self.assertEqual(1, code)
        self.assertEqual("resource-sampling-failed", result["failureKind"])

    def test_primary_start_failure_is_not_relabelled_by_missing_resource_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            code, result, stopped = self.execute(Path(directory), launch_failure=True, sampling_failure=True)
        self.assertEqual(1, code)
        self.assertEqual("consumer-failed", result["failureKind"])
        self.assertEqual("RuntimeError", result["reason"])
        self.assertEqual("incomplete", result["resourceSampling"]["status"])
        self.assertEqual([123], stopped)

    def test_reduced_fixture_never_becomes_capacity_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            code, result, _ = self.execute(Path(directory), diagnostic=True)
        self.assertEqual(0, code)
        self.assertEqual("diagnostic-passed", result["status"])
        self.assertEqual("diagnostic-only", result["claim"])

    def test_harness_exit_cannot_contradict_its_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "summary.json"
            path.write_text('{"status":"passed"}')
            with self.assertRaises(RUNNER.RunFailure):
                RUNNER._public_summary(path, 1, False)

    def test_resource_guard_and_wait_propagate_sampler_failure(self):
        sampler = Mock()
        sampler.sample_now.return_value = {"worker-host": {"nativeThreads": 512, "openFileDescriptors": 10}}
        with self.assertRaises(RUNNER.RunFailure):
            RUNNER._resources(sampler, checkpoint="guard")
        sampler.check.side_effect = RUNNER._ResourceSamplingError("failed")
        host, harness = Mock(), Mock()
        with self.assertRaises(RUNNER._ResourceSamplingError):
            RUNNER._wait_harness(host, harness, sampler, seconds=1)
        harness.wait.assert_not_called()


class ExternalEvidenceAuditTest(unittest.TestCase):
    def fixture(self, root):
        value = RUNNER.options(arguments(root) + ["--diagnostic", "--prepared-workers", "6", "--retained-workers", "4",
                               "--minimum-initial-connected", "6", "--minimum-retained-connected", "4",
                               "--workload-items-per-task", "3", "--stable-hold-millis", "6000"])
        baseline = {"workerGroupId": value.group, "workerIdsByLabWorkerKey": {f"workers-000.jsonl:{i+1}": f"worker-{i}" for i in range(6)}}
        (root / "private").mkdir()
        (root / "private/worker-identities.json").write_text(json.dumps(baseline))
        cases = []
        for label, active in (("initial", 6), ("retained", 4)):
            gate = {"activeConnected": active, "stoppedConnected": 0, "missingSchedulingIdentities": 0,
                    "qualifyingScans": 3, "stableMillis": 6000, "minimumConnected": active, "requiredConnected": active}
            cases.append({"case": label, "status": "passed", "preparedWorkers": 6, "activeWorkers": active,
                          "taskCount": 10, "taskIds": [f"{label}-{i}" for i in range(10)], "expectedItems": 30,
                          "succeededItems": 30, "verifiedItems": 30, "terminalTaskCount": 10, "elapsedMillis": 1000,
                          "preloadMillis": 500, "verificationMillis": 500, "exportMillis": 200,
                          "tasks": [{"taskId": f"{label}-{i}", "expectedItems": 3, "verifiedItems": 3,
                                     "totalItems": 3, "succeededItems": 3, "failedItems": 0,
                                     "terminalObservedMillis": 1000, "exportMillis": 20} for i in range(10)],
                          "observedItemsPerSecond": 30.0, "minimumConnected": active,
                          "preWorkStability": dict(gate), "postWorkStability": dict(gate)})
        digest = hashlib.sha256("".join(worker + "\n" for worker in sorted(baseline["workerIdsByLabWorkerKey"].values())).encode()).hexdigest()
        evidence = {"schemaVersion": 1, "status": "passed", "outcome": "passed", "phase": "complete",
                    "preparedWorkers": 6, "retainedWorkers": 4, "workloadItemsPerTask": 3, "expectedItems": 60,
                    "identityCount": 6, "identityDigest": digest, "cases": cases,
                    "knownTaskIds": sum([case["taskIds"] for case in cases], []), "unknownWrites": [], "cleanup": []}
        return value, evidence

    def test_passed_requires_the_complete_configured_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            options, summary = self.fixture(root)
            path = root / "summary.json"
            path.write_text(json.dumps(summary))
            self.assertEqual(summary, RUNNER._public_summary(path, 0, options))
            self.assertEqual("not-required-verified-terminal", RUNNER._remote_cleanup(summary))
            path.write_text('{"status":"passed"}')
            with self.assertRaises(RUNNER.RunFailure): RUNNER._public_summary(path, 0, options)

    def test_missing_or_weakened_counts_windows_and_exports_are_rejected(self):
        mutations = [
            lambda s: s.update(identityCount=5), lambda s: s.update(identityDigest="0" * 64),
            lambda s: s.update(expectedItems=30), lambda s: s.update(phase="case-retained"),
            lambda s: s.update(unknownWrites=[{"status": "unconfirmed"}]), lambda s: s.update(cases=s["cases"][:1]),
            lambda s: s["cases"][0].update(verifiedItems=29), lambda s: s["cases"][0].update(terminalTaskCount=9),
            lambda s: s["cases"][0].update(minimumConnected=5), lambda s: s["cases"][0].update(observedItemsPerSecond=300),
            lambda s: s["cases"][0]["tasks"][0].update(verifiedItems=2),
            lambda s: s["cases"][0]["tasks"][0].update(failedItems=1),
            lambda s: s["cases"][0].update(exportMillis=501),
            lambda s: s["cases"][1].update(activeWorkers=6),
            lambda s: s["cases"][1]["postWorkStability"].update(stoppedConnected=1),
            lambda s: s["cases"][1]["preWorkStability"].update(missingSchedulingIdentities=1),
            lambda s: s["cases"][0]["preWorkStability"].update(qualifyingScans=2),
            lambda s: s["cases"][0]["postWorkStability"].update(stableMillis=5999),
            lambda s: s["cases"][1].update(taskIds=s["cases"][0]["taskIds"]),
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            options, summary = self.fixture(root)
            path = root / "summary.json"
            for mutate in mutations:
                changed = copy.deepcopy(summary)
                mutate(changed)
                path.write_text(json.dumps(changed))
                with self.subTest(changed=changed), self.assertRaises(RUNNER.RunFailure):
                    RUNNER._public_summary(path, 0, options)

    def test_private_identity_evidence_must_match_and_remains_private(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            options, summary = self.fixture(root)
            path = root / "summary.json"
            path.write_text(json.dumps(summary))
            baseline_path = root / "private/worker-identities.json"
            baseline = json.loads(baseline_path.read_text())
            baseline["workerIdsByLabWorkerKey"]["workers-000.jsonl:1"] = "worker-5"
            baseline_path.write_text(json.dumps(baseline))
            with self.assertRaises(RUNNER.RunFailure): RUNNER._public_summary(path, 0, options)

    def test_remote_cleanup_does_not_follow_local_process_cleanup(self):
        self.assertEqual("unconfirmed", RUNNER._remote_cleanup({"status": "failed"}))
        value = {"status": "unconfirmed", "knownTaskIds": ["task-a"], "unknownWrites": [],
                 "cleanup": [{"taskId": "task-a", "status": "close-confirmed"}]}
        self.assertEqual("confirmed-known-tasks", RUNNER._remote_cleanup(value))
        value["unknownWrites"] = [{"operation": "create-task"}]
        self.assertEqual("unconfirmed", RUNNER._remote_cleanup(value))
        value["unknownWrites"] = []
        value["cleanup"][0]["status"] = "unconfirmed"
        self.assertEqual("unconfirmed", RUNNER._remote_cleanup(value))


if __name__ == "__main__":
    unittest.main()
