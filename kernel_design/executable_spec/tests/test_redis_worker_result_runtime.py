from __future__ import annotations

import unittest
from uuid import NAMESPACE_DNS, uuid5

from kernel_design.executable_spec import (
    RedisWorkerResultRuntime,
    WorkerMessageEndpoint,
    WorkerResult,
    WorkerResultOutcomeClass,
    classify_worker_result_outcome_code,
    encode_worker_result,
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

    def key(self, outcome_class: WorkerResultOutcomeClass) -> str:
        return self.runtime._queue_key(outcome_class)

    @staticmethod
    def result(
        context: str,
        outcome_code: str,
        payload: str = "null",
    ) -> WorkerResult:
        return WorkerResult(
            message_id=str(uuid5(NAMESPACE_DNS, context)),
            dst=WorkerMessageEndpoint.TASK,
            message_type="test.observe",
            outcome_code=outcome_code,
            payload=payload,
            forward=context,
        )

    def test_mixed_append_partitions_three_fifo_queues(self) -> None:
        success = self.result("context-success", "200", '{"value":1}')
        worker_failure = self.result("context-worker", "1000")
        adapter_rejection = self.result("context-adapter", "3001")

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
            (WorkerResultOutcomeClass.SUCCESS, success),
            (WorkerResultOutcomeClass.WORKER_FAILURE, worker_failure),
            (WorkerResultOutcomeClass.ADAPTER_REJECTION, adapter_rejection),
        ):
            self.assertEqual(
                (expected,),
                self.runtime.consume_worker_results(
                    outcome_class=outcome_class,
                    limit=1,
                ),
            )

    def test_each_class_is_independently_bounded_and_fifo(self) -> None:
        first = self.result("context-1", "1000")
        second = self.result("context-2", "1001")
        self.runtime.append_worker_results(results=(first, second))

        self.assertEqual(
            (first,),
            self.runtime.consume_worker_results(
                outcome_class=WorkerResultOutcomeClass.WORKER_FAILURE,
                limit=1,
            ),
        )
        self.assertEqual(
            (second,),
            self.runtime.consume_worker_results(
                outcome_class=WorkerResultOutcomeClass.WORKER_FAILURE,
                limit=10,
            ),
        )

    def test_corrupt_messages_are_consumed_and_skipped(self) -> None:
        valid = self.result("context-1", "200")
        key = self.key(WorkerResultOutcomeClass.SUCCESS)
        self.redis.lists[key] = [
            "{bad-json",
            '{"outcomeCode":"200"}',
            encode_worker_result(valid),
        ]

        self.assertEqual(
            (valid,),
            self.runtime.consume_worker_results(
                outcome_class=WorkerResultOutcomeClass.SUCCESS,
                limit=3,
            ),
        )
        self.assertEqual([], self.redis.lists[key])

    def test_outcome_protocol_is_exact(self) -> None:
        self.assertIs(
            WorkerResultOutcomeClass.SUCCESS,
            classify_worker_result_outcome_code("200"),
        )
        self.assertIs(
            WorkerResultOutcomeClass.WORKER_FAILURE,
            classify_worker_result_outcome_code("1000"),
        )
        self.assertIs(
            WorkerResultOutcomeClass.ADAPTER_REJECTION,
            classify_worker_result_outcome_code("3999"),
        )
        for invalid in ("", "500", "2000", "300", "30000", "失败"):
            with self.subTest(invalid=invalid):
                self.assertIsNone(
                    classify_worker_result_outcome_code(invalid)
                )
                with self.assertRaises(ValueError):
                    self.result("context", invalid)


if __name__ == "__main__":
    unittest.main()
