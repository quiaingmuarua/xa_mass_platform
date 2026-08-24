from __future__ import annotations

import json
import unittest
from typing import Sequence
from unittest.mock import patch

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    RedisKeyspace,
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskScoreBandCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskItem,
    TaskItemAppendStatus,
    TaskItemScoreBand,
    TaskScoreBand,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
)


class FakePipeline:
    def __init__(self, redis_client: FakeRedis) -> None:
        self.redis = redis_client
        self.commands: list[tuple[str, str, tuple[object, ...]]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def zscore(self, name: str, member: str) -> FakePipeline:
        self.commands.append(("zscore", name, (member,)))
        return self

    def hmget(self, name: str, keys: Sequence[str]) -> FakePipeline:
        self.commands.append(("hmget", name, tuple(keys)))
        return self

    def zadd(
        self,
        name: str,
        mapping: dict[str, int],
        *,
        nx: bool = False,
    ) -> FakePipeline:
        self.commands.append(("zadd", name, (mapping, nx)))
        return self

    def hsetnx(
        self,
        name: str,
        key: str,
        value: object,
    ) -> FakePipeline:
        self.commands.append(("hsetnx", name, (key, value)))
        return self

    def execute(self) -> list[object]:
        self.redis.pipeline_executions.append(tuple(self.commands))
        results: list[object] = []
        for command, name, args in self.commands:
            if command == "zscore":
                results.append(self.redis.zscore(name, str(args[0])))
            elif command == "hmget":
                results.append(self.redis.hmget(name, args))
            elif command == "zadd":
                results.append(
                    self.redis.zadd(
                        name,
                        args[0],
                        nx=bool(args[1]),
                    )
                )
            elif command == "hsetnx":
                results.append(self.redis.hsetnx(name, str(args[0]), args[1]))
            else:
                raise ValueError(f"unsupported fake pipeline command: {command}")
        return results


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.zsets: dict[str, dict[str, int]] = {}
        self.now_millis = 100_000
        self.pipeline_transaction_flags: list[bool] = []
        self.pipeline_executions: list[tuple[tuple[str, str, tuple[object, ...]], ...]] = []
        self.hscan_calls: list[tuple[str, int, int]] = []

    def exists(self, key: str) -> int:
        return int(key in self.hashes)

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        self.pipeline_transaction_flags.append(transaction)
        return FakePipeline(self)

    def hmget(self, name: str, keys: Sequence[str]) -> list[str | None]:
        row = self.hashes.get(name, {})
        return [row.get(key) for key in keys]

    def hget(self, name: str, key: str) -> str | None:
        return self.hashes.get(name, {}).get(key)

    def hset(self, name: str, *, mapping: dict[str, object]) -> int:
        row = self.hashes.setdefault(name, {})
        added = 0
        for key, value in mapping.items():
            added += int(key not in row)
            row[key] = str(value)
        return added

    def hsetnx(self, name: str, key: str, value: object) -> int:
        row = self.hashes.setdefault(name, {})
        if key in row:
            return 0
        row[key] = str(value)
        return 1

    def hscan(
        self,
        name: str,
        *,
        cursor: int,
        count: int,
    ) -> tuple[int, dict[str, str]]:
        self.hscan_calls.append((name, cursor, count))
        entries = list(self.hashes.get(name, {}).items())
        end = min(len(entries), cursor + count)
        next_cursor = 0 if end == len(entries) else end
        return next_cursor, dict(entries[cursor:end])

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
        self.keyspace = RedisKeyspace("test_task_runtime_unit")
        self.score_band = RedisTaskScoreBandCore(
            self.redis,
            keyspace=self.keyspace,
        )
        self.item_score_band = RedisTaskItemScoreBandCore(
            self.redis,
            keyspace=self.keyspace,
        )
        self.runtime = RedisTaskRuntime(
            self.redis,
            self.score_band,
            self.item_score_band,
            keyspace=self.keyspace,
        )
        self.catalog = RedisTaskResourceCatalog(
            self.redis,
            keyspace=self.keyspace,
        )

    @staticmethod
    def descriptor(
        task_id: str,
        *,
        worker_group_id: str = "image-workers",
        allocation_mechanism: WorkerAllocationMechanism = (
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ),
        idle_disposition: TaskIdleDisposition = (
            TaskIdleDisposition.CLOSE_WHEN_IDLE
        ),
        allocation_rule: dict[str, object] | None = None,
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            worker_allocation_mechanism=allocation_mechanism,
            idle_disposition=idle_disposition,
            allocation_rule=(
                {"worker.battery": {"$gte": 20}}
                if allocation_rule is None
                and allocation_mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else allocation_rule
            ),
            config={
                "priority": "80",
                "maximumCandidateWorkers": "20",
                "maxRetryTimes": "3",
            },
        )

    def test_create_task_commits_descriptor_under_score_lease(self) -> None:
        descriptor = self.descriptor("task-1")

        result = self.runtime.create_task(descriptor=descriptor, suffix=self.SUFFIX)
        rows = self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])
        state = self.score_band.get_score_states(task_ids=["task-1"])["task-1"]

        self.assertEqual(TaskCreationStatus.CREATED, result.status)
        self.assertEqual({"task-1": descriptor}, rows)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.PRE_REVIEW, state.band)
        self.assertEqual(self.SUFFIX, state.suffix)
        self.assertEqual(self.redis.now_millis, state.time_millis)
        fields = self.redis.hashes[
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
        ]
        self.assertEqual(
            "PRECOMPUTED_TASK_RULE",
            fields["workerAllocationMechanism"],
        )
        self.assertEqual("CLOSE_WHEN_IDLE", fields["idleDisposition"])

    def test_direct_descriptor_round_trips_null_task_rule(self) -> None:
        descriptor = self.descriptor(
            "task-1",
            allocation_mechanism=WorkerAllocationMechanism.DIRECT_ITEM_RULE,
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
        )

        result = self.runtime.create_task(descriptor=descriptor, suffix=self.SUFFIX)
        loaded = self.catalog.load_task_allocation_descriptors(
            task_ids=[descriptor.task_id],
        )[descriptor.task_id]

        self.assertEqual(TaskCreationStatus.CREATED, result.status)
        self.assertEqual(descriptor, loaded)
        self.assertEqual(
            "null",
            self.redis.hashes[
                "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
            ]["allocationRuleJson"],
        )
        self.assertEqual(
            "PARK_WHEN_IDLE",
            self.redis.hashes[
                "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
            ]["idleDisposition"],
        )

    def test_descriptor_without_idle_disposition_is_not_decoded(self) -> None:
        descriptor = self.descriptor("task-1")
        self.runtime.create_task(descriptor=descriptor, suffix=self.SUFFIX)
        del self.redis.hashes[
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
        ]["idleDisposition"]

        loaded = self.catalog.load_task_allocation_descriptors(
            task_ids=("task-1",),
        )

        self.assertIsNone(loaded["task-1"])

    def test_old_allocation_scope_row_is_not_decoded(self) -> None:
        self.redis.hashes[
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
        ] = {
            "workerGroupId": "workers",
            "allocationRuleScope": "TASK",
            "allocationRuleJson": "{}",
            "configJson": json.dumps(
                {
                    "priority": "80",
                    "maximumCandidateWorkers": "10",
                    "maxRetryTimes": "3",
                }
            ),
        }

        loaded = self.catalog.load_task_allocation_descriptors(
            task_ids=("task-1",),
        )

        self.assertIsNone(loaded["task-1"])

    def test_duplicate_create_conflicts_without_touching_score(self) -> None:
        first = self.descriptor("task-1")
        second = self.descriptor("task-1", worker_group_id="audio-workers")
        self.runtime.create_task(descriptor=first, suffix=self.SUFFIX)
        stored_score = self.redis.zscore(self.score_band.score_key, "task-1")
        self.redis.now_millis += self.score_band.SLOT_MILLIS

        result = self.runtime.create_task(descriptor=second, suffix=self.SUFFIX)

        self.assertEqual(TaskCreationStatus.CONFLICT, result.status)
        self.assertEqual(stored_score, self.redis.zscore(self.score_band.score_key, "task-1"))
        self.assertEqual(
            first,
            self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])["task-1"],
        )

    def test_orphan_descriptor_conflicts_without_creating_score(self) -> None:
        orphan = {
            "workerGroupId": "orphan-workers",
            "unknownField": "legacy",
            "allocationRuleJson": "{}",
            "configJson": "{}",
        }
        self.redis.hashes[
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
        ] = dict(orphan)
        descriptor = self.descriptor("task-1")

        result = self.runtime.create_task(
            descriptor=descriptor,
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.CONFLICT, result.status)
        self.assertEqual(
            orphan,
            self.redis.hashes[
                "xa_mass:test_task_runtime_unit:task:task-1:descriptor"
            ],
        )
        self.assertIsNone(self.redis.zscore(self.score_band.score_key, "task-1"))

    def test_pre_review_score_without_descriptor_completes_creation(self) -> None:
        existing = self.score_band.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.runtime.lease_duration_millis,
        )
        descriptor = self.descriptor("task-1")

        result = self.runtime.create_task(
            descriptor=descriptor,
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.CREATED, result.status)
        self.assertEqual(
            descriptor,
            self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])["task-1"],
        )
        self.assertEqual(
            existing.score,
            self.redis.zscore(self.score_band.score_key, "task-1"),
        )

    def test_descriptor_write_failure_retries_by_completing_pre_review(self) -> None:
        descriptor = self.descriptor("task-1")
        with patch.object(
            self.runtime,
            "_write_descriptor_if_absent",
            side_effect=RuntimeError("write failed"),
        ):
            with self.assertRaisesRegex(RuntimeError, "write failed"):
                self.runtime.create_task(
                    descriptor=descriptor,
                    suffix=self.SUFFIX,
                )

        released = self.score_band.get_score_states(task_ids=["task-1"])["task-1"]
        self.redis.now_millis += self.score_band.SLOT_MILLIS
        retry = self.runtime.create_task(descriptor=descriptor, suffix=self.SUFFIX)

        self.assertIsNotNone(released)
        self.assertEqual(
            self.redis.now_millis - self.score_band.SLOT_MILLIS,
            released.time_millis,
        )
        self.assertEqual(TaskCreationStatus.CREATED, retry.status)
        self.assertEqual(
            descriptor,
            self.catalog.load_task_allocation_descriptors(task_ids=["task-1"])["task-1"],
        )

    def test_expired_pre_review_score_completes_without_reinitialization(self) -> None:
        first = self.score_band.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.runtime.lease_duration_millis,
        )
        self.redis.now_millis += (
            self.runtime.lease_duration_millis + self.score_band.SLOT_MILLIS
        )

        result = self.runtime.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )
        current_score = self.redis.zscore(self.score_band.score_key, "task-1")

        self.assertEqual(TaskCreationStatus.CREATED, result.status)
        self.assertEqual(first.score, current_score)

    def test_existing_non_pre_review_score_conflicts_without_descriptor(self) -> None:
        admission_score = self.score_band._score(
            self.score_band.ADMISSION_VISIBLE_TAG,
            self.score_band._current_time_slot(),
            0,
        )
        self.redis.zadd(self.score_band.score_key, {"task-1": admission_score})

        result = self.runtime.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.CONFLICT, result.status)
        self.assertNotIn(
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor",
            self.redis.hashes,
        )

    def test_descriptor_write_with_stale_release_is_retryable(self) -> None:
        descriptor = self.descriptor("task-1")
        with patch.object(
            self.score_band,
            "release_observed_score_hold",
            return_value=TaskScoreTransitionResult(
                TaskScoreTransitionStatus.STALE
            ),
        ):
            result = self.runtime.create_task(
                descriptor=descriptor,
                suffix=self.SUFFIX,
            )

        self.assertEqual(TaskCreationStatus.RETRYABLE, result.status)
        self.assertIn(
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor",
            self.redis.hashes,
        )

    def test_stale_release_does_not_overwrite_owner_transition(self) -> None:
        descriptor = self.descriptor("task-1")
        old_lease = self.score_band.initialize_score(
            task_id="task-1",
            suffix=self.SUFFIX,
            lease_duration_millis=self.runtime.lease_duration_millis,
        )
        self.redis.hset(
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor",
            mapping={
                "workerGroupId": descriptor.worker_group_id,
                "workerAllocationMechanism": (
                    descriptor.worker_allocation_mechanism.value
                ),
                "idleDisposition": descriptor.idle_disposition.value,
                "allocationRuleJson": json.dumps(dict(descriptor.allocation_rule)),
                "configJson": json.dumps(dict(descriptor.config)),
            },
        )
        replacement_score = self.score_band._score(
            self.score_band.PRE_REVIEW_TAG,
            self.score_band._current_time_slot() + 10,
            2,
        )
        self.redis.zadd(self.score_band.score_key, {"task-1": replacement_score})

        release = self.score_band.release_observed_score_hold(
            task_id=descriptor.task_id,
            observed_hold_score=int(old_lease.score),
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, release.status)
        self.assertEqual(
            replacement_score,
            self.redis.zscore(self.score_band.score_key, "task-1"),
        )
        self.assertIn(
            "xa_mass:test_task_runtime_unit:task:task-1:descriptor",
            self.redis.hashes,
        )

    def test_non_json_descriptor_is_rejected_before_score_write(self) -> None:
        result = self.runtime.create_task(
            descriptor=self.descriptor(
                "task-1",
                allocation_rule={"worker.battery": {"$eq": object()}},
            ),
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.INVALID, result.status)
        self.assertEqual({}, self.redis.zsets)
        self.assertEqual({}, self.redis.hashes)

    def test_invalid_constraint_dsl_is_rejected_before_score_write(self) -> None:
        result = self.runtime.create_task(
            descriptor=self.descriptor(
                "task-1",
                allocation_rule={
                    "worker.battery": {"$unknown": 20},
                },
            ),
            suffix=self.SUFFIX,
        )

        self.assertEqual(TaskCreationStatus.INVALID, result.status)
        self.assertEqual({}, self.redis.zsets)
        self.assertEqual({}, self.redis.hashes)

    def test_batch_load_is_bounded_and_deduplicated(self) -> None:
        task_1 = self.descriptor("task-1")
        task_2 = self.descriptor("task-2", worker_group_id="audio-workers")
        self.runtime.create_task(descriptor=task_1, suffix=self.SUFFIX)
        self.redis.now_millis += self.score_band.SLOT_MILLIS
        self.runtime.create_task(descriptor=task_2, suffix=self.SUFFIX)
        self.redis.pipeline_transaction_flags.clear()
        self.redis.pipeline_executions.clear()

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
        self.runtime.create_task(descriptor=task_1, suffix=self.SUFFIX)
        self.redis.now_millis += self.score_band.SLOT_MILLIS
        self.runtime.create_task(descriptor=task_2, suffix=self.SUFFIX)
        self.redis.hashes[
            "xa_mass:test_task_runtime_unit:task:task-2:descriptor"
        ]["configJson"] = "{bad-json"

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=["task-1", "task-2"]
        )

        self.assertEqual({"task-1": task_1, "task-2": None}, rows)

    def test_append_overwrites_latest_item_record_and_initializes_score_once(
        self,
    ) -> None:
        self.runtime.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )
        first = TaskItem(
            message_id="message-1",
            event_code="image.resize",
            created_at_millis=90_000,
            payload={"source": "first"},
            priority=8,
        )
        latest = TaskItem(
            message_id="message-1",
            event_code="image.resize",
            created_at_millis=91_000,
            payload={"source": "latest"},
            priority=9,
            allocation_rule={"workerId": {"$eq": "worker-2"}},
        )

        first_result = self.runtime.append_items(
            task_id="task-1",
            items=[first],
        )
        first_score = self.redis.zscore(
            "xa_mass:test_task_runtime_unit:task:task-1:item_score",
            "message-1",
        )
        latest_result = self.runtime.append_items(
            task_id="task-1",
            items=[latest],
        )
        loaded = self.runtime.load_task_items(
            task_id="task-1",
            message_ids=["message-1"],
        )["message-1"]

        self.assertEqual(TaskItemAppendStatus.APPENDED, first_result["message-1"].status)
        self.assertEqual(TaskItemAppendStatus.APPENDED, latest_result["message-1"].status)
        self.assertEqual(first_score, self.redis.zscore(
            "xa_mass:test_task_runtime_unit:task:task-1:item_score",
            "message-1",
        ))
        self.assertEqual({"source": "latest"}, loaded.payload)
        self.assertEqual(
            {"workerId": {"$eq": "worker-2"}},
            loaded.allocation_rule,
        )
        self.assertEqual(
            latest.created_at_millis + self.runtime.DEFAULT_ITEM_TTL_MILLIS,
            loaded.expire_at_millis,
        )

    def test_append_batch_initializes_retry_budget_and_loads_missing_as_none(
        self,
    ) -> None:
        self.runtime.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )
        payload_item = TaskItem(
            message_id="message-1",
            event_code="image.resize",
            created_at_millis=90_000,
            payload={"source": "s3://input"},
        )
        ref_item = TaskItem(
            message_id="message-2",
            event_code="image.resize",
            created_at_millis=90_000,
            payload={"ref": "item://payload-2"},
            expire_at_millis=120_000,
        )

        results = self.runtime.append_items(
            task_id="task-1",
            items=[payload_item, ref_item],
        )
        loaded = self.runtime.load_task_items(
            task_id="task-1",
            message_ids=["message-2", "missing", "message-1", "message-1"],
        )
        states = self.item_score_band.get_item_score_states(
            task_id="task-1",
            message_ids=["message-1", "message-2"],
        )

        self.assertTrue(
            all(result.status is TaskItemAppendStatus.APPENDED for result in results.values())
        )
        self.assertEqual(["message-2", "missing", "message-1"], list(loaded))
        self.assertIsNone(loaded["missing"])
        self.assertEqual(
            {"ref": "item://payload-2"},
            loaded["message-2"].payload,
        )
        self.assertTrue(all(state.band is TaskItemScoreBand.ACTIVE for state in states.values()))
        self.assertTrue(all(state.remaining_budget == 4 for state in states.values()))

    def test_append_missing_task_does_not_write_item_or_score(self) -> None:
        result = self.runtime.append_items(
            task_id="missing",
            items=[
                TaskItem(
                    message_id="message-1",
                    event_code="event",
                    created_at_millis=1_000,
                    payload={},
                )
            ],
        )

        self.assertEqual(TaskItemAppendStatus.NOT_FOUND, result["message-1"].status)
        self.assertNotIn(
            "xa_mass:test_task_runtime_unit:task:missing:items",
            self.redis.hashes,
        )

    def test_append_rejects_already_expired_item_without_writes(self) -> None:
        self.runtime.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )
        result = self.runtime.append_items(
            task_id="task-1",
            items=[
                TaskItem(
                    message_id="expired",
                    event_code="image.resize",
                    created_at_millis=90_000,
                    expire_at_millis=self.redis.now_millis,
                    payload={},
                )
            ],
        )

        self.assertEqual(TaskItemAppendStatus.INVALID, result["expired"].status)
        self.assertNotIn(
            "xa_mass:test_task_runtime_unit:task:task-1:items",
            self.redis.hashes,
        )
        self.assertIsNone(
            self.redis.zscore(
                "xa_mass:test_task_runtime_unit:task:task-1:item_score",
                "expired",
            )
        )

    def test_success_results_are_task_scoped_bounded_and_last_write_wins(
        self,
    ) -> None:
        self.runtime.store_task_item_success_results(
            task_id="task-1",
            results={"message-1": '{"version":1}'},
        )
        self.runtime.store_task_item_success_results(
            task_id="task-1",
            results={"message-1": '{"version":2}', "message-2": "null"},
        )
        self.runtime.store_task_item_success_results(
            task_id="task-2",
            results={"message-1": '{"task":2}'},
        )

        self.assertEqual(
            {
                "message-1": '{"version":2}',
                "message-2": "null",
                "missing": None,
            },
            self.runtime.load_task_item_success_results(
                task_id="task-1",
                message_ids=("message-1", "message-2", "missing", "message-1"),
            ),
        )
        self.assertEqual(
            {"message-1": '{"task":2}'},
            self.runtime.load_task_item_success_results(
                task_id="task-2",
                message_ids=("message-1",),
            ),
        )
        self.assertEqual(
            {},
            self.runtime.load_task_item_success_results(
                task_id="task-1",
                message_ids=(),
            ),
        )

    def test_success_results_scan_one_task_hash_page_per_call(self) -> None:
        self.runtime.store_task_item_success_results(
            task_id="task-1",
            results={
                "message-1": '{"version":1}',
                "message-2": '{"version":2}',
            },
        )
        self.runtime.store_task_item_success_results(
            task_id="task-2",
            results={"message-1": '{"task":2}'},
        )

        first = self.runtime.scan_task_item_success_results(
            task_id="task-1",
            cursor="0",
            count_hint=1,
        )
        second = self.runtime.scan_task_item_success_results(
            task_id="task-1",
            cursor=first.next_cursor,
            count_hint=1,
        )

        self.assertEqual({"message-1": '{"version":1}'}, first.results)
        self.assertNotEqual("0", first.next_cursor)
        self.assertEqual({"message-2": '{"version":2}'}, second.results)
        self.assertEqual("0", second.next_cursor)
        self.assertEqual(
            [
                (
                    "xa_mass:test_task_runtime_unit:task:task-1:results",
                    0,
                    1,
                ),
                (
                    "xa_mass:test_task_runtime_unit:task:task-1:results",
                    1,
                    1,
                ),
            ],
            self.redis.hscan_calls,
        )

    def test_success_result_scan_rejects_invalid_coordinates(self) -> None:
        for task_id, cursor, count_hint in (
            ("", "0", 1),
            ("task-1", "", 1),
            ("task-1", "-1", 1),
            ("task-1", "0", 0),
            ("task-1", "0", 1001),
        ):
            with self.subTest(
                task_id=task_id,
                cursor=cursor,
                count_hint=count_hint,
            ):
                with self.assertRaises(ValueError):
                    self.runtime.scan_task_item_success_results(
                        task_id=task_id,
                        cursor=cursor,
                        count_hint=count_hint,
                    )
        self.assertEqual([], self.redis.hscan_calls)

    def test_success_result_storage_rejects_invalid_owner_coordinates(self) -> None:
        self.runtime.store_task_item_success_results(task_id="task-1", results={})
        self.assertNotIn(
            "xa_mass:test_task_runtime_unit:task:task-1:results",
            self.redis.hashes,
        )
        for task_id, results in (
            ("", {"message-1": "null"}),
            ("task-1", {"": "null"}),
            ("task-1", {"message-1": ""}),
        ):
            with self.subTest(task_id=task_id, results=results):
                with self.assertRaises(ValueError):
                    self.runtime.store_task_item_success_results(
                        task_id=task_id,
                        results=results,
                    )
        self.assertNotIn(
            "xa_mass:test_task_runtime_unit:task:missing:item_score",
            self.redis.zsets,
        )

    def test_append_score_failure_leaves_latest_record_for_retry(self) -> None:
        self.runtime.create_task(
            descriptor=self.descriptor("task-1"),
            suffix=self.SUFFIX,
        )
        item = TaskItem(
            message_id="message-1",
            event_code="event",
            created_at_millis=90_000,
            payload={"value": 1},
        )

        with patch.object(
            self.item_score_band,
            "initialize_item_scores",
            side_effect=RuntimeError("score unavailable"),
        ):
            failed = self.runtime.append_items(task_id="task-1", items=[item])
        retried = self.runtime.append_items(task_id="task-1", items=[item])

        self.assertEqual(TaskItemAppendStatus.RETRYABLE, failed["message-1"].status)
        self.assertEqual(TaskItemAppendStatus.APPENDED, retried["message-1"].status)
        self.assertIsNotNone(
            self.runtime.load_task_items(
                task_id="task-1",
                message_ids=["message-1"],
            )["message-1"]
        )

    def test_exports_and_read_only_catalog_surface(self) -> None:
        self.assertIs(executable_spec.RedisTaskRuntime, RedisTaskRuntime)
        self.assertIs(executable_spec.RedisTaskResourceCatalog, RedisTaskResourceCatalog)
        self.assertFalse(hasattr(RedisTaskResourceCatalog, "register_task_descriptor"))


if __name__ == "__main__":
    unittest.main()
