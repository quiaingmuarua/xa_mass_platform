from __future__ import annotations

import inspect
import unittest
from pathlib import Path
from unittest.mock import Mock

from kernel_design.executable_spec.assembly import (
    DeliverSeed,
    KernelApplication,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCreationResult,
    TaskCreationStatus,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)

try:
    from fastapi.testclient import TestClient

    from kernel_design.examples.fastapi_server import create_app
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_app = None  # type: ignore[assignment]


class FastApiBoundaryGuardTest(unittest.TestCase):
    def test_server_imports_only_the_assembly_application_boundary(self) -> None:
        source_path = (
            Path(__file__).parents[2] / "examples" / "fastapi_server.py"
        )
        source = source_path.read_text(encoding="utf-8")

        self.assertIn("from kernel_design.executable_spec.assembly import", source)
        for forbidden in (
            "executable_spec.redis_runtime",
            "TaskScoreBandCore",
            "WorkerScoreCore",
            "TaskWorkerAllocationPacer",
            "_RedisKernelProcess",
        ):
            self.assertNotIn(forbidden, source)


@unittest.skipUnless(TestClient is not None, "FastAPI example dependencies missing")
class FastApiServerTest(unittest.TestCase):
    def setUp(self) -> None:
        assert create_app is not None
        self.application = Mock(spec=KernelApplication)
        self.application.register_worker_group.return_value = WorkerRuntimeResult(
            WorkerRuntimeStatus.OK
        )
        self.application.register_worker.return_value = WorkerRuntimeResult(
            WorkerRuntimeStatus.OK
        )
        self.application.create_task.return_value = TaskCreationResult(
            TaskCreationStatus.CREATED
        )
        self.application.approve_task.return_value = TaskApprovalResult(
            TaskApprovalStatus.APPROVED
        )
        self.application.append_task_items.return_value = {
            "message-1": TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
        }
        self.application.consume_deliver_seeds.return_value = (
            DeliverSeed(
                worker_id="worker-1",
                opaque_delivery_item="delivery",
                opaque_result_context="context",
                task_item_claim_until_millis=5_000,
            ),
        )
        self.client_context = TestClient(create_app(application=self.application))
        self.client = self.client_context.__enter__()

    def tearDown(self) -> None:
        self.client_context.__exit__(None, None, None)

    def test_lifespan_and_health_use_one_kernel_application(self) -> None:
        self.assertEqual({"status": "ok"}, self.client.get("/health").json())
        self.application.start.assert_called_once_with()

    def test_routes_translate_http_values_to_assembly_contracts(self) -> None:
        group_response = self.client.post(
            "/worker-groups",
            json={
                "workerGroupId": "image-workers",
                "attributes": {"kind": "image"},
                "eventCodes": ["image.resize"],
            },
        )
        worker_response = self.client.post(
            "/workers",
            json={
                "workerId": "worker-1",
                "workerGroupId": "image-workers",
                "endpointManagerId": "endpoint-1",
                "systemMetadata": {},
                "staticAttributes": {"runtime": "python"},
                "dynamicAttributeNames": [],
            },
        )
        task_response = self.client.post(
            "/tasks",
            json={
                "taskId": "task-1",
                "workerGroupId": "image-workers",
                "allocationRule": {"static.runtime": {"$eq": "python"}},
                "config": {
                    "priority": "80",
                    "maximumCandidateWorkers": "10",
                    "runningVisibleMinimumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
            },
        )
        approval_response = self.client.post("/tasks/task-1/approve")
        append_response = self.client.post(
            "/tasks/task-1/items",
            json={
                "items": [
                    {
                        "messageId": "message-1",
                        "eventCode": "image.resize",
                        "createdAtMillis": 1,
                        "payload": {"source": "input"},
                    }
                ]
            },
        )
        consume_response = self.client.post(
            "/endpoint-managers/endpoint-1/deliver-seeds:consume?limit=10"
        )

        self.assertEqual(201, group_response.status_code)
        self.assertEqual(201, worker_response.status_code)
        self.assertEqual(201, task_response.status_code)
        self.assertEqual(200, approval_response.status_code)
        self.assertEqual(
            {"message-1": {"status": "appended"}},
            append_response.json(),
        )
        self.assertEqual(
            [
                {
                    "workerId": "worker-1",
                    "opaqueDeliveryItem": "delivery",
                    "opaqueResultContext": "context",
                    "taskItemClaimUntilMillis": 5_000,
                }
            ],
            consume_response.json(),
        )
        self.assertNotIn(
            "suffix",
            inspect.signature(self.application.create_task).parameters,
        )

    def test_stop_runs_when_lifespan_closes(self) -> None:
        self.client_context.__exit__(None, None, None)
        self.application.stop.assert_called_once_with()
        self.client_context = _ClosedContext()

    def test_domain_validation_error_is_a_protocol_422(self) -> None:
        response = self.client.post(
            "/tasks",
            json={
                "taskId": "task-1",
                "workerGroupId": "image-workers",
                "allocationRule": {},
                "config": {},
            },
        )

        self.assertEqual(422, response.status_code)
        self.assertIn("task config", response.json()["detail"])
        self.application.create_task.assert_not_called()


class _ClosedContext:
    def __exit__(self, *args: object) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
