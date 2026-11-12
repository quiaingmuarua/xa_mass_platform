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
