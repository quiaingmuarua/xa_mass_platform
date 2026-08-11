from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    RedisWorkerResultRuntime,
    DeliveryEndpoint,
    DeliveryReport,
    DeliveryReportOutcomeClass,
    classify_delivery_report_outcome_code,
    encode_delivery_report,
)


class FakeRedis:
    def __init__(self) -> None:
        self.lists: dict[str, list[str]] = {}
        self.pipeline_transaction_flags: list[bool] = []

    def rpush(self, key: str, *values: str) -> int:
        row = self.lists.setdefault(key, [])
        row.extend(values)
        return len(row)

    def lpop(self, key: str) -> str | None:
        row = self.lists.get(key, [])
        return row.pop(0) if row else None

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        self.pipeline_transaction_flags.append(transaction)
        return FakePipeline(self)


class FakePipeline:
    def __init__(self, redis: FakeRedis) -> None:
        self.redis = redis
        self.commands: list[tuple[str, str, tuple[str, ...]]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> None:
        pass

    def rpush(self, key: str, *values: str) -> FakePipeline:
        self.commands.append(("rpush", key, values))
        return self

    def lpop(self, key: str) -> FakePipeline:
        self.commands.append(("lpop", key, ()))
        return self

    def execute(self) -> list[int | str | None]:
        results: list[int | str | None] = []
        for operation, key, values in self.commands:
            if operation == "rpush":
                results.append(self.redis.rpush(key, *values))
            else:
                results.append(self.redis.lpop(key))
        return results


class RedisWorkerResultRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisWorkerResultRuntime(self.redis, prefix="test")

    def key(self, outcome_class: DeliveryReportOutcomeClass) -> str:
        return self.runtime._queue_key(outcome_class)

    @staticmethod
    def result(
        context: str,
        outcome_code: str,
        payload: str = "null",
    ) -> DeliveryReport:
        source = (
            DeliveryEndpoint.ADAPTER
            if (
                isinstance(outcome_code, str)
                and outcome_code != "200"
                and outcome_code.startswith("2")
            )
            else DeliveryEndpoint.WORKER
        )
        return DeliveryReport.create(
            src=source,
            source_id=("endpoint-manager-1" if source is DeliveryEndpoint.ADAPTER else "worker-1"),
            dst=DeliveryEndpoint.TASK,
            message_type="test.observe",
            outcome_code=outcome_code,
            payload=payload,
            forward=context,
        )

    def test_mixed_append_partitions_three_fifo_queues(self) -> None:
        success = self.result("context-success", "200", '{"value":1}')
        worker_failure = self.result("context-worker", "3303")
        adapter_rejection = self.result("context-adapter", "23002")

        self.assertEqual(
            3,
            self.runtime.append_worker_results(
                results=(success, worker_failure, adapter_rejection),
            ),
        )
        self.assertTrue(
            all(":worker-results:" in key for key in self.redis.lists)
        )
        for outcome_class, expected in (
            (DeliveryReportOutcomeClass.SUCCESS, success),
            (DeliveryReportOutcomeClass.WORKER_FAILURE, worker_failure),
            (DeliveryReportOutcomeClass.ADAPTER_REJECTION, adapter_rejection),
        ):
            self.assertEqual(
                (expected,),
                self.runtime.consume_worker_results(
                    outcome_class=outcome_class,
                    limit=1,
                ),
            )

    def test_each_class_is_independently_bounded_and_fifo(self) -> None:
        first = self.result("context-1", "3301")
        second = self.result("context-2", "3302")
        self.runtime.append_worker_results(results=(first, second))

        self.assertEqual(
            (first,),
            self.runtime.consume_worker_results(
                outcome_class=DeliveryReportOutcomeClass.WORKER_FAILURE,
                limit=1,
            ),
        )
        self.assertEqual(
            (second,),
            self.runtime.consume_worker_results(
                outcome_class=DeliveryReportOutcomeClass.WORKER_FAILURE,
                limit=10,
            ),
        )

    def test_corrupt_messages_are_consumed_and_skipped(self) -> None:
        valid = self.result("context-1", "200")
        key = self.key(DeliveryReportOutcomeClass.SUCCESS)
        self.redis.lists[key] = [
            "{bad-json",
            '{"outcomeCode":"200"}',
            encode_delivery_report(valid),
        ]

        self.assertEqual(
            (valid,),
            self.runtime.consume_worker_results(
                outcome_class=DeliveryReportOutcomeClass.SUCCESS,
                limit=3,
            ),
        )
        self.assertEqual([], self.redis.lists[key])

    def test_outcome_protocol_uses_owner_prefix_without_width_validation(
        self,
    ) -> None:
        self.assertIs(
            DeliveryReportOutcomeClass.SUCCESS,
            classify_delivery_report_outcome_code("200"),
        )
        self.assertIs(
            DeliveryReportOutcomeClass.WORKER_FAILURE,
            classify_delivery_report_outcome_code("33001"),
        )
        self.assertIs(
            DeliveryReportOutcomeClass.WORKER_FAILURE,
            classify_delivery_report_outcome_code("3304"),
        )
        self.assertIs(
            DeliveryReportOutcomeClass.ADAPTER_REJECTION,
            classify_delivery_report_outcome_code("23001"),
        )
        self.assertIs(
            DeliveryReportOutcomeClass.ADAPTER_REJECTION,
            classify_delivery_report_outcome_code("failure"),
        )
        for invalid in ("", " ", 200, None):
            with self.subTest(invalid=invalid):
                self.assertIsNone(
                    classify_delivery_report_outcome_code(invalid)
                )
                with self.assertRaises(ValueError):
                    self.result("context", invalid)


if __name__ == "__main__":
    unittest.main()
