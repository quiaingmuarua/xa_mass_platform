from __future__ import annotations

import os
import time
import unittest
import uuid
from collections.abc import Sequence

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.py_example import (
    CandidateWorkerEntry,
    DeliverSeed,
    DeliverSeedQueue,
    RedisTaskDispatchRuntime,
    RedisTaskRuntime,
    RedisZsetTaskItemScoreBandCore,
    RedisZsetTaskScoreBandCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendStatus,
    TaskItemDispatchConfig,
    TaskItemDispatchPacer,
    TaskItemScoreBand,
    TaskScoreBand,
    TaskScoreTransitionStatus,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


class RecordingDeliverSeedQueue(DeliverSeedQueue):
    def __init__(self) -> None:
        self.batches: list[tuple[str, tuple[DeliverSeed, ...]]] = []

    def append_deliver_seeds(
        self,
        *,
        endpoint_manager_id: str,
        deliver_seeds: Sequence[DeliverSeed],
    ) -> None:
        self.batches.append((endpoint_manager_id, tuple(deliver_seeds)))


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class TaskItemDispatchIntegrationTest(unittest.TestCase):
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
        self.prefix = f"dispatch-{uuid.uuid4().hex}"
        self.task_id = "task-1"
        self.message_id = "message-1"
        self.task_score = RedisZsetTaskScoreBandCore(
            self.redis,
            score_key=f"tr:{self.prefix}:task:score",
        )
        self.item_score = RedisZsetTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            self.task_score,
            self.item_score,
            prefix=self.prefix,
        )
        self.dispatch_runtime = RedisTaskDispatchRuntime(
            self.redis,
            prefix=self.prefix,
        )
        self.queue = RecordingDeliverSeedQueue()
        self.pacer = TaskItemDispatchPacer(
            self.task_score,
            self.dispatch_runtime,
            self.item_score,
            self.task_runtime,
            self.queue,
        )

    def tearDown(self) -> None:
        self.redis.delete(
            f"tr:{self.prefix}:task:score",
            f"tc:{self.prefix}:task:{self.task_id}",
            f"tr:{self.prefix}:task:{self.task_id}:items",
            f"tr:{self.prefix}:task:{self.task_id}:item-score",
            f"ad:{self.prefix}:task:{self.task_id}:candidate-workers",
        )

    def test_real_redis_dispatch_claims_item_and_appends_seed(self) -> None:
        created = self.task_runtime.create_task(
            descriptor=TaskDescriptor(
                task_id=self.task_id,
                worker_group_id="image-workers",
                allocation_rule={},
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "10",
                    "runningVisibleMinimumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
            ),
            suffix=5,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)
        running = self.task_score.rewrite_score(
            task_id=self.task_id,
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_time_millis=time.time_ns() // 1_000_000,
            target_band=TaskScoreBand.RUNNING_VISIBLE,
            target_suffix=5,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)

        item = TaskItem(
            message_id=self.message_id,
            event_code="image.resize",
            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
            payload={"source": "s3://input"},
        )
        appended = self.task_runtime.append_items(
            task_id=self.task_id,
            items=(item,),
        )
        self.dispatch_runtime.append_candidate_workers(
            task_id=self.task_id,
            candidate_workers=(
                CandidateWorkerEntry(
                    worker_id="worker-1",
                    worker_group_id="image-workers",
                    endpoint_manager_id="endpoint-manager-1",
                    worker_lease_score=123_456,
                ),
            ),
            expires_at_millis=time.time_ns() // 1_000_000 + 5_000,
        )

        dispatch_started_millis = time.time_ns() // 1_000_000
        dispatched = self.pacer.dispatch_task_items(
            config=TaskItemDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
            )
        )
        candidate_count = self.dispatch_runtime.candidate_worker_counts(
            task_ids=(self.task_id,),
        )[self.task_id]
        item_state = self.item_score.get_item_score_states(
            task_id=self.task_id,
            message_ids=(self.message_id,),
        )[self.message_id]

        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, running.status)
        self.assertEqual(TaskItemAppendStatus.APPENDED, appended[self.message_id].status)
        self.assertEqual(1, dispatched)
        self.assertEqual(0, candidate_count)
        self.assertIsNotNone(item_state)
        self.assertEqual(TaskItemScoreBand.ACTIVE, item_state.band)
        self.assertEqual(3, item_state.remaining_budget)
        self.assertGreaterEqual(
            item_state.time_millis,
            dispatch_started_millis + 3_000 - self.item_score.SLOT_MILLIS,
        )
        self.assertEqual(1, len(self.queue.batches))
        endpoint_manager_id, seeds = self.queue.batches[0]
        self.assertEqual("endpoint-manager-1", endpoint_manager_id)
        self.assertEqual(1, len(seeds))
        seed = seeds[0]
        self.assertEqual(self.task_id, seed.task_id)
        self.assertEqual("worker-1", seed.selected_worker_id)
        self.assertEqual("endpoint-manager-1", seed.endpoint_manager_id)
        self.assertEqual(self.message_id, seed.task_item.message_id)
        self.assertEqual(item_state.score, seed.claim_score)
        self.assertEqual(123_456, seed.worker_lease_score)
        self.assertEqual(
            [],
            list(self.redis.scan_iter(match=f"*{self.prefix}*deliver*")),
        )


if __name__ == "__main__":
    unittest.main()
