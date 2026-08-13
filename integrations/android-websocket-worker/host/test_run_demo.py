from __future__ import annotations

import json
import unittest
from unittest.mock import patch

import run_demo


class FakeRuntimeApiClient:
    last_instance: "FakeRuntimeApiClient | None" = None
    fail_call = False

    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        self.base_url = base_url
        self.timeout_seconds = timeout_seconds
        self.requests: list[tuple[str, str, object, str]] = []
        FakeRuntimeApiClient.last_instance = self

    def send(self, method: str, path: str, body: object, operation: str):
        self.requests.append((method, path, body, operation))
        if operation != "workerGroupItem.call":
            raise AssertionError(operation)
        if self.fail_call:
            raise RuntimeError("scripted call failure")
        return run_demo.ApiResponse(
            200,
            {
                "status": "succeeded",
                "opaqueResultPayload": json.dumps(
                    {"counter": 7, "sdkInt": 33}
                ),
            },
        )


class RunDemoTest(unittest.TestCase):
    def setUp(self) -> None:
        FakeRuntimeApiClient.last_instance = None
        FakeRuntimeApiClient.fail_call = False

    def test_calls_the_group_with_an_unrestricted_item(self) -> None:
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
        self.assertEqual(1, len(client.requests))
        method, path, call_body, operation = client.requests[0]
        self.assertEqual("POST", method)
        self.assertEqual(
            "/api/v1/worker-groups/android-demo-workers/items:call",
            path,
        )
        self.assertEqual("workerGroupItem.call", operation)
        self.assertEqual({}, call_body["item"]["allocationRule"])
        self.assertNotIn("workerId", call_body["item"])
        self.assertNotIn("workerGroupId", call_body["item"])
        self.assertNotIn("taskId", call_body)
        self.assertEqual("android.demo.state.read", result["eventCode"])
        self.assertEqual(7, result["result"]["counter"])
        self.assertNotIn("workerId", result)
        self.assertNotIn("taskId", result)

    def test_propagates_rpc_failure_without_task_cleanup(self) -> None:
        FakeRuntimeApiClient.fail_call = True
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
            ["workerGroupItem.call"],
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
