from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from kernel_design.executable_spec.assembly import (
    DeliverSeed,
    SeedResult,
    WorkerCommandConsumerClient,
    WorkerCommandEnvelope,
    WorkerMessageType,
    SeedResultCommandClient,
    encode_deliver_seed,
)

try:
    from fastapi.testclient import TestClient

    from kernel_design.examples.worker_adapter_server import create_app
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_app = None  # type: ignore[assignment]


class WorkerAdapterServerBoundaryGuardTest(unittest.TestCase):
    def test_server_depends_only_on_transport_assembly_boundaries(self) -> None:
        source_path = (
            Path(__file__).parents[2] / "examples" / "worker_adapter_server.py"
        )
        source = source_path.read_text(encoding="utf-8")

        self.assertIn("from kernel_design.executable_spec.assembly import", source)
        for forbidden in (
            "executable_spec.redis_runtime",
            "    KernelApplication,\n",
            "TaskScoreBandCore",
            "WorkerScoreCore",
            "Pacer",
        ):
            self.assertNotIn(forbidden, source)


@unittest.skipUnless(TestClient is not None, "FastAPI example dependencies missing")
class WorkerAdapterServerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        assert create_app is not None
        self.consumer = Mock(spec=WorkerCommandConsumerClient)
        self.result_commands = Mock(spec=SeedResultCommandClient)
        self.result_commands.append_seed_results.return_value = 1
        self.client = TestClient(
            create_app(
                endpoint_manager_id="endpoint-manager-1",
                worker_command_consumer=self.consumer,
                seed_result_commands=self.result_commands,
            )
        )

    @staticmethod
    def command(
        *,
        execute_before_millis: int = 105_000,
    ) -> WorkerCommandEnvelope:
        return WorkerCommandEnvelope(
            command_id="a5e9e10d-f78b-469e-93ab-864b49c189c1",
            message_type=WorkerMessageType.TASK_ITEM,
            execute_before_millis=execute_before_millis,
            opaque_item=encode_deliver_seed(
                DeliverSeed(
                    worker_id="worker-1",
                    opaque_delivery_item=(
                        '{"eventCode":"event-1","payload":{"value":1}}'
                    ),
                    opaque_result_context="opaque-context",
                )
            ),
        )

    def test_health_does_not_require_kernel_lifecycle(self) -> None:
        self.assertEqual({"status": "ok"}, self.client.get("/health").json())
        for boundary in (self.consumer, self.result_commands):
            self.assertFalse(hasattr(boundary, "start"))
            self.assertFalse(hasattr(boundary, "stop"))

    def test_endpoint_manager_id_is_required(self) -> None:
        assert create_app is not None
        with self.assertRaisesRegex(ValueError, "non-empty"):
            create_app(
                endpoint_manager_id="",
                worker_command_consumer=self.consumer,
                seed_result_commands=self.result_commands,
            )

    def test_poll_returns_204_when_worker_mailbox_is_empty(self) -> None:
        self.consumer.consume_worker_command.return_value = None

        response = self.client.post("/workers/worker-1/commands:poll")

        self.assertEqual(204, response.status_code)
        self.consumer.consume_worker_command.assert_called_once_with(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )
        self.result_commands.append_seed_results.assert_not_called()

    def test_poll_forwards_kernel_command_envelope_unchanged(self) -> None:
        command = self.command()
        self.consumer.consume_worker_command.return_value = command

        with patch(
            "kernel_design.examples.worker_adapter_server._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post("/workers/worker-1/commands:poll")

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {
                "commandId": command.command_id,
                "executeBeforeMillis": 105_000,
                "messageType": "TASK_ITEM",
                "opaqueItem": command.opaque_item,
            },
            response.json(),
        )

    def test_expired_command_is_dropped_without_result_evidence(self) -> None:
        self.consumer.consume_worker_command.return_value = self.command(
            execute_before_millis=self.NOW_MILLIS
        )

        with patch(
            "kernel_design.examples.worker_adapter_server._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post("/workers/worker-1/commands:poll")

        self.assertEqual(204, response.status_code)
        self.result_commands.append_seed_results.assert_not_called()

    def test_worker_seed_results_are_forwarded_without_command_wrapping(
        self,
    ) -> None:
        results = (
            SeedResult(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                "success-context",
                "200",
                "null",
            ),
            SeedResult(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "failure-context",
                "1500",
            ),
        )

        for result in results:
            with self.subTest(command_id=result.command_id):
                self.result_commands.reset_mock()
                self.result_commands.append_seed_results.return_value = 1

                response = self.client.post(
                    "/workers/worker-1/results",
                    json={
                        "commandId": result.command_id,
                        "opaqueResultContext": result.opaque_result_context,
                        "outcomeCode": result.outcome_code,
                        "opaqueResultPayload": result.opaque_result_payload,
                    },
                )

                self.assertEqual(202, response.status_code)
                self.assertEqual({"accepted": True}, response.json())
                submitted = self.result_commands.append_seed_results.call_args.kwargs[
                    "results"
                ][0]
                self.assertEqual(result, submitted)

    def test_worker_result_contract_rejects_invalid_seed_results(
        self,
    ) -> None:
        base_request = {
            "commandId": "a5e9e10d-f78b-469e-93ab-864b49c189c1",
            "opaqueResultContext": "context",
            "outcomeCode": "200",
            "opaqueResultPayload": "null",
        }
        invalid_requests = (
            {**base_request, "outcomeCode": "3001"},
            {**base_request, "outcomeCode": "500"},
            {**base_request, "opaqueResultContext": ""},
            {**base_request, "commandId": "not-a-uuid"},
            {**base_request, "opaqueResultPayload": None},
            {**base_request, "messageType": "TASK_ITEM"},
        )

        for request in invalid_requests:
            with self.subTest(request=request):
                response = self.client.post(
                    "/workers/worker-1/results",
                    json=request,
                )
                self.assertEqual(422, response.status_code)

        self.result_commands.append_seed_results.assert_not_called()

    def test_unaccepted_result_returns_retryable_http_status(self) -> None:
        self.result_commands.append_seed_results.return_value = 0

        response = self.client.post(
            "/workers/worker-1/results",
            json={
                "commandId": "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                "opaqueResultContext": "context",
                "outcomeCode": "200",
                "opaqueResultPayload": "null",
            },
        )

        self.assertEqual(503, response.status_code)

    def test_injected_boundaries_must_be_supplied_together(self) -> None:
        assert create_app is not None
        with self.assertRaisesRegex(ValueError, "injected together"):
            create_app(
                endpoint_manager_id="endpoint-manager-1",
                worker_command_consumer=self.consumer,
            )
        with self.assertRaisesRegex(ValueError, "injected together"):
            create_app(
                endpoint_manager_id="endpoint-manager-1",
                seed_result_commands=self.result_commands,
            )


if __name__ == "__main__":
    unittest.main()
