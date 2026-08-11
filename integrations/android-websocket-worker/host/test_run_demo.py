from __future__ import annotations

import json
import unittest
from unittest.mock import patch

import run_demo


WORKER_ID = "server-issued-worker-id"


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
        if operation == "task.create":
            return run_demo.ApiResponse(201, {"status": "created"})
        if operation == "task.approve":
            return run_demo.ApiResponse(200, {"status": "approved"})
        if operation == "taskItem.call":
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
        if operation == "task.close":
            return run_demo.ApiResponse(200, {"status": "closed"})
        raise AssertionError(operation)


class RunDemoTest(unittest.TestCase):
    def setUp(self) -> None:
        FakeRuntimeApiClient.last_instance = None
        FakeRuntimeApiClient.fail_call = False

    def test_targets_worker_and_closes_task(self) -> None:
        with patch.object(
            run_demo,
            "RuntimeApiClient",
            FakeRuntimeApiClient,
        ):
            result = run_demo.run_demo(
                server_base_url="http://127.0.0.1:18082",
                worker_id=WORKER_ID,
                request_timeout_seconds=2,
                wait_timeout_millis=1_000,
            )

        client = FakeRuntimeApiClient.last_instance
        self.assertIsNotNone(client)
        operations = [request[3] for request in client.requests]
        self.assertEqual(
            ["task.create", "task.approve", "taskItem.call", "task.close"],
            operations,
        )
        call_body = client.requests[2][2]
        self.assertEqual(
            WORKER_ID,
            call_body["item"]["allocationRule"]["workerId"]["$eq"],
        )
        self.assertEqual("android.demo.state.read", result["eventCode"])
        self.assertEqual(7, result["result"]["counter"])

    def test_closes_task_when_rpc_fails(self) -> None:
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
                    worker_id=WORKER_ID,
                    request_timeout_seconds=2,
                    wait_timeout_millis=1_000,
                )

        operations = [
            request[3]
            for request in FakeRuntimeApiClient.last_instance.requests
        ]
        self.assertEqual("task.close", operations[-1])

    def test_rejects_blank_worker_id(self) -> None:
        with self.assertRaises(ValueError):
            run_demo.run_demo(
                server_base_url="http://127.0.0.1:18082",
                worker_id=" ",
                request_timeout_seconds=2,
                wait_timeout_millis=1_000,
            )


if __name__ == "__main__":
    unittest.main()
