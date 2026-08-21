from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import Mock

from kernel_design.executable_spec.assembly import (
    KernelApplication,
    KernelApplicationConfig,
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
            "TaskRequest",
            "TaskCallItemRequest",
            "@app.post",
        ):
            self.assertNotIn(forbidden, source)


@unittest.skipUnless(TestClient is not None, "FastAPI example dependencies missing")
class KernelRuntimeServerTest(unittest.TestCase):
    def setUp(self) -> None:
        assert create_app is not None
        self.application = Mock(spec=KernelApplication)
        self.client_context = TestClient(
            create_app(application=self.application)
        )
        self.client = self.client_context.__enter__()

    def tearDown(self) -> None:
        self.client_context.__exit__(None, None, None)

    def test_lifespan_and_health_use_one_kernel_application(self) -> None:
        self.assertEqual({"status": "ok"}, self.client.get("/health").json())
        self.assertEqual(
            {"/health"},
            {route.path for route in self.client.app.routes},
        )
        self.application.start.assert_called_once_with()
        self.assertFalse(
            hasattr(
                self.client.app.state,
                "resources_command_client",
            )
        )

    def test_task_business_routes_are_not_exposed(self) -> None:
        for path in (
            "/tasks",
            "/tasks/task-1/approve",
            "/tasks/task-1/close",
            "/tasks/task-1:submit-call-items",
        ):
            with self.subTest(path=path):
                self.assertEqual(404, self.client.post(path, json={}).status_code)

        self.application.create_task.assert_not_called()
        self.application.approve_task.assert_not_called()
        self.application.close_task.assert_not_called()
        self.application.submit_task_call_items.assert_not_called()

    def test_worker_resource_routes_are_not_public(self) -> None:
        response = self.client.post(
            "/worker-groups/image-workers/workers/worker-1",
            json={"workerProperties": {"runtime": "python"}},
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
                config=KernelApplicationConfig(),
                application=self.application,
            )

    def test_stop_runs_when_lifespan_closes(self) -> None:
        self.client_context.__exit__(None, None, None)
        self.application.stop.assert_called_once_with()
        self.client_context = _ClosedContext()

class _ClosedContext:
    def __exit__(self, *args: object) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
