from __future__ import annotations

import inspect
import unittest
from pathlib import Path
from unittest.mock import Mock

from kernel_design.executable_spec.assembly import (
    TaskType,
    KernelApplication,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCloseResult,
    TaskCloseStatus,
    TaskCreationResult,
    TaskCreationStatus,
)

try:
    from fastapi.testclient import TestClient

    from kernel_design.runtime_server import create_app
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_app = None  # type: ignore[assignment]


class KernelRuntimeServerBoundaryGuardTest(unittest.TestCase):
    def test_server_imports_only_the_assembly_application_boundary(self) -> None:
        source_path = (
            Path(__file__).parents[2] / "runtime_server" / "app.py"
        )
        source = source_path.read_text(encoding="utf-8")

        self.assertIn("from kernel_design.executable_spec.assembly import", source)
        for forbidden in (
            "executable_spec.redis_runtime",
            "ResourcesCommandClient",
            "WorkerGroupRequest",
            "WorkerRequest",
            "TaskScoreBandCore",
            "WorkerScoreCore",
            "TaskWorkerAllocationPacer",
            "_RedisKernelProcess",
            "WorkerCommandConsumerClient",
            "WorkerResultCommandClient",
            "worker_delivery",
        ):
            self.assertNotIn(forbidden, source)


@unittest.skipUnless(TestClient is not None, "FastAPI example dependencies missing")
class KernelRuntimeServerTest(unittest.TestCase):
    def setUp(self) -> None:
        assert create_app is not None
        self.application = Mock(spec=KernelApplication)
        self.application.create_task.return_value = TaskCreationResult(
            TaskCreationStatus.CREATED
        )
        self.application.approve_task.return_value = TaskApprovalResult(
            TaskApprovalStatus.APPROVED
        )
        self.application.close_task.return_value = TaskCloseResult(
            TaskCloseStatus.CLOSED
        )
        self.application.wake_task_dispatch.return_value = 2
        self.client_context = TestClient(
            create_app(application=self.application)
        )
        self.client = self.client_context.__enter__()

    def tearDown(self) -> None:
        self.client_context.__exit__(None, None, None)

    def test_lifespan_and_health_use_one_kernel_application(self) -> None:
        self.assertEqual({"status": "ok"}, self.client.get("/health").json())
        self.application.start.assert_called_once_with()
        self.assertFalse(
            hasattr(
                self.client.app.state,
                "resources_command_client",
            )
        )

    def test_task_routes_translate_http_values_to_application(self) -> None:
        task_response = self.client.post(
            "/tasks",
            json={
                "taskId": "task-1",
                "workerGroupId": "image-workers",
                "taskType": "ITEM_DRIVEN",
                "emptyCloseAtMillis": 1234,
                "config": {
                    "priority": "80",
                    "maximumCandidateWorkers": "10",
                    "maxRetryTimes": "3",
                },
            },
        )
        approval_response = self.client.post("/tasks/task-1/approve")
        close_response = self.client.post("/tasks/task-1/close")
        wake_response = self.client.post(
            "/tasks:dispatch-wake",
            json={"taskIds": ["task-1", "task-1", "task-2"]},
        )
        removed_append_response = self.client.post(
            "/tasks/task-1/items",
            json={
                "items": [
                    {
                        "messageId": "message-1",
                        "eventCode": "image.resize",
                        "createdAtMillis": 1,
                        "payload": {"source": "input"},
                        "allocationRule": {
                            "workerId": {"$eq": "worker-1"}
                        },
                    }
                ]
            },
        )
        self.assertEqual(201, task_response.status_code)
        self.assertEqual(200, approval_response.status_code)
        self.assertEqual(200, close_response.status_code)
        self.assertEqual(200, wake_response.status_code)
        self.assertEqual(
            {"status": "accepted", "acceptedTaskCount": 2},
            wake_response.json(),
        )
        self.assertEqual({"status": "closed"}, close_response.json())
        self.assertEqual(404, removed_append_response.status_code)
        self.assertNotIn(
            "suffix",
            inspect.signature(self.application.create_task).parameters,
        )
        task_descriptor = self.application.create_task.call_args.kwargs["descriptor"]
        self.assertIs(
            TaskType.ITEM_DRIVEN,
            task_descriptor.task_type,
        )
        self.assertIsNone(task_descriptor.allocation_rule)
        self.assertEqual(1234, task_descriptor.empty_close_at_millis)
        self.application.wake_task_dispatch.assert_called_once_with(
            task_ids=("task-1", "task-2"),
        )
        self.assertFalse(hasattr(self.application, "consume_worker_commands"))
        self.assertEqual(
            404,
            self.client.put(
                "/worker-groups/image-workers",
                json={},
            ).status_code,
        )
        self.assertEqual(
            404,
            self.client.put(
                "/worker-groups/image-workers/workers/worker-1",
                json={},
            ).status_code,
        )
        self.assertEqual(404, self.client.post("/worker-groups").status_code)
        self.assertEqual(404, self.client.post("/workers").status_code)

    def test_task_rejects_invalid_empty_close_threshold(self) -> None:
        base_request = {
            "taskId": "task-1",
            "workerGroupId": "image-workers",
            "taskType": "TASK_DRIVEN",
            "allocationRule": {},
            "config": {
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        }

        for value in (-1, True, "1000"):
            with self.subTest(value=value):
                response = self.client.post(
                    "/tasks",
                    json={**base_request, "emptyCloseAtMillis": value},
                )
                self.assertEqual(422, response.status_code)

        self.application.create_task.assert_not_called()

    def test_dynamic_attribute_route_is_not_public(self) -> None:
        response = self.client.post(
            "/workers/image-workers/worker-1/dynamic-attributes",
            json={"updates": {"battery": 80}, "observedAtMillis": 1},
        )

        self.assertEqual(404, response.status_code)
        self.assertEqual(
            404,
            self.client.post(
                "/worker-results",
                json={"forward": "context", "outcomeCode": "200"},
            ).status_code,
        )
        self.assertEqual(
            404,
            self.client.post(
                "/worker-delivery/endpoint-managers/system-polling/"
                "workers/worker-1/commands:poll"
            ).status_code,
        )

    def test_config_and_injected_application_are_mutually_exclusive(self) -> None:
        assert create_app is not None
        with self.assertRaisesRegex(ValueError, "mutually exclusive"):
            create_app(
                config_json="{}",
                application=self.application,
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
                "taskType": "TASK_DRIVEN",
                "allocationRule": {},
                "config": {},
            },
        )

        self.assertEqual(422, response.status_code)
        self.assertIn("task config", response.json()["detail"])
        self.application.create_task.assert_not_called()

    def test_old_task_scope_contract_is_rejected(self) -> None:
        old_field_response = self.client.post(
            "/tasks",
            json={
                "taskId": "task-1",
                "workerGroupId": "image-workers",
                "allocationRuleScope": "TASK",
                "allocationRule": {},
                "config": {
                    "priority": "80",
                    "maximumCandidateWorkers": "10",
                    "maxRetryTimes": "3",
                },
            },
        )
        old_value_response = self.client.post(
            "/tasks",
            json={
                "taskId": "task-1",
                "workerGroupId": "image-workers",
                "taskType": "TASK",
                "allocationRule": {},
                "config": {
                    "priority": "80",
                    "maximumCandidateWorkers": "10",
                    "maxRetryTimes": "3",
                },
            },
        )

        self.assertEqual(422, old_field_response.status_code)
        self.assertEqual(422, old_value_response.status_code)
        self.application.create_task.assert_not_called()


class _ClosedContext:
    def __exit__(self, *args: object) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
