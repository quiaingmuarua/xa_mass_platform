from __future__ import annotations

import json
import unittest
from typing import Sequence
from unittest.mock import patch

import kernel_design.py_example as py_example
from kernel_design.py_example import (
    RedisTaskCreationRuntime,
    RedisTaskResourceCatalog,
    RedisZsetTaskScoreBandCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskScoreBand,
    TaskScoreTransitionStatus,
)


class FakePipeline:
    def __init__(self, redis_client: FakeRedis) -> None:
        self.redis = redis_client
        self.commands: list[tuple[str, tuple[str, ...]]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def zscore(self, name: str, member: str) -> FakePipeline:
        self.commands.append((name, (member,)))
        return self

    def hmget(self, name: str, keys: Sequence[str]) -> FakePipeline:
        self.commands.append((name, tuple(keys)))
        return self

    def execute(self) -> list[object]:
        self.redis.pipeline_executions.append(tuple(self.commands))
        return [
            self.redis.zscore(name, fields[0])
            if len(fields) == 1 and name in self.redis.zsets
            else self.redis.hmget(name, fields)
            for name, fields in self.commands
        ]


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.zsets: dict[str, dict[str, int]] = {}
        self.now_millis = 100_000
        self.pipeline_transaction_flags: list[bool] = []
        self.pipeline_executions: list[tuple[tuple[str, tuple[str, ...]], ...]] = []

    def exists(self, key: str) -> int:
        return int(key in self.hashes)

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        self.pipeline_transaction_flags.append(transaction)
        return FakePipeline(self)

    def hmget(self, name: str, keys: Sequence[str]) -> list[str | None]:
        row = self.hashes.get(name, {})
        return [row.get(key) for key in keys]

    def hset(self, name: str, *, mapping: dict[str, object]) -> int:
        row = self.hashes.setdefault(name, {})
        added = 0
        for key, value in mapping.items():
            added += int(key not in row)
            row[key] = str(value)
        return added

    def zscore(self, key: str, member: str) -> int | None:
        return self.zsets.get(key, {}).get(member)

    def zadd(
        self,
        key: str,
        mapping: dict[str, int],
        *,
        nx: bool = False,
    ) -> int:
        zset = self.zsets.setdefault(key, {})
        added = 0
        for member, score in mapping.items():
            if nx and member in zset:
                continue
            added += int(member not in zset)
            zset[member] = int(score)
        return added

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    def eval(self, script: str, numkeys: int, *args: object) -> list[object]:
        if "local observed_score" in script:
            return self._cas_score(args)
        raise ValueError(f"unsupported fake script with {numkeys} keys")

    def _cas_score(self, args: tuple[object, ...]) -> list[object]:
        score_key = str(args[0])
        task_id = str(args[1])
        observed_score = int(args[2])
        next_score = int(args[3])
        stored = self.zscore(score_key, task_id)
        if stored is None or stored != observed_score:
            return ["stale"]
        self.zadd(score_key, {task_id: next_score})
        return ["transitioned", next_score]


class RedisTaskRuntimeTest(unittest.TestCase):
    SUFFIX = 7

    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.score_core = RedisZsetTaskScoreBandCore(
            self.redis,
            score_key="tr:test:task:score",
        )
        self.creation = RedisTaskCreationRuntime(self.score_core, prefix="test")
        self.catalog = RedisTaskResourceCatalog(self.redis, prefix="test")

    @staticmethod
    def descriptor(
        task_id: str,
        *,
        worker_group_id: str = "image-workers",
        allocation_rule: dict[str, object] | None = None,
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            allocation_rule=(
                {"dynamic.battery": {"$gte": 20}}
                if allocation_rule is None
                else allocation_rule
            ),
            config={
                "priority": "80",
                "runningVisibleMinimumCandidateWorkers": "10",
            },
        )

    def test_create_task_commits_descriptor_under_score_lease(self) -> None:
        descriptor = self.descriptor("task-1")

        result = self.creation.create_task(descriptor=descriptor, suffix=self.SUFFIX)
        rows = self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])
        state = self.score_core.get_score_states(task_ids=["task-1"])["task-1"]

        self.assertEqual(TaskCreationStatus.CREATED, result.status)
        self.assertEqual({"task-1": descriptor}, rows)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.PRE_REVIEW, state.band)
        self.assertEqual(self.SUFFIX, state.suffix)
        self.assertEqual(self.redis.now_millis, state.time_millis)

    def test_duplicate_create_conflicts_without_touching_score(self) -> None:
        first = self.descriptor("task-1")
        second = self.descriptor("task-1", worker_group_id="audio-workers")
        self.creation.create_task(descriptor=first, suffix=self.SUFFIX)
        stored_score = self.redis.zscore(self.score_core.score_key, "task-1")
        self.redis.now_millis += self.score_core.SLOT_MILLIS

        result = self.creation.create_task(descriptor=second, suffix=self.SUFFIX)

        self.assertEqual(TaskCreationStatus.CONFLICT, result.status)
        self.assertEqual(stored_score, self.redis.zscore(self.score_core.score_key, "task-1"))
        self.assertEqual(
            first,
            self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])["task-1"],
        )

    def test_orphan_descriptor_does_not_block_score_owned_creation(self) -> None:
        self.redis.hashes["tc:test:task:task-1"] = {
            "workerGroupId": "orphan-workers",
            "allocationRuleJson": "{}",
            "configJson": "{}",
        }
        descriptor = self.descriptor("task-1")

        result = self.creation.create_task(
            descriptor=descriptor,
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.CREATED, result.status)
        self.assertEqual(
            descriptor,
            self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])["task-1"],
        )

    def test_existing_score_conflicts_with_create(self) -> None:
        self.score_core.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.creation.lease_duration_millis,
        )

        result = self.creation.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.CONFLICT, result.status)
        self.assertNotIn("tc:test:task:task-1", self.redis.hashes)

    def test_descriptor_write_failure_best_effort_releases_score(self) -> None:
        descriptor = self.descriptor("task-1")
        with patch.object(
            self.creation,
            "_write_descriptor",
            side_effect=RuntimeError("write failed"),
        ):
            with self.assertRaisesRegex(RuntimeError, "write failed"):
                self.creation.create_task(
                    descriptor=descriptor,
                    suffix=self.SUFFIX,
                )

        released = self.score_core.get_score_states(task_ids=["task-1"])["task-1"]
        same_slot = self.score_core.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.creation.lease_duration_millis,
        )
        self.redis.now_millis += self.score_core.SLOT_MILLIS
        next_slot = self.score_core.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.creation.lease_duration_millis,
        )

        self.assertIsNotNone(released)
        self.assertEqual(
            self.redis.now_millis - self.score_core.SLOT_MILLIS,
            released.time_millis,
        )
        self.assertEqual(TaskScoreTransitionStatus.NOOP, same_slot.status)
        self.assertEqual(TaskScoreTransitionStatus.NOOP, next_slot.status)

    def test_expired_initialization_score_is_not_reinitialized(self) -> None:
        first = self.score_core.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.creation.lease_duration_millis,
        )
        self.redis.now_millis += (
            self.creation.lease_duration_millis + self.score_core.SLOT_MILLIS
        )

        result = self.creation.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )
        current_score = self.redis.zscore(self.score_core.score_key, "task-1")

        self.assertEqual(TaskCreationStatus.CONFLICT, result.status)
        self.assertEqual(first.score, current_score)

    def test_descriptor_write_with_stale_release_is_retryable(self) -> None:
        descriptor = self.descriptor("task-1")
        with patch.object(
            self.creation,
            "_release_creation_lease",
            return_value=TaskScoreTransitionStatus.STALE,
        ):
            result = self.creation.create_task(
                descriptor=descriptor,
                suffix=self.SUFFIX,
            )

        self.assertEqual(TaskCreationStatus.RETRYABLE, result.status)
        self.assertIn("tc:test:task:task-1", self.redis.hashes)

    def test_stale_release_does_not_overwrite_owner_transition(self) -> None:
        descriptor = self.descriptor("task-1")
        old_lease = self.score_core.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.creation.lease_duration_millis,
        )
        self.creation._write_descriptor(
            descriptor=descriptor,
            allocation_rule_json=json.dumps(dict(descriptor.allocation_rule)),
            config_json=json.dumps(dict(descriptor.config)),
        )
        replacement_score = self.score_core._score(
            self.score_core.PRE_REVIEW_TAG,
            self.score_core._current_time_slot() + 10,
            2,
        )
        self.redis.zadd(self.score_core.score_key, {"task-1": replacement_score})

        status = self.creation._release_creation_lease(
            task_id=descriptor.task_id,
            observed_lease_score=int(old_lease.score),
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, status)
        self.assertEqual(
            replacement_score,
            self.redis.zscore(self.score_core.score_key, "task-1"),
        )
        self.assertIn("tc:test:task:task-1", self.redis.hashes)

    def test_non_json_descriptor_is_rejected_before_score_write(self) -> None:
        result = self.creation.create_task(
            descriptor=self.descriptor(
                "task-1",
                allocation_rule={"dynamic.battery": {"$eq": object()}},
            ),
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.INVALID, result.status)
        self.assertEqual({}, self.redis.zsets)
        self.assertEqual({}, self.redis.hashes)

    def test_batch_load_is_bounded_and_deduplicated(self) -> None:
        task_1 = self.descriptor("task-1")
        task_2 = self.descriptor("task-2", worker_group_id="audio-workers")
        self.creation.create_task(descriptor=task_1, suffix=self.SUFFIX)
        self.redis.now_millis += self.score_core.SLOT_MILLIS
        self.creation.create_task(descriptor=task_2, suffix=self.SUFFIX)

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=["task-2", "missing", "task-1", "task-1"]
        )

        self.assertEqual(
            {"task-2": task_2, "missing": None, "task-1": task_1},
            rows,
        )
        self.assertEqual([False], self.redis.pipeline_transaction_flags)
        self.assertEqual(3, len(self.redis.pipeline_executions[0]))

    def test_corrupt_row_fails_only_that_task_closed(self) -> None:
        task_1 = self.descriptor("task-1")
        task_2 = self.descriptor("task-2")
        self.creation.create_task(descriptor=task_1, suffix=self.SUFFIX)
        self.redis.now_millis += self.score_core.SLOT_MILLIS
        self.creation.create_task(descriptor=task_2, suffix=self.SUFFIX)
        self.redis.hashes["tc:test:task:task-2"]["configJson"] = "{bad-json"

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=["task-1", "task-2"]
        )

        self.assertEqual({"task-1": task_1, "task-2": None}, rows)

    def test_exports_and_read_only_catalog_surface(self) -> None:
        self.assertIs(py_example.RedisTaskCreationRuntime, RedisTaskCreationRuntime)
        self.assertIs(py_example.RedisTaskResourceCatalog, RedisTaskResourceCatalog)
        self.assertFalse(hasattr(RedisTaskResourceCatalog, "register_task_descriptor"))


if __name__ == "__main__":
    unittest.main()
