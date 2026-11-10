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
    def setUp(self):
        self.expected = {"item-a": "a", "item-unicode": "号码检查 ☕"}
        self.results = {identity: {"status": "succeeded", "opaqueResultPayload": json.dumps({
            "input": seed, "valid": True, "md5": hashlib.md5(seed.encode("utf-8")).hexdigest()})}
            for identity, seed in self.expected.items()}

    def test_independent_utf8_digest_and_exact_result_identity_set(self):
        proof._assert_results(self.results, self.expected)
        for wrong in (
            {"item-a": self.results["item-a"]},
            {**self.results, "extra": self.results["item-a"]},
            {**self.results, "item-a": {"status": "failed"}},
            {**self.results, "item-a": {"status": "not_observed"}},
        ):
            with self.assertRaises(RuntimeError):
                proof._assert_results(wrong, self.expected)
        for field, value in (("input", "another seed"), ("md5", "0" * 32), ("valid", False), ("valid", 1)):
            wrong = copy.deepcopy(self.results)
            content = json.loads(wrong["item-a"]["opaqueResultPayload"])
            content[field] = value
            wrong["item-a"]["opaqueResultPayload"] = json.dumps(content)
            with self.assertRaises(RuntimeError):
                proof._assert_results(wrong, self.expected)

    def test_preview_must_preserve_stored_item_input_event_and_actual_pool_query(self):
        preview = {"truncated": False, "results": [
            {"messageId": identity, "item": {"messageId": identity, "eventCode": proof.EVENT,
                "payload": {"value": seed}, "workerSelector": proof.SELECTOR}, "result": self.results[identity]}
            for identity, seed in self.expected.items()
        ]}
        proof._assert_preview(preview, self.expected)
        for key, value in (("workerSelector", {"executorName": "workerId", "input": "a-worker"}),
                           ("eventCode", "another.event"), ("payload", {"value": "wrong"})):
            wrong = copy.deepcopy(preview)
            wrong["results"][0]["item"][key] = value
            with self.assertRaises(RuntimeError):
                proof._assert_preview(wrong, self.expected)
        wrong = copy.deepcopy(preview)
        wrong["results"][1] = wrong["results"][0]
        with self.assertRaises(RuntimeError):
            proof._assert_preview(wrong, self.expected)
        with self.assertRaises(RuntimeError):
            proof._assert_preview({**preview, "truncated": True}, self.expected)

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
