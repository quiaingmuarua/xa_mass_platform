from __future__ import annotations

import json
import unittest
from unittest.mock import patch

import run_demo


class FakeRuntimeApiClient:
    last_instance: "FakeRuntimeApiClient | None" = None
    fail_event_code: str | None = None

    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        self.base_url = base_url
        self.timeout_seconds = timeout_seconds
        self.requests: list[tuple[str, str, object, str]] = []
        FakeRuntimeApiClient.last_instance = self

    def send(self, method: str, path: str, body: object, operation: str):
        self.requests.append((method, path, body, operation))
        if operation == "tasks.preview":
            return run_demo.ApiResponse(
                200,
                {
                    "entries": [{
                        "taskId": "managed-task",
                        "scoreBand": "running_visible",
                        "task": {
                            "taskId": "managed-task",
                            "workerGroupId": run_demo.WORKER_GROUP_ID,
                            "workerAllocationMechanism": "ON_DEMAND_ITEM_RULE",
                            "idleDisposition": "PARK_WHEN_IDLE",
                        },
                        "workerGroup": {
                            "workerGroupId": run_demo.WORKER_GROUP_ID,
                        },
                    }]
                },
            )
        if operation != "taskItems.call":
            raise AssertionError(operation)
        results = {}
        for item in body["items"]:
            event_code = item["eventCode"]
            if event_code == self.fail_event_code:
                raise RuntimeError("scripted call failure")
            if event_code == "extension.worker.android.state.read":
                result = {"counter": 7, "sdkInt": 33}
            elif event_code == "extension.worker.android.battery.read":
                result = {
                    "available": True,
                    "capacityPercent": 82,
                    "charging": False,
                }
            elif event_code == "extension.worker.android.string.digest":
                result = {
                    "algorithm": "MD5",
                    "input": "hello",
                    "digest": "5d41402abc4b2a76b9719d911017c592",
                }
            else:
                raise AssertionError(event_code)
            results[item["messageId"]] = {
                "status": "succeeded",
                "opaqueResultPayload": json.dumps(result),
            }
        return run_demo.ApiResponse(
            200,
            {"results": results},
        )


class RunDemoTest(unittest.TestCase):
    def setUp(self) -> None:
        FakeRuntimeApiClient.last_instance = None
        FakeRuntimeApiClient.fail_event_code = None

    def test_resolves_the_managed_task_and_calls_it_with_unrestricted_items(
        self,
    ) -> None:
        with patch.object(
            run_demo,
            "RuntimeApiClient",
            FakeRuntimeApiClient,
        ):
            result = run_demo.run_demo(
                server_base_url="http://127.0.0.1:18082",
                request_timeout_seconds=2,
                wait_timeout_millis=1_000,
            )

        client = FakeRuntimeApiClient.last_instance
        self.assertIsNotNone(client)
        self.assertEqual(2, len(client.requests))
        self.assertEqual(
            (
                "POST",
                "/api/v1/runtime-view/tasks:preview",
                {"sampleLimit": 100},
                "tasks.preview",
            ),
            client.requests[0],
        )
        expected_payloads = dict(run_demo.CAPABILITY_CALLS)
        method, path, call_body, operation = client.requests[1]
        self.assertEqual("POST", method)
        self.assertEqual(
            "/api/v1/tasks/managed-task/items:call",
            path,
        )
        self.assertEqual("taskItems.call", operation)
        event_codes = []
        for item in call_body["items"]:
            event_code = item["eventCode"]
            event_codes.append(event_code)
            self.assertEqual({}, item["allocationRule"])
            self.assertEqual(
                expected_payloads[event_code],
                item["payload"],
            )
            self.assertNotIn("workerId", item)
            self.assertNotIn("workerGroupId", item)
            self.assertNotIn("createdAtMillis", item)
        self.assertNotIn("taskId", call_body)
        self.assertEqual(list(run_demo.EVENT_CODES), event_codes)
        self.assertEqual(
            list(run_demo.EVENT_CODES),
            [item["eventCode"] for item in result["results"]],
        )
        self.assertEqual(7, result["results"][0]["result"]["counter"])
        self.assertEqual(
            82,
            result["results"][1]["result"]["capacityPercent"],
        )
        self.assertEqual(
            "5d41402abc4b2a76b9719d911017c592",
            result["results"][2]["result"]["digest"],
        )
        self.assertNotIn("workerId", result)
        self.assertNotIn("taskId", result)

    def test_propagates_rpc_failure_without_task_cleanup(self) -> None:
        FakeRuntimeApiClient.fail_event_code = (
            "extension.worker.android.battery.read"
        )
        with patch.object(
            run_demo,
            "RuntimeApiClient",
            FakeRuntimeApiClient,
        ):
            with self.assertRaisesRegex(
                RuntimeError,
                "scripted call failure",
            ):
                run_demo.run_demo(
                    server_base_url="http://127.0.0.1:18082",
                    request_timeout_seconds=2,
                    wait_timeout_millis=1_000,
                )

        self.assertEqual(
            ["tasks.preview", "taskItems.call"],
            [
                request[3]
                for request in FakeRuntimeApiClient.last_instance.requests
            ],
        )

    def test_rejects_invalid_wait_timeout(self) -> None:
        with self.assertRaises(ValueError):
            run_demo.run_demo(
                server_base_url="http://127.0.0.1:18082",
                request_timeout_seconds=2,
                wait_timeout_millis=0,
            )


if __name__ == "__main__":
    unittest.main()
