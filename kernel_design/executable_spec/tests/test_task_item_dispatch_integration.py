from __future__ import annotations

import json
import os
import time
import unittest
import uuid

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    CandidateWorkerEntry,
    RedisAssignmentDispatchRuntime,
    RedisDeliverSeedRuntime,
    RedisTaskRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskScoreBandCore,
    RedisWorkerScoreCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendStatus,
    TaskItemDispatchConfig,
    TaskItemDispatchPacer,
    TaskItemScoreBand,
    TaskScoreBand,
    TaskScoreTransitionStatus,
    WorkerScoreTransitionStatus,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


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
        self.task_score = RedisTaskScoreBandCore(
            self.redis,
            score_key=f"tr:{self.prefix}:task:score",
        )
        self.item_score = RedisTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            self.task_score,
            self.item_score,
            prefix=self.prefix,
        )
        self.candidate_runtime = RedisAssignmentDispatchRuntime(
            self.redis,
            prefix=self.prefix,
        )
        self.deliver_seed_runtime = RedisDeliverSeedRuntime(
            self.redis,
            prefix=self.prefix,
        )
        self.worker_score = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=f"wr:{self.prefix}:score",
        )
        self.pacer = TaskItemDispatchPacer(
            self.task_score,
            self.candidate_runtime,
            self.deliver_seed_runtime,
            self.item_score,
            self.task_runtime,
            self.worker_score,
        )

    def tearDown(self) -> None:
        self.redis.delete(
            f"tr:{self.prefix}:task:score",
            f"tc:{self.prefix}:task:{self.task_id}",
            f"tr:{self.prefix}:task:{self.task_id}:items",
            f"tr:{self.prefix}:task:{self.task_id}:item-score",
            f"wr:{self.prefix}:score:image-workers",
            f"ad:{self.prefix}:task:{self.task_id}:candidate-workers",
            (
                f"ad:{self.prefix}:endpoint-manager:endpoint-manager-1:"
                "deliver-seeds"
            ),
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
        initialized_worker = self.worker_score.initialize_hot_acquire_score(
            home_bucket_id="image-workers",
            worker_id="worker-1",
            lane_rank=50,
        )
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            initialized_worker.status,
        )
        time.sleep((self.worker_score.SLOT_MILLIS + 20) / 1_000)
        observed_worker_score = self.worker_score.acquire_hot_acquire_candidates(
            home_bucket_id="image-workers",
            limit=1,
        )["worker-1"]
        worker_lease = self.worker_score.acquire_observed_hot_score_leases(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": observed_worker_score},
            target_time_millis=time.time_ns() // 1_000_000 + 5_000,
        )["worker-1"]
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            worker_lease.status,
        )
        assert worker_lease.score is not None
        self.candidate_runtime.append_candidate_workers(
            task_id=self.task_id,
            candidate_workers=(
                CandidateWorkerEntry(
                    worker_id="worker-1",
                    worker_group_id="image-workers",
                    endpoint_manager_id="endpoint-manager-1",
                    worker_lease_score=worker_lease.score,
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
        candidate_count = self.candidate_runtime.candidate_worker_counts(
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
        seeds = self.deliver_seed_runtime.consume_deliver_seeds(
            endpoint_manager_id="endpoint-manager-1",
            limit=10,
        )
        self.assertEqual(1, len(seeds))
        seed = seeds[0]
        delivery_item = json.loads(seed.opaque_delivery_item)
        result_context = json.loads(seed.opaque_result_context)
        self.assertEqual("worker-1", seed.worker_id)
        self.assertEqual(
            {
                "eventCode": "image.resize",
                "payload": {"source": "s3://input"},
            },
            delivery_item,
        )
        self.assertEqual(
            {
                "taskId": self.task_id,
                "messageId": self.message_id,
                "workerId": "worker-1",
                "workerGroupId": "image-workers",
                "claimScore": item_state.score,
                "workerLeaseScore": worker_lease.score,
                "taskItemClaimUntilMillis": seed.task_item_claim_until_millis,
            },
            result_context,
        )
        self.assertGreaterEqual(
            seed.task_item_claim_until_millis,
            dispatch_started_millis + 3_000,
        )
        self.assertEqual(
            (),
            self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                limit=10,
            ),
        )


if __name__ == "__main__":
    unittest.main()
