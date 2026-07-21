from __future__ import annotations

import json
import os
import time
import unittest
import uuid
from collections.abc import Mapping

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    TaskType,
    DueTaskItemAdmissionPolicy,
    PrioritySoftLimitSystemAdmissionPolicy,
    RedisCandidateWorkerCache,
    RedisDeliverSeedRuntime,
    RedisTaskRuntime,
    RedisTaskResourceCatalog,
    RedisTaskItemScoreBandCore,
    RedisTaskScoreBandCore,
    RedisWorkerScoreCore,
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    TaskCreationStatus,
    TaskCreationResult,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskItemDispatchConfig,
    TaskItemDispatchPacer,
    TaskRunningActivationConfig,
    TaskRunningActivationPacer,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
    TaskItemScoreBand,
    TaskScoreBand,
    WorkerCandidateMatcher,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)
from kernel_design.executable_spec.assembly.application import (
    TaskApprovalResult,
    TaskApprovalStatus,
    _TaskLifecycleManager,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
)
from kernel_design.executable_spec.redis_runtime.assignment_dispatch import (
    RedisCandidateWarmupSchedule,
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
        self.task_catalog = RedisTaskResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        self.candidate_cache = RedisCandidateWorkerCache(
            self.redis,
            prefix=self.prefix,
        )
        self.warmup_schedule = RedisCandidateWarmupSchedule(
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
        self.worker_catalog = RedisWorkerResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        self.worker_runtime = RedisWorkerRuntime(
            self.redis,
            self.worker_score,
            prefix=self.prefix,
            initial_lane_rank=50,
        )
        dynamic_runtime = RedisWorkerDynamicAttributeRuntime(
            self.worker_catalog,
            update_handlers={},
        )
        candidate_acquirer = WorkerCandidateAcquirer(
            self.candidate_cache,
            self.worker_score,
            WorkerCandidateMatcher(
                self.worker_catalog,
                dynamic_runtime,
            ),
            dynamic_runtime,
            worker_scan_limit=10,
        )
        self.allocation_pacer = TaskWorkerAllocationPacer(
            self.warmup_schedule,
            self.task_score,
            self.task_catalog,
            candidate_acquirer,
            self.candidate_cache,
        )
        self.activation_pacer = TaskRunningActivationPacer(
            self.task_score,
            self.task_catalog,
            DueTaskItemAdmissionPolicy(self.item_score),
            PrioritySoftLimitSystemAdmissionPolicy(
                self.task_score,
                running_task_soft_limit=100,
            ),
            self.warmup_schedule,
        )
        self.task_lifecycle = _TaskLifecycleManager(
            self.task_score,
            self.task_catalog,
        )
        self.pacer = TaskItemDispatchPacer(
            self.task_score,
            self.task_catalog,
            self.deliver_seed_runtime,
            self.item_score,
            self.task_runtime,
            candidate_acquirer,
            self.warmup_schedule,
        )

    def tearDown(self) -> None:
        self.redis.delete(
            f"tr:{self.prefix}:task:score",
            f"tc:{self.prefix}:task:{self.task_id}",
            f"tr:{self.prefix}:task:{self.task_id}:items",
            f"tr:{self.prefix}:task:{self.task_id}:item-score",
            f"wr:{self.prefix}:score:image-workers",
            f"ad:{self.prefix}:candidate:{self.task_id}:workers",
            f"ad:{self.prefix}:candidate-warmups",
            f"wr:{self.prefix}:groups",
            f"wr:{self.prefix}:workers:image-workers",
            (
                f"ad:{self.prefix}:endpoint-manager:endpoint-manager-1:"
                "deliver-seeds"
            ),
            (
                f"ad:{self.prefix}:endpoint-manager:endpoint-manager-2:"
                "deliver-seeds"
            ),
        )

    def _create_approve_append_activate(
        self,
        *,
        descriptor: TaskDescriptor,
        items: tuple[TaskItem, ...],
    ) -> tuple[
        TaskCreationResult,
        TaskApprovalResult,
        Mapping[str, TaskItemAppendResult],
        int,
    ]:
        created = self.task_runtime.create_task(
            descriptor=descriptor,
            suffix=5,
        )
        approved = self.task_lifecycle.approve_task(task_id=descriptor.task_id)
        appended = self.task_runtime.append_items(
            task_id=descriptor.task_id,
            items=items,
        )
        time.sleep((2 * self.task_score.SLOT_MILLIS + 20) / 1_000)
        activated = self.activation_pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )
        return created, approved, appended, activated

    def test_task_driven_vertical_redis_proof_uses_precomputed_cache(self) -> None:
        descriptor = TaskDescriptor(
            task_id=self.task_id,
            worker_group_id="image-workers",
            task_type=TaskType.TASK_DRIVEN,
            allocation_rule={"attributes.runtime": {"$eq": "python"}},
            config={
                "priority": "80",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
        )
        item = TaskItem(
            message_id=self.message_id,
            event_code="image.resize",
            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
            payload={"source": "s3://input"},
        )
        created = self.task_runtime.create_task(descriptor=descriptor, suffix=5)
        approved = self.task_lifecycle.approve_task(task_id=self.task_id)
        time.sleep((2 * self.task_score.SLOT_MILLIS + 20) / 1_000)
        activation_without_item = (
            self.activation_pacer.activate_running_visible_tasks(
                config=TaskRunningActivationConfig(
                    task_batch_limit=10,
                    running_visible_initial_suffix=8,
                )
            )
        )
        state_without_item = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]
        appended = self.task_runtime.append_items(
            task_id=self.task_id,
            items=(item,),
        )
        time.sleep((2 * self.task_score.SLOT_MILLIS + 20) / 1_000)
        activated = self.activation_pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )
        running_state = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]
        warmup_score = self.redis.zscore(
            f"ad:{self.prefix}:candidate-warmups",
            self.task_id,
        )
        candidate_count_before_worker_registration = (
            self.candidate_cache.candidate_worker_counts(
                candidate_ids=(self.task_id,),
            )[self.task_id]
        )

        group_result = self.worker_catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        matched_worker = self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            )
        )
        unmatched_worker = self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-2",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-2",
                attributes={"runtime": "java"},
                dynamic_attribute_names=frozenset(),
            )
        )
        time.sleep((self.worker_score.SLOT_MILLIS + 20) / 1_000)
        dispatched_without_precomputation = self.pacer.dispatch_task_items(
            config=TaskItemDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
            )
        )
        task_state_before_warmup = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]
        warmed_tasks = self.allocation_pacer.allocate_candidate_workers(
            config=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_lease_duration_millis=5_000,
            )
        )
        task_state_after_warmup = self.task_score.get_score_states(
            task_ids=(self.task_id,)
        )[self.task_id]
        candidate_count_before_dispatch = (
            self.candidate_cache.candidate_worker_counts(
                candidate_ids=(self.task_id,),
            )[self.task_id]
        )
        worker_states = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=("worker-1", "worker-2"),
        )
        worker_lease_score = worker_states["worker-1"].score
        due_worker_candidates = self.worker_score.acquire_hot_acquire_candidates(
            home_bucket_id="image-workers",
            limit=10,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)

        dispatch_started_millis = time.time_ns() // 1_000_000
        dispatched = self.pacer.dispatch_task_items(
            config=TaskItemDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
            )
        )
        candidate_count = self.candidate_cache.candidate_worker_counts(
            candidate_ids=(self.task_id,),
        )[self.task_id]
        item_state = self.item_score.get_item_score_states(
            task_id=self.task_id,
            message_ids=(self.message_id,),
        )[self.message_id]
        warmup_score_after_dispatch = self.redis.zscore(
            f"ad:{self.prefix}:candidate-warmups",
            self.task_id,
        )

        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskApprovalStatus.APPROVED, approved.status)
        self.assertEqual(0, activation_without_item)
        self.assertEqual(TaskScoreBand.PRE_DISPATCH_VISIBLE, state_without_item.band)
        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            appended[self.message_id].status,
        )
        self.assertEqual(WorkerRuntimeStatus.OK, group_result.status)
        self.assertEqual(WorkerRuntimeStatus.OK, matched_worker.status)
        self.assertEqual(WorkerRuntimeStatus.OK, unmatched_worker.status)
        self.assertEqual(1, activated)
        self.assertIsNotNone(running_state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, running_state.band)
        self.assertIsNotNone(warmup_score)
        self.assertEqual(0, candidate_count_before_worker_registration)
        self.assertEqual(0, dispatched_without_precomputation)
        self.assertEqual(1, warmed_tasks)
        self.assertEqual(task_state_before_warmup, task_state_after_warmup)
        self.assertEqual(1, candidate_count_before_dispatch)
        self.assertGreater(
            worker_states["worker-1"].time_millis,
            time.time_ns() // 1_000_000,
        )
        self.assertGreater(
            worker_states["worker-2"].time_millis,
            time.time_ns() // 1_000_000,
        )
        self.assertNotIn("worker-1", due_worker_candidates)
        self.assertNotIn("worker-2", due_worker_candidates)
        self.assertEqual(1, dispatched)
        self.assertEqual(0, candidate_count)
        self.assertIsNotNone(warmup_score_after_dispatch)
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
                "workerLeaseScore": worker_lease_score,
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

    def test_item_driven_vertical_redis_proof_uses_complete_item_rules(self) -> None:
        group_result = self.worker_catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
                item_allocation_fields=frozenset({"workerId"}),
            )
        )
        worker_results = tuple(
            self.worker_runtime.upsert_worker(
                declaration=WorkerDeclaration(
                    worker_id=f"worker-{index}",
                    worker_group_id="image-workers",
                    endpoint_manager_id=f"endpoint-manager-{index}",
                    attributes={"runtime": "python"},
                    dynamic_attribute_names=frozenset(),
                )
            )
            for index in (1, 2)
        )
        time.sleep((self.worker_score.SLOT_MILLIS + 20) / 1_000)

        items = tuple(
            TaskItem(
                message_id=f"message-{index}",
                event_code="image.resize",
                created_at_millis=time.time_ns() // 1_000_000 - 1_000,
                payload={"target": f"worker-{index}"},
                allocation_rule={"workerId": {"$eq": f"worker-{index}"}},
            )
            for index in (1, 2)
        )
        created, approved, appended, activated = (
            self._create_approve_append_activate(
                descriptor=TaskDescriptor(
                    task_id=self.task_id,
                    worker_group_id="image-workers",
                    task_type=TaskType.ITEM_DRIVEN,
                    allocation_rule=None,
                    config={
                        "priority": "80",
                        "maximumCandidateWorkers": "10",
                        "maxRetryTimes": "3",
                    },
                ),
                items=items,
            )
        )
        warmup_score_after_activation = self.redis.zscore(
            f"ad:{self.prefix}:candidate-warmups",
            self.task_id,
        )
        worker_scores_before_warmer = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=("worker-1", "worker-2"),
        )
        warmed_tasks = self.allocation_pacer.allocate_candidate_workers(
            config=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_lease_duration_millis=5_000,
            )
        )
        worker_scores_after_warmer = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=("worker-1", "worker-2"),
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)

        dispatched = self.pacer.dispatch_task_items(
            config=TaskItemDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
            )
        )

        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskApprovalStatus.APPROVED, approved.status)
        self.assertTrue(
            all(
                appended[item.message_id].status is TaskItemAppendStatus.APPENDED
                for item in items
            )
        )
        self.assertEqual(WorkerRuntimeStatus.OK, group_result.status)
        self.assertTrue(
            all(result.status is WorkerRuntimeStatus.OK for result in worker_results)
        )
        self.assertEqual(1, activated)
        self.assertIsNone(warmup_score_after_activation)
        self.assertEqual(0, warmed_tasks)
        self.assertEqual(worker_scores_before_warmer, worker_scores_after_warmer)
        self.assertEqual(2, dispatched)
        self.assertEqual(
            {
                self.task_id: 0,
                "message-1": 0,
                "message-2": 0,
            },
            self.candidate_cache.candidate_worker_counts(
                candidate_ids=(self.task_id, "message-1", "message-2"),
            ),
        )
        self.assertIsNone(
            self.redis.zscore(
                f"ad:{self.prefix}:candidate-warmups",
                self.task_id,
            )
        )
        for index in (1, 2):
            seeds = self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id=f"endpoint-manager-{index}",
                limit=10,
            )
            self.assertEqual(1, len(seeds))
            self.assertEqual(f"worker-{index}", seeds[0].worker_id)
            self.assertEqual(
                f"message-{index}",
                json.loads(seeds[0].opaque_result_context)["messageId"],
            )
            self.assertEqual(
                {"target": f"worker-{index}"},
                json.loads(seeds[0].opaque_delivery_item)["payload"],
            )


if __name__ == "__main__":
    unittest.main()
