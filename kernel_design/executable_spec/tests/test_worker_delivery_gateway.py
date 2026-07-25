from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from kernel_design.executable_spec.assembly import (
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    DeliverSeed,
    SeedResult,
    SeedResultCommandClient,
    WorkerCommandConsumePage,
    WorkerCommandConsumerClient,
    WorkerCommandEnvelope,
    WorkerMessageType,
    encode_deliver_seed,
)

try:
    from fastapi import FastAPI, Request
    from fastapi.responses import JSONResponse
    from fastapi.testclient import TestClient

    from kernel_design.runtime_server.worker_delivery_gateway import (
        create_worker_delivery_router,
    )
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_worker_delivery_router = None  # type: ignore[assignment]


class WorkerDeliveryGatewayBoundaryGuardTest(unittest.TestCase):
    def test_gateway_depends_only_on_transport_assembly_boundaries(self) -> None:
        source_path = (
            Path(__file__).parents[2]
            / "runtime_server"
            / "worker_delivery_gateway.py"
        )
        source = source_path.read_text(encoding="utf-8")

        self.assertIn("from kernel_design.executable_spec.assembly import", source)
        for forbidden in (
            "executable_spec.redis_runtime",
            "KernelApplication",
            "TaskScoreBandCore",
            "WorkerScoreCore",
            "Pacer",
        ):
            self.assertNotIn(forbidden, source)


