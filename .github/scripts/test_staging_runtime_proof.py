from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from distribution.server import staging_runtime_proof as proof


class PackagedIngressOracleTest(unittest.TestCase):
    def test_performance_rpc_tasks_receive_the_host_any_supply_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = root / "runtime"
            jar = runtime / "lib/server.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"fixture")
            with patch.object(proof, "_users_file", return_value=root / "users.json"):
                command = proof._runtime_command(runtime, root, {"serverJar": "lib/server.jar"}, "java",
                                                 18082, 18083, "wss://localhost/worker", "redis://localhost/15",
                                                 "test_performance_rpc_supply")
            flags = dict(argument[2:].split("=", 1) for argument in command if argument.startswith("--"))
            prefix = "xa.mass.task-rpc.refill-by-worker-group["
            defaults = {key: json.loads(value) for key, value in flags.items() if key.startswith(prefix)}
            self.assertEqual(defaults, {
                f"{prefix}{group}][0]": {"poolName": "any", "target": {}, "count": 1000}
                for group in proof.PERFORMANCE_GROUPS})

    def performance(self, change=None, *, returncode=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = root / "kit/acceptance/performance/run_worker_call_performance.py"
            entry.parent.mkdir(parents=True)
            entry.write_text("# the shipped existing runner", encoding="utf-8")
            output = root / "performance-deployment/evidence"
            output.mkdir(parents=True)
            summary = {"status": "passed", "executionEnvironment": "existing-server", "referenceHost": False,
                       "calibration": None, "attribution": None,
                       "notCollected": ["server-resources", "redis-resources", "server-jfr"],
                       "cases": [{"case": case, "status": "passed", "repetition": 1,
                                  "measurementStartedEpochMillis": 1000, "measurementEndedEpochMillis": 31000}
                                 for case in ("task-any-500", "task-targeted-500", "direct-500")],
                       "cleanup": {"ownedProcessesStopped": True, "privateCredentialCopiesRemoved": True,
                                   "workerConnections": {"status": "confirmed"}, "errors": []}}
            if change:
                change(summary)
            (output / "lane-summary.json").write_text(json.dumps(summary), encoding="utf-8")
            gate = Mock(admin_url="https://localhost:8443", worker_url="https://127.0.0.1:8443", ca_file=root / "ca.pem")
            process, child = Mock(), Mock()
            process.poll.return_value = None
            child.wait.return_value = returncode
            with patch.object(proof, "start_process", return_value=child) as start, patch.object(proof, "stop_process"):
                result = proof._performance(gate, [process], simulator_home=root / "kit", proof_root=root,
                                            truststore=root / "ca.p12", environment={"PYTHONPATH": "checkout"},
                                            runtime_manifest={"version": "4.4.0", "gitCommit": "a" * 40})
            command = start.call_args.args[0]
            self.assertEqual(command[1], str(entry))
            self.assertIn("--deployment-config", command)
            self.assertIn("--skip-build", command)
            self.assertIn("--java-truststore", command)
            self.assertNotIn(proof.EXTERNAL_KEY, " ".join(command))
            self.assertNotIn(proof.PERFORMANCE_WORKER[1], " ".join(command))
            self.assertNotIn("PYTHONPATH", start.call_args.args[2])
            self.assertEqual(start.call_args.args[2]["SSL_CERT_FILE"], str(root / "ca.pem"))
            configuration = json.loads((root / "performance-deployment.json").read_text(encoding="utf-8"))
            self.assertEqual(configuration["groups"], list(proof.PERFORMANCE_GROUPS))
            self.assertEqual((configuration["workersPerGroup"], configuration["assignmentBatchLimit"]), (100, 100))
            self.assertEqual(Path(configuration["accessKeyFile"]), root / "external-access-key.txt")
            self.assertFalse((root / "performance-worker-credential.json").exists())
            gate.write_passwords.assert_called_once_with(gate.worker_password_file, (proof.OTHER_WORKER, proof.PERFORMANCE_WORKER))
            return result

    def test_performance_consumes_the_existing_runner_from_the_same_kit(self):
        result = self.performance()
        self.assertEqual(result["claim"], "packaged-existing-performance-flow")
        self.assertEqual({case["case"] for case in result["cases"]},
                         {"task-any-500", "task-targeted-500", "direct-500"})
        self.assertTrue(result["consumerCleanupConfirmed"])
        self.assertFalse(result["referenceHost"])

    def test_packaged_performance_requires_all_paths_and_confirmed_consumer_cleanup(self):
        changes = [lambda s: s.update(executionEnvironment="local"),
                   lambda s: s.update(referenceHost=True), lambda s: s.update(cases=s["cases"][:1]),
                   lambda s: s["cases"][0].update(status="failed"),
                   lambda s: s["cases"][0].update(measurementEndedEpochMillis=None),
                   lambda s: s["cleanup"].update(privateCredentialCopiesRemoved=False),
                   lambda s: s["cleanup"]["workerConnections"].update(status="unconfirmed"),
                   lambda s: s.update(calibration={"server": "claimed"}),
                   lambda s: s.update(notCollected=[])]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                self.performance(change)
        with self.assertRaises(RuntimeError):
            self.performance(returncode=1)

    def test_packaged_performance_does_not_add_a_throughput_pass_threshold(self):
        result = self.performance(lambda s: s["cases"][-1].update(status="invalid"))
        self.assertEqual(result["cases"][-1]["status"], "invalid")

    def test_loaded_fixture_consumes_zip_and_checks_each_terminal_task_as_external_user(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = root / "kit/acceptance/run_loaded.py"
            entry.parent.mkdir(parents=True)
            entry.write_text("# shipped fixture", encoding="utf-8")
            output = root / "external-capacity-diagnostic"
            output.mkdir()
            cases = [{"taskCount": 10, "verifiedItems": 30, "terminalTaskCount": 10,
                      "taskIds": [f"task-{stage}-{index}" for index in range(10)]} for stage in range(2)]
            summary = {"status": "diagnostic-passed", "claim": "diagnostic-only", "acceptance": {"cases": cases},
                       "cleanup": {"ownedProcessesStopped": True, "privateCredentialCopiesRemoved": True}}
            (output / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
            gate = Mock(admin_url="https://localhost:8443", worker_url="https://workers.localhost:8443")
            gate.request.return_value = (200, json.dumps({"scoreBand": "terminal", "task": {
                "workerGroupId": proof.GROUP_LOADED, "metadata": {"userId": proof.EXTERNAL_USER}}}).encode(), {})
            server = Mock()
            server.poll.return_value = None
            child = Mock()
            child.wait.return_value = 0
            with patch.object(proof, "start_process", return_value=child) as start, patch.object(proof, "stop_process"):
                result = proof._loaded(gate, [server], simulator_home=root / "kit", proof_root=root,
                                       truststore=root / "ca.p12", environment={"PYTHONPATH": "checkout"})
            command = start.call_args.args[0]
            self.assertEqual(command[1], str(entry))
            self.assertIn("--diagnostic", command)
            self.assertNotIn(proof.EXTERNAL_KEY, " ".join(command))
            self.assertNotIn("PYTHONPATH", start.call_args.args[2])
            self.assertEqual(gate.request.call_count, 20)
            self.assertEqual(result["verifiedItems"], 60)
            self.assertFalse((root / "loaded-worker-credential.json").exists())

    def finite(self, stdout: bytes, returncode=0, *, user=proof.EXTERNAL_USER, alive=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gate = Mock(admin_url="https://localhost:8443", ca_file=root / "ca.pem")
            gate.request.return_value = (200, json.dumps({"scoreBand": "terminal", "task": {
                "workerGroupId": "g", "metadata": {"userId": user}}}).encode(), {})
            process = Mock()
            process.poll.return_value = None if alive else 1
            completed = Mock(stdout=stdout, stderr=b"", returncode=returncode)
            with patch.object(proof.subprocess, "run", return_value=completed) as run:
                witness = proof._finite(gate, "g", "initial", [process], simulator_home=root / "kit",
                                        proof_root=root, environment={"PYTHONPATH": "checkout"})
            command, options = run.call_args.args[0], run.call_args.kwargs
            self.assertEqual(command[1:3], ["-I", str(root / "kit/acceptance/finite_round.py")])
            self.assertNotIn(proof.EXTERNAL_KEY, " ".join(command))
            self.assertEqual(options["env"]["SSL_CERT_FILE"], str(root / "ca.pem"))
            self.assertNotIn("PYTHONPATH", options["env"])
            self.assertEqual((root / "external-access-key.txt").read_text().strip(), proof.EXTERNAL_KEY)
            return witness

    def passing(self, **changes):
        return json.dumps({"outcome": "passed", "phase": "done", "group": "g", "taskId": "t1", "items": 3,
                           "exportSha256": "e" * 64, "kitVersion": "4.1.0",
                           "durationsMillis": {"approveToTerminal": 5, "total": 9}, **changes}).encode()

    def test_kit_round_runs_the_shipped_command_and_checks_the_external_users_task(self):
        witness = self.finite(self.passing())
        self.assertEqual((witness["taskId"], witness["kitVersion"], witness["items"]), ("t1", "4.1.0", 3))
        for stdout, code, user, alive in ((self.passing(outcome="unconfirmed"), 3, proof.EXTERNAL_USER, True),
                                          (self.passing(), 1, proof.EXTERNAL_USER, True),
                                          (self.passing(group="other"), 0, proof.EXTERNAL_USER, True),
                                          (self.passing() + b"\n" + self.passing(), 0, proof.EXTERNAL_USER, True),
                                          (self.passing(), 0, "1000", True),
                                          (self.passing(), 0, proof.EXTERNAL_USER, False)):
            with self.subTest(code=code, user=user, alive=alive), self.assertRaises(RuntimeError):
                self.finite(stdout, code, user=user, alive=alive)

    def test_external_user_is_a_default_project_operator_with_a_private_key(self):
        with tempfile.TemporaryDirectory() as directory:
            users = json.loads(proof._users_file(Path(directory)).read_text(encoding="utf-8"))["users"]
        external = next(user for user in users if user["userId"] == proof.EXTERNAL_USER)
        # The Server's TaskUserId format; an invalid ID makes the packaged Runtime refuse the users file.
        self.assertRegex(external["userId"], r"^[1-9a-z][0-9a-z]{3,8}$")
        self.assertEqual((external["role"], external["projectIds"]), ("operator", ["default"]))
        self.assertEqual(external["accessKeySha256"], hashlib.sha256(proof.EXTERNAL_KEY.encode()).hexdigest())
        self.assertNotEqual(proof.EXTERNAL_KEY, proof.ADMIN_KEY)

    def test_console_checks_actual_packaged_js_and_css_not_only_index_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frontend = root / "frontend/dist"
            (frontend / "static/js").mkdir(parents=True)
            (frontend / "static/css").mkdir(parents=True)
            index = b'<script src="/static/js/index-123.js"></script><link rel="stylesheet" href="/static/css/index-456.css">'
            javascript, stylesheet = b'console.log("loaded")', b'body{margin:0}'
            (frontend / "index.html").write_bytes(index)
            (frontend / "static/js/index-123.js").write_bytes(javascript)
            (frontend / "static/css/index-456.css").write_bytes(stylesheet)
            gate = Mock()
            good = [(200, index, {"Content-Type": "text/html"}),
                    (200, javascript, {"Content-Type": "text/javascript;charset=UTF-8"}),
                    (200, stylesheet, {"Content-Type": "text/css"})]
            gate.request.side_effect = good
            self.assertEqual(len(proof._console(gate, root)["entrypointAssets"]), 2)
            for bad in ((200, index, {"Content-Type": "text/html"}),
                        (404, b"missing", {"Content-Type": "text/html"}),
                        (200, b"different bytes", {"Content-Type": "text/javascript"})):
                gate.request.side_effect = [good[0], bad]
                with self.assertRaises(RuntimeError):
                    proof._console(gate, root)

    def test_unknown_failure_keeps_stage_but_does_not_print_exception_payload(self):
        def fail(**arguments):
            arguments["evidence"]["phase"] = "worker-startup"
            raise ValueError("private-response-or-credential")
        with patch.object(proof, "_verify", side_effect=fail):
            with self.assertRaises(RuntimeError) as observed:
                proof.verify(runtime_root=Path("unused"), simulator_home=Path("unused"),
                             redis_url="unused", scope="test_fixture", proof_root=Path("unused"))
        self.assertNotIn("private-response-or-credential", str(observed.exception))
        self.assertEqual(observed.exception.safe_evidence["phase"], "worker-startup")
        self.assertEqual(observed.exception.safe_evidence["exceptionType"], "ValueError")
        self.assertIsNone(observed.exception.__cause__)


if __name__ == "__main__":
    unittest.main()
