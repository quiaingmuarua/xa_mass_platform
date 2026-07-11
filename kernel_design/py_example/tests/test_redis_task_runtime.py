from __future__ import annotations

import unittest
from typing import Sequence

import kernel_design.py_example as py_example
from kernel_design.py_example import (
    RedisTaskResourceCatalog,
    TaskDescriptor,
    TaskDescriptorRegistrationStatus,
)


class FakePipeline:
    def __init__(self, redis_client: FakeRedis) -> None:
        self.redis = redis_client
        self.commands: list[tuple[str, tuple[str, ...]]] = []

    def hmget(self, name: str, keys: Sequence[str]) -> FakePipeline:
        self.commands.append((name, tuple(keys)))
        return self

    def execute(self) -> list[list[str | None]]:
        self.redis.pipeline_executions.append(tuple(self.commands))
        return [self.redis.hmget(name, fields) for name, fields in self.commands]


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.eval_calls: list[tuple[int, str, tuple[object, ...]]] = []
        self.pipeline_transaction_flags: list[bool] = []
        self.pipeline_executions: list[tuple[tuple[str, tuple[str, ...]], ...]] = []

    def eval(
        self,
        script: str,
        numkeys: int,
        key: str,
        *args: object,
    ) -> int:
        self.eval_calls.append((numkeys, key, args))
        if key in self.hashes:
            return 0
        worker_group_id, allocation_rule_json, config_json = args
        self.hashes[key] = {
            "workerGroupId": str(worker_group_id),
            "allocationRuleJson": str(allocation_rule_json),
            "configJson": str(config_json),
        }
        return 1

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        self.pipeline_transaction_flags.append(transaction)
        return FakePipeline(self)

    def hmget(self, name: str, keys: Sequence[str]) -> list[str | None]:
        row = self.hashes.get(name, {})
        return [row.get(key) for key in keys]


class RedisTaskResourceCatalogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.catalog = RedisTaskResourceCatalog(self.redis, prefix="test")

    @staticmethod
    def descriptor(
        task_id: str,
        *,
        worker_group_id: str = "image-workers",
        allocation_rule: dict[str, object] | None = None,
        config: dict[str, object] | None = None,
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            allocation_rule=(
                {"dynamic.battery": {"$gte": 20}}
                if allocation_rule is None
                else allocation_rule
            ),
            config=(
                {
                    "priority": "80",
                    "runningVisibleMinimumCandidateWorkers": "10",
                }
                if config is None
                else config
            ),  # type: ignore[arg-type]
        )

    def test_register_and_load_descriptor_round_trip(self) -> None:
        descriptor = self.descriptor("task-1")

        result = self.catalog.register_task_descriptor(descriptor=descriptor)
        rows = self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])

        self.assertEqual(result.status, TaskDescriptorRegistrationStatus.REGISTERED)
        self.assertEqual(rows, {"task-1": descriptor})
        self.assertEqual(
            set(self.redis.hashes["tc:test:task:task-1"]),
            {"workerGroupId", "allocationRuleJson", "configJson"},
        )
        self.assertNotIn("taskId", self.redis.hashes["tc:test:task:task-1"])
        self.assertEqual(self.redis.eval_calls[0][0], 1)

    def test_duplicate_registration_conflicts_without_overwrite(self) -> None:
        first = self.descriptor("task-1", worker_group_id="image-workers")
        second = self.descriptor("task-1", worker_group_id="audio-workers")
        self.catalog.register_task_descriptor(descriptor=first)

        result = self.catalog.register_task_descriptor(descriptor=second)
        loaded = self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])

        self.assertEqual(result.status, TaskDescriptorRegistrationStatus.CONFLICT)
        self.assertEqual(loaded["task-1"], first)

    def test_registration_rejects_non_json_descriptor(self) -> None:
        descriptor = self.descriptor(
            "task-1",
            allocation_rule={"dynamic.battery": {"$eq": object()}},
        )

        result = self.catalog.register_task_descriptor(descriptor=descriptor)

        self.assertEqual(result.status, TaskDescriptorRegistrationStatus.INVALID)

        self.assertEqual(self.redis.hashes, {})

    def test_bounded_batch_load_uses_one_non_transaction_pipeline(self) -> None:
        task_1 = self.descriptor("task-1")
        task_2 = self.descriptor("task-2", worker_group_id="audio-workers")
        self.catalog.register_task_descriptor(descriptor=task_1)
        self.catalog.register_task_descriptor(descriptor=task_2)

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=["task-2", "missing", "task-1"]
        )

        self.assertEqual(
            rows,
            {"task-2": task_2, "missing": None, "task-1": task_1},
        )
        self.assertEqual(self.redis.pipeline_transaction_flags, [False])
        self.assertEqual(len(self.redis.pipeline_executions), 1)
        self.assertEqual(
            [command[0] for command in self.redis.pipeline_executions[0]],
            [
                "tc:test:task:task-2",
                "tc:test:task:missing",
                "tc:test:task:task-1",
            ],
        )

    def test_corrupt_row_fails_only_that_task_closed(self) -> None:
        task_1 = self.descriptor("task-1")
        task_2 = self.descriptor("task-2")
        self.catalog.register_task_descriptor(descriptor=task_1)
        self.catalog.register_task_descriptor(descriptor=task_2)
        self.redis.hashes["tc:test:task:task-2"]["configJson"] = "{bad-json"

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=["task-1", "task-2"]
        )

        self.assertEqual(rows, {"task-1": task_1, "task-2": None})

    def test_empty_batch_does_not_open_pipeline(self) -> None:
        self.assertEqual(
            self.catalog.load_task_allocation_descriptors(task_ids=[]),
            {},
        )
        self.assertEqual(self.redis.pipeline_transaction_flags, [])

    def test_batch_load_deduplicates_task_ids(self) -> None:
        task = self.descriptor("task-1")
        self.catalog.register_task_descriptor(descriptor=task)

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=["task-1", "task-1"]
        )

        self.assertEqual(rows, {"task-1": task})
        self.assertEqual(len(self.redis.pipeline_executions[0]), 1)

    def test_catalog_is_exported_without_score_side_effects(self) -> None:
        self.assertIs(py_example.RedisTaskResourceCatalog, RedisTaskResourceCatalog)
        descriptor = self.descriptor("task-1")

        self.catalog.register_task_descriptor(descriptor=descriptor)

        self.assertEqual(set(self.redis.hashes), {"tc:test:task:task-1"})

    def test_empty_prefix_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "prefix must be non-empty"):
            RedisTaskResourceCatalog(self.redis, prefix="")


if __name__ == "__main__":
    unittest.main()
