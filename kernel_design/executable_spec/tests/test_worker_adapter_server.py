from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from uuid import UUID

from kernel_design.executable_spec.assembly import (
    DeliverSeed,
    DeliverSeedConsumerClient,
    SeedResultCommandClient,
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
        self.consumer = Mock(spec=DeliverSeedConsumerClient)
        self.result_commands = Mock(spec=SeedResultCommandClient)
        self.result_commands.append_seed_results.return_value = 1
        self.client = TestClient(
            create_app(
                deliver_seed_consumer=self.consumer,
                seed_result_commands=self.result_commands,
            )
        )

    @staticmethod
    def seed(*, claim_until_millis: int = 105_000) -> DeliverSeed:
        return DeliverSeed(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"event-1","payload":{"value":1}}',
            opaque_result_context="opaque-context",
            task_item_claim_until_millis=claim_until_millis,
        )

    def test_health_does_not_require_kernel_lifecycle(self) -> None:
        self.assertEqual({"status": "ok"}, self.client.get("/health").json())
        for boundary in (self.consumer, self.result_commands):
            self.assertFalse(hasattr(boundary, "start"))
            self.assertFalse(hasattr(boundary, "stop"))

    def test_poll_returns_204_when_worker_mailbox_is_empty(self) -> None:
        self.consumer.consume_deliver_seeds.return_value = {}

        response = self.client.post("/workers/worker-1/commands:poll")

        self.assertEqual(204, response.status_code)
        self.consumer.consume_deliver_seeds.assert_called_once_with(
            worker_ids=("worker-1",),
        )
        self.result_commands.append_seed_results.assert_not_called()

    def test_poll_returns_private_command_envelope(self) -> None:
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed()
        }

        with patch(
            "kernel_design.examples.worker_adapter_server._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post("/workers/worker-1/commands:poll")

        self.assertEqual(200, response.status_code)
        payload = response.json()
        UUID(payload.pop("commandId"))
        self.assertEqual(
            {
                "messageType": "TASK_SEED",
                "opaqueDeliveryItem": (
                    '{"eventCode":"event-1","payload":{"value":1}}'
                ),
                "opaqueResultContext": "opaque-context",
                "taskItemClaimUntilMillis": 105_000,
            },
            payload,
        )

    def test_expired_seed_is_dropped_without_result_evidence(self) -> None:
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed(claim_until_millis=self.NOW_MILLIS)
        }

        with patch(
            "kernel_design.examples.worker_adapter_server._current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            response = self.client.post("/workers/worker-1/commands:poll")

        self.assertEqual(204, response.status_code)
        self.result_commands.append_seed_results.assert_not_called()

    def test_worker_success_and_failure_are_forwarded_as_seed_results(self) -> None:
        requests = (
            {
                "commandId": "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                "messageType": "TASK_SEED_RESULT",
                "opaqueResultContext": "success-context",
                "outcomeCode": "200",
                "opaqueResultPayload": "null",
            },
            {
                "commandId": "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "messageType": "TASK_SEED_RESULT",
                "opaqueResultContext": "failure-context",
                "outcomeCode": "1500",
            },
        )

        for request in requests:
            with self.subTest(outcome=request["outcomeCode"]):
                self.result_commands.reset_mock()
                self.result_commands.append_seed_results.return_value = 1

                response = self.client.post(
                    "/workers/worker-1/results",
                    json=request,
                )

                self.assertEqual(202, response.status_code)
                self.assertEqual({"accepted": True}, response.json())
                result = self.result_commands.append_seed_results.call_args.kwargs[
                    "results"
                ][0]
                self.assertEqual(
                    request["opaqueResultContext"],
                    result.opaque_result_context,
                )
                self.assertEqual(request["outcomeCode"], result.outcome_code)
                self.assertEqual(
                    request.get("opaqueResultPayload"),
                    result.opaque_result_payload,
                )

    def test_worker_result_contract_rejects_adapter_and_invalid_envelopes(
        self,
    ) -> None:
        base_request = {
            "commandId": "a5e9e10d-f78b-469e-93ab-864b49c189c1",
            "messageType": "TASK_SEED_RESULT",
            "opaqueResultContext": "context",
            "outcomeCode": "200",
            "opaqueResultPayload": "null",
        }
        invalid_requests = (
            {**base_request, "outcomeCode": "3001", "opaqueResultPayload": None},
            {**base_request, "messageType": "TASK_SEED"},
            {**base_request, "opaqueResultPayload": None},
            {**base_request, "commandId": "not-a-uuid"},
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
                "messageType": "TASK_SEED_RESULT",
                "opaqueResultContext": "context",
                "outcomeCode": "200",
                "opaqueResultPayload": "null",
            },
        )

        self.assertEqual(503, response.status_code)

    def test_injected_boundaries_must_be_supplied_together(self) -> None:
        assert create_app is not None
        with self.assertRaisesRegex(ValueError, "injected together"):
            create_app(deliver_seed_consumer=self.consumer)
        with self.assertRaisesRegex(ValueError, "injected together"):
            create_app(seed_result_commands=self.result_commands)


if __name__ == "__main__":
    unittest.main()
