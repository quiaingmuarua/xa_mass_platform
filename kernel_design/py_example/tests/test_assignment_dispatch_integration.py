from __future__ import annotations

import os
import time
import unittest
import uuid

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.py_example import (
    RedisTaskResourceCatalog,
    RedisTaskDispatchRuntime,
    RedisTaskRuntime,
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisZsetTaskScoreBandCore,
    RedisZsetWorkerScoreCore,
    TaskCreationStatus,
    TaskDescriptor,
    TaskScoreBand,
    TaskScoreTransitionStatus,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class AssignmentDispatchIntegrationTest(unittest.TestCase):
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
        self.worker_group_id = "image-workers"
        self.task_score_key = f"tr:{self.prefix}:task:score"
        self.worker_score_prefix = f"wr:{self.prefix}:score"

        self.task_score = RedisZsetTaskScoreBandCore(
            self.redis,
            score_key=self.task_score_key,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            self.task_score,
            prefix=self.prefix,
        )
        self.task_catalog = RedisTaskResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        self.worker_score = RedisZsetWorkerScoreCore(
            self.redis,
            score_key_prefix=self.worker_score_prefix,
        )
        self.worker_runtime = RedisWorkerRuntime(
            self.redis,
            self.worker_score,
            prefix=self.prefix,
        )
        self.worker_catalog = RedisWorkerResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        dynamic_runtime = RedisWorkerDynamicAttributeRuntime(
            self.worker_catalog,
            update_handlers={},
        )
        self.dispatch_runtime = RedisTaskDispatchRuntime(
            self.redis,
            prefix=self.prefix,
        )
        self.pacer = TaskWorkerAllocationPacer(
            self.task_score,
            self.task_catalog,
            self.worker_score,
            WorkerCandidateMatcher(self.worker_catalog, dynamic_runtime),
            self.dispatch_runtime,
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

    def test_real_redis_allocation_publishes_observed_worker_evidence(self) -> None:
        group_result = self.worker_catalog.register_worker_group_descriptor(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=self.worker_group_id,
                attributes={"kind": "image"},
                event_codes=frozenset({"resize"}),
            )
        )
        worker_result = self.worker_runtime.register_worker_descriptor(
            descriptor=WorkerDescriptor(
                worker_id=self.worker_id,
                worker_group_id=self.worker_group_id,
                system_metadata={"tier": "premium"},
                static_attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            ),
            lane_rank=5,
        )
        task_result = self.task_runtime.create_task(
            descriptor=TaskDescriptor(
                task_id=self.task_id,
                worker_group_id=self.worker_group_id,
                allocation_rule={"static.runtime": {"$eq": "python"}},
                config={
                    "priority": "80",
                    "runningVisibleMinimumCandidateWorkers": "1",
                    "maximumCandidateWorkers": "10",
                },
            ),
            suffix=5,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)
        promoted = self.task_score.rewrite_score(
            task_id=self.task_id,
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_time_millis=time.time_ns() // 1_000_000,
            target_band=TaskScoreBand.RUNNING_VISIBLE,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)

        published = self.pacer.allocate_candidate_workers(
            config=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_scan_limit=10,
                candidate_ttl_millis=5_000,
                no_candidate_recheck_delay_millis=500,
            )
        )
        queued_candidate_count = self.dispatch_runtime.candidate_worker_count(
            task_id=self.task_id,
        )
        entries = self.dispatch_runtime.consume_candidate_workers(
            task_id=self.task_id,
            limit=10,
        )

        self.assertEqual(group_result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(worker_result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(task_result.status, TaskCreationStatus.CREATED)
        self.assertEqual(promoted.status, TaskScoreTransitionStatus.TRANSITIONED)
        self.assertEqual(published, 1)
        self.assertEqual(queued_candidate_count, 1)
        self.assertEqual([entry.worker_id for entry in entries], [self.worker_id])
        self.assertGreater(entries[0].observed_worker_score, 0)


if __name__ == "__main__":
    unittest.main()
