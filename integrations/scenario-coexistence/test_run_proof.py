import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import urllib.error
import run_proof as proof


class ProofAssertionsTest(unittest.TestCase):
    def test_campaign_countries_are_independent_and_numbers_are_repeatable(self):
        with patch.object(proof, "http", return_value={"id": "campaign"}):
            _, cross = proof.campaign(SimpleNamespace(url="server"), country="US", recipient_country="CN")
            _, any_sender = proof.campaign(SimpleNamespace(url="server"), country=None, recipient_country="CN")
        self.assertEqual("CN", cross["recipientCountry"])
        self.assertEqual("US", cross["senderCountry"])
        self.assertIsNone(any_sender["senderCountry"])
        self.assertEqual(cross["recipientIds"], any_sender["recipientIds"])
        self.assertNotIn("senderPhone", cross)
        self.assertNotIn("senderPhone", any_sender)
        self.assertTrue(all(number.startswith("+86") and number[1:].isdigit() for number in cross["recipientIds"]))
        self.assertNotIn("country", cross)

    def test_recipient_input_uses_the_original_worker_coordinate(self):
        run = SimpleNamespace(host="host", input_workers_by_id={"worker": {
            "workerGroupId": "demo-sim", "replicaKey": "workers-000.jsonl:2"}})
        with patch.object(proof, "http", return_value={"persisted": True}) as send:
            proof.action(run, {"workerId": "worker", "id": "message"}, "reply", "reply", "request")
            send.assert_called_once_with("host", "/lab/v1/workers/demo-sim/workers-000.jsonl%3A2:inputs",
                {"eventName": "message.reply", "payload": {
                    "messageId": "message", "text": "reply", "requestId": "request"}}, timeout=2)

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

    def test_equal_reply_text_cannot_satisfy_a_newer_reply_identity(self):
        message, snapshot, http = self.fixture()
        message["replyRequestId"] = snapshot["replyRequestId"] = "older"
        with patch.object(proof, "http", side_effect=http), patch.object(proof, "campaign_messages", return_value=[message]), \
                patch.object(proof.time, "monotonic", side_effect=[0, 5.1]), patch.object(proof.time, "sleep"):
            with self.assertRaisesRegex(AssertionError, "did not converge"):
                proof.observe_receipt(SimpleNamespace(url="server"), {"taskId": "task"}, message, "REPLIED", 0, "latest", "newest")

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


class PoolSelectionTest(unittest.TestCase):
    def fixture(self, us_worker="US-0", managed_state="running-initial", pool="messaging"):
        workers = {f"{country}-{i}": {"workerId": f"{country}-{i}", "country": country}
                   for country in ("CN", "US", "GB") for i in range(4)}
        run = SimpleNamespace(url="server", host="host", input_workers_by_id=workers, check=lambda: None)
        tasks, messages, requests = {}, {}, []

        def http(base, path, body=None, **kwargs):
            requests.append((path, body))
            if path in ("/api/v1/projects/sms", "/api/v1/projects/messages"):
                return {"managedTaskIds": {"demo-sim": path.rsplit("/", 1)[-1] + "-managed"}}
            if path in ("/api/v1/projects/sms/tasks?limit=100", "/api/v1/projects/messages/tasks?limit=100"):
                project = path.split("/")[4]
                rows = [{"taskId": project + "-managed", "scoreBand": managed_state}]
                return {"truncated": False, "tasks": rows + (list(tasks.values()) if project == "messages" else [])}
            if path == "/api/v1/messages/tasks" and body is not None:
                identity = body["requestId"]
                country = body["senderCountry"]
                tasks[identity] = {"taskId": identity, "task": {"metadata": {}, "refill": [{
                    "poolName": pool, "target": {"worker.country": [country]} if country else {}, "count": 100}]}}
                messages[identity] = [{"id": f"{identity}-{i}", "campaignId": identity, "status": "SENT",
                                       "workerId": us_worker if country == "US" else "GB-0",
                                       "country": body["recipientCountry"], "recipientId": recipient}
                                      for i, recipient in enumerate(body["recipientIds"])]
                return {"taskId": identity}
            raise AssertionError("Unexpected API call in Pool-only fixture")

        return run, http, messages, requests

    def execute(self, run, http, messages):
        with patch.object(proof, "http", side_effect=http), \
                patch.object(proof, "campaign_messages", side_effect=lambda run, value: messages[value["taskId"]]), \
                patch.object(proof, "lab_message", side_effect=lambda run, message: dict(message)), \
                patch.object(proof, "sms_create", side_effect=AssertionError("Pool proof created SMS demand")):
            return proof.pool_selection(run)

    def test_pool_only_world_keeps_both_real_send_oracles_without_sms_or_phone(self):
        run, http, messages, requests = self.fixture()
        result = self.execute(run, http, messages)
        self.assertTrue(result["passed"])
        self.assertEqual(4, result["messages"])
        offered = [body for _, body in requests if body is not None]
        self.assertEqual(["US", None], [body["senderCountry"] for body in offered])
        self.assertEqual(offered[0]["recipientIds"], offered[1]["recipientIds"])
        self.assertTrue(all("senderPhone" not in body for body in offered))
        self.assertEqual(4, sum(len(rows) for rows in messages.values()))
        self.assertIn("pool-us-send", run.completed_stages)
        self.assertIn("pool-any-send", run.completed_stages)

    def test_wrong_us_executor_fails_before_any_query(self):
        run, http, messages, _ = self.fixture(us_worker="CN-0")
        with self.assertRaisesRegex(AssertionError, "actual sender country"):
            self.execute(run, http, messages)
        self.assertEqual("pool-us-send", run.current_stage)
        self.assertNotIn("pool-us-send", run.completed_stages)
        self.assertEqual(["cross-country"], list(messages))

    def test_active_managed_task_rejects_fixture_before_submission(self):
        run, http, messages, requests = self.fixture(managed_state="running")
        with self.assertRaisesRegex(AssertionError, "Managed Task became active"):
            self.execute(run, http, messages)
        self.assertFalse(messages)
        self.assertTrue(all(body is None for _, body in requests))
        self.assertEqual("pool-supply-preconditions", run.current_stage)

    def test_wrong_supply_is_not_accepted_as_pool_selection(self):
        run, http, messages, _ = self.fixture(pool="country")
        with self.assertRaisesRegex(AssertionError, "Messaging Pool supply"):
            self.execute(run, http, messages)

    def test_phone_metadata_is_not_accepted_as_pool_selection(self):
        page = {"truncated": False, "tasks": [{"taskId": "task", "task": {
            "refill": [{"poolName": "messaging", "target": {}, "count": 100}],
            "metadata": {"senderPhone": "private"}}}]}
        with patch.object(proof, "http", return_value=page), self.assertRaisesRegex(AssertionError, "selected a phone"):
            proof.require_messaging_supply(SimpleNamespace(url="server"), {"taskId": "task"}, None)


