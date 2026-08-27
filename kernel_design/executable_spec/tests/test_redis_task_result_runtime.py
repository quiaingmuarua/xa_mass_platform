from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
    RedisKeyspace,
    RedisTaskResultRuntime,
    TaskResultClass,
    encode_delivery_report,
)


class FakeRedis:
    def __init__(self) -> None:
        self.lists: dict[str, list[str]] = {}

    def rpush(self, key: str, *values: str) -> int:
        row = self.lists.setdefault(key, [])
        row.extend(values)
        return len(row)

    def lpop(self, key: str, count: int) -> list[str] | None:
        row = self.lists.get(key, [])
        if not row:
            return None
        values = row[:count]
        del row[:count]
        return values


class RedisTaskResultRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisTaskResultRuntime(
            self.redis,
            keyspace=RedisKeyspace("test_task_result_unit"),
        )

    def key(self, result_class: TaskResultClass) -> str:
        return self.runtime._queue_key(result_class)

    @staticmethod
    def result(
        context: str,
        outcome_code: str,
        payload: str = "null",
    ) -> DeliveryReport:
        return DeliveryReport.create(
            src=DeliveryEndpoint.WORKER,
            source_id="worker-1",
            dst=DeliveryEndpoint.TASK,
            message_type="test.observe",
            outcome_code=outcome_code,
            payload=payload,
            forward=context,
        )

    def test_explicit_lanes_are_independent_and_do_not_classify_outcome_code(
        self,
    ) -> None:
        success_lane_failure_code = self.result(
            "success-context",
            "3500",
            '{"value":1}',
        )
        failure_lane_success_code = self.result("failure-context", "200")

        self.assertEqual(
            1,
            self.runtime.append_task_results(
                result_class=TaskResultClass.SUCCESS,
                results=(success_lane_failure_code,),
            ),
        )
        self.assertEqual(
            1,
            self.runtime.append_task_results(
                result_class=TaskResultClass.FAILURE,
                results=(failure_lane_success_code,),
            ),
        )
        self.assertEqual(
            (success_lane_failure_code,),
            self.runtime.consume_task_results(
                result_class=TaskResultClass.SUCCESS,
                limit=10,
            ),
        )
        self.assertEqual(
            (failure_lane_success_code,),
            self.runtime.consume_task_results(
                result_class=TaskResultClass.FAILURE,
                limit=10,
            ),
        )
        self.assertEqual(
            {
                "xa_mass:test_task_result_unit:result:routing:success",
                "xa_mass:test_task_result_unit:result:routing:failure",
            },
            set(self.redis.lists),
        )

    def test_each_lane_is_independently_bounded_and_fifo(self) -> None:
        first = self.result("context-1", "3301")
        second = self.result("context-2", "3302")
        self.runtime.append_task_results(
            result_class=TaskResultClass.FAILURE,
            results=(first, second),
        )

        self.assertEqual(
            (first,),
            self.runtime.consume_task_results(
                result_class=TaskResultClass.FAILURE,
                limit=1,
            ),
        )
        self.assertEqual(
            (second,),
            self.runtime.consume_task_results(
                result_class=TaskResultClass.FAILURE,
                limit=10,
            ),
        )

    def test_corrupt_messages_are_consumed_and_skipped(self) -> None:
        valid = self.result("context-1", "200")
        key = self.key(TaskResultClass.SUCCESS)
        self.redis.lists[key] = [
            "{bad-json",
            '{"outcomeCode":"200"}',
            encode_delivery_report(valid),
        ]

        self.assertEqual(
            (valid,),
            self.runtime.consume_task_results(
                result_class=TaskResultClass.SUCCESS,
                limit=3,
            ),
        )
        self.assertEqual([], self.redis.lists[key])

    def test_lane_and_bounds_are_validated(self) -> None:
        result = self.result("context", "200")
        with self.assertRaises(TypeError):
            self.runtime.append_task_results(
                result_class="SUCCESS",  # type: ignore[arg-type]
                results=(result,),
            )
        with self.assertRaises(TypeError):
            self.runtime.append_task_results(
                result_class=TaskResultClass.SUCCESS,
                results=(object(),),  # type: ignore[arg-type]
            )
        with self.assertRaises(ValueError):
            self.runtime.consume_task_results(
                result_class=TaskResultClass.SUCCESS,
                limit=0,
            )


if __name__ == "__main__":
    unittest.main()
