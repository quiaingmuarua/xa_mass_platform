from __future__ import annotations

import os
import time
import unittest
import uuid

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    RedisTaskResourceCatalog,
    RedisAssignmentDispatchRuntime,
    RedisTaskRuntime,
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskScoreBandCore,
    RedisWorkerScoreCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskRunningActivationConfig,
    TaskRunningActivationPacer,
    TaskScoreBand,
    TaskScoreTransitionStatus,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
    minimum_candidate_workers_satisfied,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class TaskWorkerAllocationIntegrationTest(unittest.TestCase):
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
        self.task_id = "task-1"
        self.worker_id = "worker-1"
        self.unmatched_worker_id = "worker-2"
        self.worker_group_id = "image-workers"
        self.task_score_key = f"tr:{self.prefix}:task:score"
        self.worker_score_prefix = f"wr:{self.prefix}:score"

        self.task_score = RedisTaskScoreBandCore(
            self.redis,
            score_key=self.task_score_key,
        )
        self.task_item_score = RedisTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            self.task_score,
            self.task_item_score,
            prefix=self.prefix,
        )
        self.task_catalog = RedisTaskResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        self.worker_score = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=self.worker_score_prefix,
        )
        self.worker_runtime = RedisWorkerRuntime(
            self.redis,
            self.worker_score,
            prefix=self.prefix,
            initial_lane_rank=5,
        )
        self.worker_catalog = RedisWorkerResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        dynamic_runtime = RedisWorkerDynamicAttributeRuntime(
            self.worker_catalog,
            update_handlers={},
        )
        self.dispatch_runtime = RedisAssignmentDispatchRuntime(
            self.redis,
            prefix=self.prefix,
        )
        self.pacer = TaskWorkerAllocationPacer(
            self.task_score,
            self.task_catalog,
            self.worker_score,
            WorkerCandidateMatcher(
                self.worker_catalog,
                dynamic_runtime,
            ),
            self.dispatch_runtime,
        )
        self.running_activation_pacer = TaskRunningActivationPacer(
            self.task_score,
            self.task_catalog,
            self.dispatch_runtime,
            minimum_candidate_workers_satisfied,
        )

    def tearDown(self) -> None:
        self.redis.delete(
            self.task_score_key,
            f"tc:{self.prefix}:task:{self.task_id}",
            f"ad:{self.prefix}:task:{self.task_id}:candidate-workers",
            f"wr:{self.prefix}:groups",
            f"wr:{self.prefix}:workers:{self.worker_group_id}",
            f"{self.worker_score_prefix}:{self.worker_group_id}",
        )

    def test_real_redis_allocation_publishes_worker_reservation(self) -> None:
        group_result = self.worker_catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=self.worker_group_id,
                attributes={"kind": "image"},
                event_codes=frozenset({"resize"}),
            )
        )
        worker_result = self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=self.worker_id,
                worker_group_id=self.worker_group_id,
                endpoint_manager_id="endpoint-manager-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            ),
        )
        unmatched_worker_result = self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=self.unmatched_worker_id,
                worker_group_id=self.worker_group_id,
                endpoint_manager_id="endpoint-manager-1",
                attributes={"runtime": "java"},
                dynamic_attribute_names=frozenset(),
            ),
        )
        task_result = self.task_runtime.create_task(
            descriptor=TaskDescriptor(
                task_id=self.task_id,
                worker_group_id=self.worker_group_id,
                allocation_rule={"attributes.runtime": {"$eq": "python"}},
                config={
                    "priority": "80",
                    "runningVisibleMinimumCandidateWorkers": "1",
                    "maximumCandidateWorkers": "10",
                    "maxRetryTimes": "3",
                },
            ),
            suffix=5,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)
        approved = self.task_score.rewrite_score(
            task_id=self.task_id,
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_time_millis=time.time_ns() // 1_000_000,
            target_band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
            target_suffix=5,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)
        score_before_allocation = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]

        published = self.pacer.allocate_candidate_workers(
            config=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_scan_limit=10,
                worker_lease_duration_millis=5_000,
            )
        )
        score_after_allocation = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]
        worker_score_after_allocation = self.worker_score.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=(self.worker_id, self.unmatched_worker_id),
        )
        published_while_reserved = self.pacer.allocate_candidate_workers(
            config=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_scan_limit=10,
                worker_lease_duration_millis=5_000,
            )
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)
        due_worker_candidates = self.worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=self.worker_group_id,
            limit=10,
        )
        transitioned = self.running_activation_pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )
        running_state = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]
        queued_candidate_count = self.dispatch_runtime.candidate_worker_counts(
            task_ids=(self.task_id,),
        )[self.task_id]
        entries = self.dispatch_runtime.consume_candidate_workers(
            task_id=self.task_id,
            limit=10,
        )

        self.assertEqual(group_result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(worker_result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(unmatched_worker_result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(task_result.status, TaskCreationStatus.CREATED)
        self.assertEqual(approved.status, TaskScoreTransitionStatus.TRANSITIONED)
        self.assertEqual(published, 1)
        self.assertEqual(published_while_reserved, 0)
        self.assertEqual(
            score_after_allocation.band,
            TaskScoreBand.PRE_DISPATCH_VISIBLE,
        )
        self.assertGreater(
            score_after_allocation.time_millis,
            score_before_allocation.time_millis,
        )
        self.assertEqual(score_after_allocation.suffix, 5)
        self.assertEqual(transitioned, 1)
        self.assertEqual(running_state.band, TaskScoreBand.RUNNING_VISIBLE)
        self.assertEqual(running_state.suffix, 8)
        self.assertEqual(queued_candidate_count, 1)
        self.assertEqual([entry.worker_id for entry in entries], [self.worker_id])
        self.assertEqual(entries[0].endpoint_manager_id, "endpoint-manager-1")
        self.assertGreater(
            worker_score_after_allocation[self.worker_id].time_millis,
            time.time_ns() // 1_000_000,
        )
        self.assertGreater(
            worker_score_after_allocation[
                self.unmatched_worker_id
            ].time_millis,
            time.time_ns() // 1_000_000,
        )
        self.assertEqual(
            entries[0].worker_lease_score,
            worker_score_after_allocation[self.worker_id].score,
        )
        self.assertNotIn(self.unmatched_worker_id, due_worker_candidates)
        self.assertNotIn(self.worker_id, due_worker_candidates)


if __name__ == "__main__":
    unittest.main()