class ScenarioSummaryTest(unittest.TestCase):
    class Run(SimpleNamespace):
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def execute(self, scenario, action):
        run = self.Run(host="host", artifacts={}, peaks={})
        with TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "private").mkdir()
            with patch.object(proof, "all_pages", return_value=[]), patch.object(proof, scenario, side_effect=action):
                result = proof.execute_scenario(run, scenario, output)
            private_failure = output / "private" / "failure.txt"
            return result, private_failure.read_text(encoding="utf-8") if private_failure.exists() else None

    def test_later_failure_preserves_completed_checks_and_safe_stage(self):
        checkpoint = {"stage": "DELIVERED", "platformMillis": 5, "productMillis": 6}

        def fail(run):
            proof.begin_stage(run, "direct-phone-send")
            proof.begin_stage(run, "completed-task-receipts")
            run.receipt_checkpoints = [checkpoint]
            proof.begin_stage(run, "latest-result-export")
            raise AssertionError("Export missed the later reply")

        result, _ = self.execute("functional", fail)
        self.assertFalse(result["passed"])
        self.assertEqual("latest-result-export", result["failedStage"])
        self.assertEqual(["startup", "inventory", "direct-phone-send", "completed-task-receipts"], result["completedStages"])
        self.assertEqual([checkpoint], result["checkpoints"])
        self.assertEqual("AssertionError", result["failureType"])

    def test_remote_failure_content_stays_private_without_mutation_retry(self):
        attempts = []

        def fail(run):
            attempts.append(True)
            proof.begin_stage(run, "lifecycle-old-run-send")
            raise RuntimeError("private remote response")

        result, private_failure = self.execute("lifecycle", fail)
        self.assertFalse(result["passed"])
        self.assertEqual([True], attempts)
        self.assertEqual("lifecycle-old-run-send", result["failedStage"])
        self.assertNotIn("private remote response", json.dumps(result))
        self.assertEqual("private remote response", private_failure)

    def test_success_completes_last_stage_and_shutdown(self):
        def succeed(run):
            proof.begin_stage(run, "latest-result-export")
            return {"passed": True}

        result, failure = self.execute("functional", succeed)
        self.assertTrue(result["passed"])
        self.assertEqual(["startup", "inventory", "latest-result-export", "shutdown"], result["completedStages"])
        self.assertNotIn("failedStage", result)
        self.assertIsNone(failure)


if __name__ == "__main__":
    unittest.main()
