from __future__ import annotations

import unittest
from unittest.mock import Mock, call, patch
from uuid import NAMESPACE_DNS, uuid5

from kernel_design.executable_spec.test_support import (
    EventHandlerResult,
    LocalFunctionTransportAdapter,
    WorkerMeta,
)
from kernel_design.executable_spec.assembly import (
    WorkerCommandConsumerClient,
    WorkerCommand,
    WorkerMessageEndpoint,
    WorkerResultCommandClient,
)


class LocalFunctionTransportAdapterTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.consumer = Mock(spec=WorkerCommandConsumerClient)
        self.result_commands = Mock(spec=WorkerResultCommandClient)
        self.result_commands.append_worker_results.return_value = 1
        self.adapter = LocalFunctionTransportAdapter(
            endpoint_manager_id="endpoint-manager-1",
            worker_command_consumer=self.consumer,
            worker_result_commands=self.result_commands,
        )
        self.adapter.register_worker(
            "worker-1",
            WorkerMeta({"local": "value"}),
        )

    @staticmethod
    def command(
        *,
        worker_id: str = "worker-1",
        event_code: str = "event-1",
        payload: str = '{"value":1}',
        execute_before_millis: int = 105_000,
        result_context: str = "opaque-context",
    ) -> dict[str, WorkerCommand]:
        return {
            worker_id: WorkerCommand(
                message_id=str(
                    uuid5(NAMESPACE_DNS, worker_id + result_context)
                ),
                src=WorkerMessageEndpoint.TASK,
                dst=WorkerMessageEndpoint.WORKER,
                message_type=event_code,
                execute_before_millis=execute_before_millis,
                payload=payload,
                forward=result_context,
            )
        }

    def drain(self) -> int:
        with patch.object(
            LocalFunctionTransportAdapter,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            return self.adapter.drain_once(limit=10)

    @staticmethod
    def batch(
        *commands: dict[str, WorkerCommand],
    ) -> dict[str, WorkerCommand]:
        worker_commands = {}
        for command_batch in commands:
            worker_commands.update(command_batch)
        return worker_commands

    def test_handler_success_appends_one_deterministic_result_batch(self) -> None:
        handler = Mock(
            return_value=EventHandlerResult(
                outcome_code="200",
                payload={"z": 2, "a": 1},
            )
        )
        self.adapter.register_event_handler("event-1", handler)
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command()
        )

        self.assertEqual(1, self.drain())

        handler.assert_called_once()
        payload, worker_meta = handler.call_args.args
        self.assertEqual({"value": 1}, payload)
        self.assertEqual("value", worker_meta.attributes["local"])
        result = self.result_commands.append_worker_results.call_args.kwargs[
            "results"
        ][0]
        self.assertEqual("opaque-context", result.forward)
        self.assertEqual(
            self.command()["worker-1"].message_id,
            result.message_id,
        )
        self.assertEqual("200", result.outcome_code)
        self.assertEqual('{"a":1,"z":2}', result.payload)

    def test_worker_failure_code_is_forwarded_without_subcode_parsing(self) -> None:
        self.adapter.register_event_handler(
            "event-1",
            lambda _payload, _worker: EventHandlerResult("1409"),
        )
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command()
        )

        self.assertEqual(1, self.drain())

        result = self.result_commands.append_worker_results.call_args.kwargs[
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
                self.result_commands.append_worker_results.return_value = 1
                self.adapter.register_event_handler("event-1", handler)
                self.consumer.consume_worker_commands.return_value = self.batch(
                    self.command()
                )

                self.assertEqual(1, self.drain())

                result = self.result_commands.append_worker_results.call_args.kwargs[
                    "results"
                ][0]
                self.assertEqual("1500", result.outcome_code)
                self.assertEqual("null", result.payload)

    def test_expired_corrupt_and_missing_handler_are_bounded(self) -> None:
        self.adapter.register_worker("worker-2", WorkerMeta({}))
        self.adapter.register_worker("worker-3", WorkerMeta({}))
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command(execute_before_millis=self.NOW_MILLIS),
            self.command(
                worker_id="worker-2",
                event_code="missing",
                payload="{}",
            ),
            self.command(
                worker_id="worker-3",
                payload="{bad-json",
            ),
        )

        self.assertEqual(1, self.drain())

        results = self.result_commands.append_worker_results.call_args.kwargs[
            "results"
        ]
        self.assertEqual(
            ["1404"],
            [result.outcome_code for result in results],
        )

    def test_unregister_worker_is_idempotent_and_leaves_unknown_result(self) -> None:
        self.adapter.unregister_worker("worker-1")
        self.adapter.unregister_worker("worker-1")
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command()
        )

        self.assertEqual(0, self.drain())

        self.result_commands.append_worker_results.assert_not_called()

    def test_worker_removed_after_consume_leaves_unknown_result(self) -> None:
        def consume(**_: object):
            self.adapter.unregister_worker("worker-1")
            return self.batch(self.command())

        self.consumer.consume_worker_commands.side_effect = consume

        self.assertEqual(0, self.drain())

        self.result_commands.append_worker_results.assert_not_called()

    def test_one_drain_uses_one_consume_and_one_append(self) -> None:
        self.adapter.register_event_handler(
            "event-1",
            lambda _payload, _worker: EventHandlerResult("200"),
        )
        self.adapter.register_worker("worker-2", WorkerMeta({}))
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command(result_context="context-1"),
            self.command(
                worker_id="worker-2",
                result_context="context-2",
            ),
        )
        self.result_commands.append_worker_results.return_value = 2

        self.assertEqual(2, self.drain())

        self.consumer.consume_worker_commands.assert_called_once_with(
            endpoint_manager_id="endpoint-manager-1",
            limit=10,
        )
        self.result_commands.append_worker_results.assert_called_once()
        results = self.result_commands.append_worker_results.call_args.kwargs[
            "results"
        ]
        self.assertEqual(
            ("null", "null"),
            tuple(result.payload for result in results),
        )

    def test_append_error_propagates_without_adapter_compensation(self) -> None:
        self.adapter.register_event_handler(
            "event-1",
            lambda _payload, _worker: EventHandlerResult("200"),
        )
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command()
        )
        self.result_commands.append_worker_results.side_effect = RuntimeError("down")

        with self.assertRaisesRegex(RuntimeError, "down"):
            self.drain()

    def test_duplicate_registration_replaces_process_local_value(self) -> None:
        first = Mock(return_value=EventHandlerResult("1000"))
        second = Mock(return_value=EventHandlerResult("200"))
        self.adapter.register_event_handler("event-1", first)
        self.adapter.register_event_handler("event-1", second)
        self.adapter.register_worker("worker-1", WorkerMeta({"version": 2}))
        self.consumer.consume_worker_commands.return_value = self.batch(
            self.command()
        )

        self.drain()

        first.assert_not_called()
        self.assertEqual(2, second.call_args.args[1].attributes["version"])

    def test_each_drain_requests_one_independent_bounded_batch(self) -> None:
        self.consumer.consume_worker_commands.side_effect = (
            self.batch(),
            self.batch(),
        )

        self.adapter.drain_once(limit=2)
        self.adapter.drain_once(limit=2)

        self.assertEqual(
            [
                call(
                    endpoint_manager_id="endpoint-manager-1",
                    limit=2,
                ),
                call(
                    endpoint_manager_id="endpoint-manager-1",
                    limit=2,
                ),
            ],
            self.consumer.consume_worker_commands.call_args_list,
        )

    def test_handler_outcome_code_accepts_only_success_or_worker_failure(self) -> None:
        self.assertEqual("200", EventHandlerResult("200").outcome_code)
        self.assertEqual("1999", EventHandlerResult("1999").outcome_code)
        for invalid in ("", "500", "3001", "１２３４", 200, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                EventHandlerResult(invalid)  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
