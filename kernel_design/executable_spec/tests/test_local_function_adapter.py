from __future__ import annotations

import unittest
from unittest.mock import Mock, call, patch

from kernel_design.examples.local_function_adapter import (
    EventHandlerResult,
    LocalFunctionTransportAdapter,
    WorkerMeta,
)
from kernel_design.executable_spec.assembly import (
    DeliverSeed,
    DeliverSeedConsumerClient,
    SeedResultCommandClient,
)


class LocalFunctionTransportAdapterTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.consumer = Mock(spec=DeliverSeedConsumerClient)
        self.result_commands = Mock(spec=SeedResultCommandClient)
        self.result_commands.append_seed_results.return_value = 1
        self.adapter = LocalFunctionTransportAdapter(
            deliver_seed_consumer=self.consumer,
            seed_result_commands=self.result_commands,
        )
        self.adapter.register_worker(
            "worker-1",
            WorkerMeta({"local": "value"}),
        )

    @staticmethod
    def seed(
        *,
        worker_id: str = "worker-1",
        delivery_item: str | None = None,
        claim_until_millis: int = 105_000,
        result_context: str = "opaque-context",
    ) -> DeliverSeed:
        return DeliverSeed(
            worker_id=worker_id,
            opaque_delivery_item=delivery_item
            or '{"eventCode":"event-1","payload":{"value":1}}',
            opaque_result_context=result_context,
            task_item_claim_until_millis=claim_until_millis,
        )

    def drain(self) -> int:
        with patch.object(
            LocalFunctionTransportAdapter,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            return self.adapter.drain_once(limit=10)

    def test_handler_success_appends_one_deterministic_result_batch(self) -> None:
        handler = Mock(
            return_value=EventHandlerResult(
                outcome_code="200",
                payload={"z": 2, "a": 1},
            )
        )
        self.adapter.register_event_handler("event-1", handler)
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed()
        }

        self.assertEqual(1, self.drain())

        handler.assert_called_once()
        payload, worker_meta = handler.call_args.args
        self.assertEqual({"value": 1}, payload)
        self.assertEqual("value", worker_meta.attributes["local"])
        result = self.result_commands.append_seed_results.call_args.kwargs[
            "results"
        ][0]
        self.assertEqual("opaque-context", result.opaque_result_context)
        self.assertEqual("200", result.outcome_code)
        self.assertEqual('{"a":1,"z":2}', result.opaque_result_payload)

    def test_worker_failure_code_is_forwarded_without_subcode_parsing(self) -> None:
        self.adapter.register_event_handler(
            "event-1",
            lambda _payload, _worker: EventHandlerResult("1409"),
        )
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed()
        }

        self.assertEqual(1, self.drain())

        result = self.result_commands.append_seed_results.call_args.kwargs[
            "results"
        ][0]
        self.assertEqual("1409", result.outcome_code)

    def test_handler_or_result_encoding_error_becomes_1500(self) -> None:
        for handler in (
            lambda _payload, _worker: (_ for _ in ()).throw(RuntimeError("boom")),
            lambda _payload, _worker: EventHandlerResult(
                "200",
                {"bad": object()},
            ),
        ):
            with self.subTest(handler=handler):
                self.result_commands.reset_mock()
                self.result_commands.append_seed_results.return_value = 1
                self.adapter.register_event_handler("event-1", handler)
                self.consumer.consume_deliver_seeds.return_value = {
                    "worker-1": self.seed()
                }

                self.assertEqual(1, self.drain())

                result = self.result_commands.append_seed_results.call_args.kwargs[
                    "results"
                ][0]
                self.assertEqual("1500", result.outcome_code)
                self.assertIsNone(result.opaque_result_payload)

    def test_expired_corrupt_and_missing_handler_are_bounded(self) -> None:
        self.adapter.register_worker("worker-2", WorkerMeta({}))
        self.adapter.register_worker("worker-3", WorkerMeta({}))
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed(claim_until_millis=self.NOW_MILLIS),
            "worker-2": self.seed(
                worker_id="worker-2",
                delivery_item='{"eventCode":"missing","payload":{}}',
            ),
            "worker-3": self.seed(
                worker_id="worker-3",
                delivery_item="{bad-json",
            ),
        }

        self.assertEqual(1, self.drain())

        results = self.result_commands.append_seed_results.call_args.kwargs["results"]
        self.assertEqual(["1404"], [result.outcome_code for result in results])

    def test_unregister_worker_is_idempotent_and_unregistered_mailbox_is_not_read(self) -> None:
        self.adapter.unregister_worker("worker-1")
        self.adapter.unregister_worker("worker-1")

        self.assertEqual(0, self.drain())

        self.consumer.consume_deliver_seeds.assert_not_called()
        self.result_commands.append_seed_results.assert_not_called()

    def test_worker_removed_after_consume_reports_unavailable(self) -> None:
        def consume(**_: object):
            self.adapter.unregister_worker("worker-1")
            return {"worker-1": self.seed()}

        self.consumer.consume_deliver_seeds.side_effect = consume

        self.assertEqual(1, self.drain())

        result = self.result_commands.append_seed_results.call_args.kwargs[
            "results"
        ][0]
        self.assertEqual("3001", result.outcome_code)

    def test_one_drain_uses_one_consume_and_one_append(self) -> None:
        self.adapter.register_event_handler(
            "event-1",
            lambda _payload, _worker: EventHandlerResult("200"),
        )
        self.adapter.register_worker("worker-2", WorkerMeta({}))
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed(result_context="context-1"),
            "worker-2": self.seed(
                worker_id="worker-2",
                result_context="context-2",
            ),
        }
        self.result_commands.append_seed_results.return_value = 2

        self.assertEqual(2, self.drain())

        self.consumer.consume_deliver_seeds.assert_called_once_with(
            worker_ids=("worker-1", "worker-2"),
        )
        self.result_commands.append_seed_results.assert_called_once()
        results = self.result_commands.append_seed_results.call_args.kwargs["results"]
        self.assertEqual(("null", "null"), tuple(
            result.opaque_result_payload for result in results
        ))

    def test_append_error_propagates_without_adapter_compensation(self) -> None:
        self.adapter.register_event_handler(
            "event-1",
            lambda _payload, _worker: EventHandlerResult("200"),
        )
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed()
        }
        self.result_commands.append_seed_results.side_effect = RuntimeError("down")

        with self.assertRaisesRegex(RuntimeError, "down"):
            self.drain()

    def test_duplicate_registration_replaces_process_local_value(self) -> None:
        first = Mock(return_value=EventHandlerResult("1000"))
        second = Mock(return_value=EventHandlerResult("200"))
        self.adapter.register_event_handler("event-1", first)
        self.adapter.register_event_handler("event-1", second)
        self.adapter.register_worker("worker-1", WorkerMeta({"version": 2}))
        self.consumer.consume_deliver_seeds.return_value = {
            "worker-1": self.seed()
        }

        self.drain()

        first.assert_not_called()
        self.assertEqual(2, second.call_args.args[1].attributes["version"])

    def test_worker_selection_rotates_across_registered_workers(self) -> None:
        for worker_id in ("worker-2", "worker-3"):
            self.adapter.register_worker(worker_id, WorkerMeta({}))
        self.consumer.consume_deliver_seeds.return_value = {}

        self.adapter.drain_once(limit=2)
        self.adapter.drain_once(limit=2)

        self.assertEqual(
            [
                call(worker_ids=("worker-1", "worker-2")),
                call(worker_ids=("worker-3", "worker-1")),
            ],
            self.consumer.consume_deliver_seeds.call_args_list,
        )

    def test_handler_outcome_code_accepts_only_success_or_worker_failure(self) -> None:
        self.assertEqual("200", EventHandlerResult("200").outcome_code)
        self.assertEqual("1999", EventHandlerResult("1999").outcome_code)
        for invalid in ("", "500", "3001", "１２３４", 200, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                EventHandlerResult(invalid)  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
