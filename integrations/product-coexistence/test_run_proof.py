import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import urllib.error
import run_proof as proof


class ProofAssertionsTest(unittest.TestCase):
    def test_task_completion_uses_score_band(self):
        with patch.object(proof, "http", return_value={"entries": [{"taskId": "task", "scoreBand": "terminal", "state": "unrelated"}]}):
            self.assertEqual("terminal", proof.task_state(SimpleNamespace(url="server"), "task"))

    def fixture(self):
        message = {"id": "message", "recipientId": "recipient", "workerId": "worker", "status": "REPLIED", "reply": "latest"}
        snapshot = {**message, "messageId": "message"}
        def http(base, path, body=None, **kwargs):
            if path.endswith("items:states"):
                return {"message": {"tag": 9, "band": "terminal"}}
            return {"message": {"status": "succeeded", "opaqueResultPayload": json.dumps(snapshot)}}
        return message, snapshot, http

    def test_wrong_association_is_an_immediate_failure(self):
        message, snapshot, http = self.fixture(); snapshot["recipientId"] = "other"
        with patch.object(proof, "http", side_effect=http):
            with self.assertRaisesRegex(AssertionError, "association"):
                proof.observe_receipt(SimpleNamespace(url="server"), {"taskId": "task"}, message, "REPLIED", proof.time.monotonic(), "latest")

    def test_both_observation_times_must_fit_original_budget(self):
        message, _, http = self.fixture()
        with patch.object(proof, "http", side_effect=http), patch.object(proof, "campaign_messages", return_value=[message]), \
                patch.object(proof.time, "monotonic", side_effect=[0, 4.9, 5.1]):
            with self.assertRaisesRegex(AssertionError, "budget exceeded"):
                proof.observe_receipt(SimpleNamespace(url="server"), {"taskId": "task"}, message, "REPLIED", 0, "latest")

    def test_only_temporary_read_failure_can_retry(self):
        attempts = 0
        def observation():
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise urllib.error.HTTPError("local", 503, "busy", {}, None)
            return "ready"
        run = SimpleNamespace(check=lambda: None)
        self.assertEqual("ready", proof.wait(run, observation, 1, "ready"))
        self.assertEqual(2, attempts)
        with self.assertRaises(urllib.error.HTTPError):
            proof.wait(run, lambda: (_ for _ in ()).throw(urllib.error.HTTPError("local", 400, "invalid", {}, None)), 1, "invalid")


if __name__ == "__main__":
    unittest.main()
