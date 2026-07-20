from __future__ import annotations

import os
import time
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskScoreBandCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendStatus,
    TaskItemScoreBand,
    TaskItemScoreTransitionStatus,
    TaskScoreTransitionStatus,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class RedisTaskRuntimeIntegrationTest(unittest.TestCase):
    SUFFIX = 7

    @classmethod
    def setUpClass(cls) -> None:
        assert redis_module is not None
        assert _REDIS_URL is not None
        cls.redis = redis_module.Redis.from_url(_REDIS_URL, decode_responses=False)
        try:
            cls.redis.ping()
        except redis_module.RedisError as error:
            raise unittest.SkipTest(f"real Redis is unavailable: {error}") from error

    def setUp(self) -> None:
        self.prefix = f"integration-{uuid.uuid4().hex}"
        self.score_key = f"tr:{self.prefix}:task:score"
        self.score_band = RedisTaskScoreBandCore(
            self.redis,
            score_key=self.score_key,
        )
        self.item_score_band = RedisTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.runtime = RedisTaskRuntime(
            self.redis,
            self.score_band,
            self.item_score_band,
            prefix=self.prefix,
            lease_duration_millis=200,
        )
        self.catalog = RedisTaskResourceCatalog(self.redis, prefix=self.prefix)
        self.task_ids: set[str] = set()

    def tearDown(self) -> None:
        keys = [self.score_key]
        keys.extend(self._task_key(task_id) for task_id in self.task_ids)
        keys.extend(self._items_key(task_id) for task_id in self.task_ids)
        keys.extend(self._item_score_key(task_id) for task_id in self.task_ids)
        self.redis.delete(*keys)

    @staticmethod
    def descriptor(
        task_id: str,
        *,
        worker_group_id: str = "image-workers",
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            allocation_rule={"dynamic.battery": {"$gte": 20}},
            config={
                "priority": "80",
                "maximumCandidateWorkers": "20",
                "maxRetryTimes": "3",
            },
        )

    def _task_key(self, task_id: str) -> str:
        return f"tc:{self.prefix}:task:{task_id}"

    def _items_key(self, task_id: str) -> str:
        return f"tr:{self.prefix}:task:{task_id}:items"

    def _item_score_key(self, task_id: str) -> str:
        return f"tr:{self.prefix}:task:{task_id}:item-score"

    def test_real_redis_allows_only_one_creation_owner_per_slot(self) -> None:
        task_id = "task-atomic"
        self.task_ids.add(task_id)
        descriptor = self.descriptor(task_id)

        def create_once(_: int) -> TaskCreationStatus:
            return self.runtime.create_task(
                descriptor=descriptor,
                suffix=self.SUFFIX,
            ).status

        with ThreadPoolExecutor(max_workers=8) as executor:
            statuses = list(executor.map(create_once, range(16)))

        self.assertEqual(1, statuses.count(TaskCreationStatus.CREATED))
        self.assertNotIn(TaskCreationStatus.INVALID, statuses)
        self.assertTrue(
            all(
                status
                in {
                    TaskCreationStatus.CREATED,
                    TaskCreationStatus.RETRYABLE,
                    TaskCreationStatus.CONFLICT,
                }
                for status in statuses
            )
        )

    def test_real_redis_expired_score_is_not_reinitialized(self) -> None:
        task_id = "task-stale"
        self.task_ids.add(task_id)
        old_lease = self.score_band.initialize_score(
            task_id=task_id,
            suffix=self.SUFFIX,
            lease_duration_millis=self.runtime.lease_duration_millis,
        )
        time.sleep(0.32)
        repeated_initialization = self.score_band.initialize_score(
            task_id=task_id,
            suffix=self.SUFFIX,
            lease_duration_millis=self.runtime.lease_duration_millis,
        )

        self.assertEqual(
            TaskScoreTransitionStatus.NOOP,
            repeated_initialization.status,
        )
        self.assertEqual(old_lease.score, repeated_initialization.score)
        self.assertFalse(self.redis.exists(self._task_key(task_id)))

    def test_real_redis_pipeline_round_trip_decodes_binary_rows(self) -> None:
        first = self.descriptor("task-1")
        second = self.descriptor("task-2", worker_group_id="audio-workers")
        self.task_ids.update({first.task_id, second.task_id})
        self.runtime.create_task(descriptor=first, suffix=self.SUFFIX)
        self.runtime.create_task(descriptor=second, suffix=self.SUFFIX)

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=[second.task_id, "missing", first.task_id]
        )

        self.assertEqual(
            rows,
            {second.task_id: second, "missing": None, first.task_id: first},
        )
        raw_fields = self.redis.hmget(
            self._task_key(first.task_id),
            ["workerGroupId", "allocationRuleJson", "configJson"],
        )
        self.assertTrue(all(isinstance(value, bytes) for value in raw_fields))

    def test_real_redis_corrupt_hash_row_fails_closed(self) -> None:
        first = self.descriptor("task-1")
        second = self.descriptor("task-2")
        self.task_ids.update({first.task_id, second.task_id})
        self.runtime.create_task(descriptor=first, suffix=self.SUFFIX)
        self.runtime.create_task(descriptor=second, suffix=self.SUFFIX)
        self.redis.hset(self._task_key(second.task_id), "configJson", "{bad-json")

        rows = self.catalog.load_task_allocation_descriptors(
            task_ids=[first.task_id, second.task_id]
        )

        self.assertEqual(rows, {first.task_id: first, second.task_id: None})

    def test_real_redis_append_overwrites_record_without_reinitializing_score(
        self,
    ) -> None:
        task_id = "task-items"
        self.task_ids.add(task_id)
        self.runtime.create_task(
            descriptor=self.descriptor(task_id),
            suffix=self.SUFFIX,
        )
        first = TaskItem(
            message_id="message-1",
            event_code="image.resize",
            created_at_millis=int(time.time() * 1_000),
            payload={"source": "first"},
        )
        latest = TaskItem(
            message_id="message-1",
            event_code="image.resize.v2",
            created_at_millis=first.created_at_millis + 100,
            payload={"source": "latest"},
            expire_at_millis=first.created_at_millis + 60_000,
        )

        first_result = self.runtime.append_items(task_id=task_id, items=[first])
        first_score = self.redis.zscore(
            self._item_score_key(task_id),
            first.message_id,
        )
        latest_result = self.runtime.append_items(task_id=task_id, items=[latest])
        loaded = self.runtime.load_task_items(
            task_id=task_id,
            message_ids=[first.message_id, "missing"],
        )
        state = self.item_score_band.get_item_score_states(
            task_id=task_id,
            message_ids=[first.message_id],
        )[first.message_id]

        self.assertEqual(TaskItemAppendStatus.APPENDED, first_result[first.message_id].status)
        self.assertEqual(TaskItemAppendStatus.APPENDED, latest_result[first.message_id].status)
        self.assertEqual(
            first_score,
            self.redis.zscore(self._item_score_key(task_id), first.message_id),
        )
        self.assertEqual(latest, loaded[first.message_id])
        self.assertIsNone(loaded["missing"])
        self.assertEqual(TaskItemScoreBand.ACTIVE, state.band)
        self.assertEqual(4, state.remaining_budget)

    def test_real_redis_task_item_owner_composition_reaches_final_success(
        self,
    ) -> None:
        task_id = "task-item-flow"
        message_id = "message-flow"
        self.task_ids.add(task_id)
        self.runtime.create_task(
            descriptor=self.descriptor(task_id),
            suffix=self.SUFFIX,
        )
        now_millis = int(time.time() * 1_000)
        item = TaskItem(
            message_id=message_id,
            event_code="image.resize",
            created_at_millis=now_millis,
            payload={"source": "input"},
        )

        appended = self.runtime.append_items(task_id=task_id, items=[item])
        observations = self.item_score_band.acquire_item_score_candidates(
            task_id=task_id,
            limit=1,
        )
        observed_score, observed_budget = observations[message_id]
        claimed = self.item_score_band.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores={message_id: observed_score},
            target_time_millis=now_millis + 2_000,
            remaining_budget_delta=-1,
        )
        claim_score = claimed[message_id].score
        loaded = self.runtime.load_task_items(
            task_id=task_id,
            message_ids=[message_id],
        )
        retried = self.item_score_band.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores={message_id: claim_score},
            target_time_millis=now_millis + 4_000,
            remaining_budget_delta=0,
        )
        finalized = self.item_score_band.promote_item_outcomes(
            task_id=task_id,
            message_ids=[message_id],
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=now_millis + 1_000,
        )
        final_state = self.item_score_band.get_item_score_states(
            task_id=task_id,
            message_ids=[message_id],
        )[message_id]

        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            appended[message_id].status,
        )
        self.assertEqual(4, observed_budget)
        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            claimed[message_id].status,
        )
        self.assertEqual(item.message_id, loaded[message_id].message_id)
        self.assertEqual(item.payload, loaded[message_id].payload)
        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            retried[message_id].status,
        )
        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            finalized[message_id].status,
        )
        self.assertEqual(TaskItemScoreBand.FINAL_SUCCESS, final_state.band)
        self.assertIsNone(final_state.remaining_budget)


if __name__ == "__main__":
    unittest.main()