@unittest.skipUnless(TestClient is not None, "FastAPI example dependencies missing")
class WorkerDeliveryGatewayTest(unittest.TestCase):
    NOW_MILLIS = 100_000
    ENDPOINT_MANAGER_ID = "endpoint-manager-1"

    def setUp(self) -> None:
        assert create_worker_delivery_router is not None
        self.consumer = Mock(spec=WorkerCommandConsumerClient)
        self.result_commands = Mock(spec=SeedResultCommandClient)
        self.result_commands.append_seed_results.return_value = 1
        app = FastAPI()

        @app.exception_handler(ValueError)
        async def invalid_contract_value(
            _request: Request,
            error: ValueError,
        ) -> JSONResponse:
            return JSONResponse({"detail": str(error)}, status_code=422)

        app.include_router(
            create_worker_delivery_router(
                worker_command_consumer=self.consumer,
                seed_result_commands=self.result_commands,
            )
        )
        self.client = TestClient(app)

    @staticmethod
    def command(
        *,
        command_id: str = "a5e9e10d-f78b-469e-93ab-864b49c189c1",
        execute_before_millis: int = 105_000,
        worker_id: str = "worker-1",
    ) -> WorkerCommandEnvelope:
        return WorkerCommandEnvelope(
            command_id=command_id,
            message_type=WorkerMessageType.TASK_ITEM,
            execute_before_millis=execute_before_millis,
            opaque_item=encode_deliver_seed(
                DeliverSeed(
                    worker_id=worker_id,
                    opaque_delivery_item=(
                        '{"eventCode":"event-1","payload":{"value":1}}'
                    ),
                    opaque_result_context="opaque-context",
                )
            ),
        )

    def point_path(self, action: str, *, worker_id: str = "worker-1") -> str:
        return (
            "/worker-delivery/endpoint-managers/"
            f"{self.ENDPOINT_MANAGER_ID}/workers/{worker_id}/{action}"
        )

    def batch_path(self, action: str) -> str:
        return (
            "/worker-delivery/endpoint-managers/"
            f"{self.ENDPOINT_MANAGER_ID}/{action}"
        )

    def test_poll_returns_204_when_worker_mailbox_is_empty(self) -> None:
        self.consumer.consume_worker_command.return_value = None

        response = self.client.post(self.point_path("commands:poll"))

        self.assertEqual(204, response.status_code)
        self.consumer.consume_worker_command.assert_called_once_with(
            endpoint_manager_id=self.ENDPOINT_MANAGER_ID,
            worker_id="worker-1",
        )
        self.result_commands.append_seed_results.assert_not_called()

    def test_poll_forwards_kernel_command_envelope_unchanged(self) -> None:
        command = self.command()
        self.consumer.consume_worker_command.return_value = command

        with patch(
            "kernel_design.runtime_server.worker_delivery_gateway._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post(self.point_path("commands:poll"))

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

    def test_expired_point_command_is_dropped_without_result_evidence(
        self,
    ) -> None:
        self.consumer.consume_worker_command.return_value = self.command(
            execute_before_millis=self.NOW_MILLIS
        )

        with patch(
            "kernel_design.runtime_server.worker_delivery_gateway._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post(self.point_path("commands:poll"))

        self.assertEqual(204, response.status_code)
        self.result_commands.append_seed_results.assert_not_called()

    def test_batch_consume_preserves_worker_demux_and_cursor(self) -> None:
        active = self.command()
        expired = self.command(
            command_id="9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
            execute_before_millis=self.NOW_MILLIS,
            worker_id="worker-2",
        )
        self.consumer.consume_worker_commands.return_value = (
            WorkerCommandConsumePage(
                {"worker-1": active, "worker-2": expired},
                "7",
            )
        )

        with patch(
            "kernel_design.runtime_server.worker_delivery_gateway._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post(
                self.batch_path("commands:consume"),
                json={"cursor": None, "scanCount": 100},
            )

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {
                "workerCommandsByWorkerId": {
                    "worker-1": {
                        "commandId": active.command_id,
                        "executeBeforeMillis": active.execute_before_millis,
                        "messageType": "TASK_ITEM",
                        "opaqueItem": active.opaque_item,
                    }
                },
                "nextCursor": "7",
            },
            response.json(),
        )
        self.consumer.consume_worker_commands.assert_called_once_with(
            endpoint_manager_id=self.ENDPOINT_MANAGER_ID,
            cursor=None,
            scan_count=100,
        )

    def test_worker_results_accept_only_success_or_worker_failure(self) -> None:
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
                    self.point_path("results"),
                    json=self.result_payload(result),
                )

                self.assertEqual(202, response.status_code)
                self.assertEqual({"accepted": True}, response.json())
                submitted = (
                    self.result_commands.append_seed_results.call_args.kwargs[
                        "results"
                    ][0]
                )
                self.assertEqual(result, submitted)

        adapter_rejection = SeedResult(
            "66f60ac8-e68f-4783-90e3-13b20a54ca13",
            "adapter-context",
            "3001",
        )
        self.result_commands.reset_mock()
        response = self.client.post(
            self.point_path("results"),
            json=self.result_payload(adapter_rejection),
        )
        self.assertEqual(422, response.status_code)
        self.result_commands.append_seed_results.assert_not_called()

    def test_adapter_batch_appends_mixed_outcome_classes_once(self) -> None:
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
            SeedResult(
                "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                "adapter-context",
                "3001",
            ),
        )
        self.result_commands.append_seed_results.return_value = len(results)

        response = self.client.post(
            self.batch_path("results:append"),
            json={"results": [self.result_payload(result) for result in results]},
        )

        self.assertEqual(202, response.status_code)
        self.assertEqual({"acceptedCount": 3}, response.json())
        self.result_commands.append_seed_results.assert_called_once_with(
            results=results
        )

    def test_invalid_or_partially_accepted_batches_are_rejected(self) -> None:
        empty_response = self.client.post(
            self.batch_path("results:append"),
            json={"results": []},
        )
        self.assertEqual(422, empty_response.status_code)
        self.result_commands.append_seed_results.assert_not_called()

        result = SeedResult(
            "a5e9e10d-f78b-469e-93ab-864b49c189c1",
            "success-context",
            "200",
            "null",
        )
        self.result_commands.append_seed_results.return_value = 0
        partial_response = self.client.post(
            self.batch_path("results:append"),
            json={"results": [self.result_payload(result)]},
        )
        self.assertEqual(503, partial_response.status_code)

    def test_invalid_point_and_batch_contract_values_return_422(self) -> None:
        invalid_result = {
            "commandId": "not-a-uuid",
            "opaqueResultContext": "context",
            "outcomeCode": "200",
            "opaqueResultPayload": "null",
        }
        self.assertEqual(
            422,
            self.client.post(
                self.point_path("results"),
                json=invalid_result,
            ).status_code,
        )
        polling_batch_path = (
            "/worker-delivery/endpoint-managers/"
            f"{SYSTEM_POLLING_ENDPOINT_MANAGER_ID}/commands:consume"
        )
        self.assertEqual(
            422,
            self.client.post(
                polling_batch_path,
                json={"cursor": None, "scanCount": 1},
            ).status_code,
        )
        self.assertEqual(
            422,
            self.client.post(
                "/worker-delivery/endpoint-managers/"
                f"{SYSTEM_POLLING_ENDPOINT_MANAGER_ID}/results:append",
                json={
                    "results": [
                        {
                            "commandId": (
                                "a5e9e10d-f78b-469e-93ab-864b49c189c1"
                            ),
                            "opaqueResultContext": "context",
                            "outcomeCode": "200",
                            "opaqueResultPayload": "null",
                        }
                    ]
                },
            ).status_code,
        )
        self.assertEqual(
            422,
            self.client.post(
                self.batch_path("commands:consume"),
                json={"cursor": "not-a-cursor", "scanCount": 1},
            ).status_code,
        )
        self.assertEqual(
            422,
            self.client.post(
                self.batch_path("commands:consume"),
                json={"cursor": None, "scanCount": 0},
            ).status_code,
        )

    @staticmethod
    def result_payload(result: SeedResult) -> dict[str, object]:
        return {
            "commandId": result.command_id,
            "opaqueResultContext": result.opaque_result_context,
            "outcomeCode": result.outcome_code,
            "opaqueResultPayload": result.opaque_result_payload,
        }


if __name__ == "__main__":
    unittest.main()
