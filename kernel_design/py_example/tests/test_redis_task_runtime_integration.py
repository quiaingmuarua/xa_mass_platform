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

from kernel_design.py_example import (
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisZsetTaskScoreBandCore,
    TaskCreationStatus,
    TaskDescriptor,
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
        self.score_band = RedisZsetTaskScoreBandCore(
            self.redis,
            score_key=self.score_key,
        )
        self.runtime = RedisTaskRuntime(
            self.redis,
            self.score_band,
            prefix=self.prefix,
            lease_duration_millis=200,
        )
        self.catalog = RedisTaskResourceCatalog(self.redis, prefix=self.prefix)
        self.task_ids: set[str] = set()

    def tearDown(self) -> None:
        keys = [self.score_key]
        keys.extend(self._task_key(task_id) for task_id in self.task_ids)
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
                "runningVisibleMinimumCandidateWorkers": "10",
            },
        )

    def _task_key(self, task_id: str) -> str:
        return f"tc:{self.prefix}:task:{task_id}"

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


if __name__ == "__main__":
    unittest.main()
