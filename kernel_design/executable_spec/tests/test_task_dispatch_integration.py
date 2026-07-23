from __future__ import annotations

import json
import os
import time
import unittest
import uuid
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    TaskType,
    DeliverSeed,
    DeliverSeedAppendStatus,
    DueTaskItemAdmissionPolicy,
    RunningSoftLimitSystemAdmissionPolicy,
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
    TaskDispatchConfig,
    TaskDispatchPacer,
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
from kernel_design.executable_spec.scheduling import TaskItemDispatcher
from kernel_design.executable_spec.redis_runtime.assignment_dispatch import (
    RedisCandidateWarmupSchedule,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class TaskDispatchIntegrationTest(unittest.TestCase):
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
            RunningSoftLimitSystemAdmissionPolicy(
                self.task_score,
                running_task_soft_limit=100,
            ),
            self.warmup_schedule,
        )
        self.task_lifecycle = _TaskLifecycleManager(
            self.task_score,
            self.task_catalog,
        )
        task_item_dispatcher = TaskItemDispatcher(
            self.item_score,
            self.task_runtime,
            candidate_acquirer,
            self.warmup_schedule,
        )
        self.pacer = TaskDispatchPacer(
            self.task_score,
            self.task_catalog,
            self.deliver_seed_runtime,
            self.item_score,
            self.warmup_schedule,
            task_item_dispatcher,
        )

    def test_adapter_mailbox_worker_field_has_one_atomic_consumer(self) -> None:
        seed = DeliverSeed(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"image.resize","payload":{}}',
            opaque_result_context='{"taskId":"task-1"}',
            task_item_claim_until_millis=int(time.time() * 1_000) + 5_000,
        )
        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.APPENDED},
            self.deliver_seed_runtime.append_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                deliver_seeds_by_worker_id={"worker-1": seed}
            ),
        )
        conflicting_seed = DeliverSeed(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"other","payload":{}}',
            opaque_result_context='{"taskId":"task-2"}',
            task_item_claim_until_millis=int(time.time() * 1_000) + 5_000,
        )
        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.OCCUPIED},
            self.deliver_seed_runtime.append_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                deliver_seeds_by_worker_id={"worker-1": conflicting_seed}
            ),
        )
        competing_runtime = RedisDeliverSeedRuntime(
            self.redis,
            prefix=self.prefix,
        )

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = tuple(
                executor.map(
                    lambda runtime: runtime.consume_deliver_seed(
                        endpoint_manager_id="endpoint-manager-1",
                        worker_id="worker-1",
                    ),
                    (self.deliver_seed_runtime, competing_runtime),
                )
            )

        self.assertEqual(1, sum(bool(result) for result in results))
        self.assertEqual([seed], [result for result in results if result])

    def test_adapter_mailbox_cursor_consumes_sparse_worker_fields(self) -> None:
        seeds = tuple(
            DeliverSeed(
                worker_id=f"mailbox-worker-{index}",
                opaque_delivery_item=f'{{"index":{index}}}',
                opaque_result_context=f'{{"index":{index}}}',
                task_item_claim_until_millis=int(time.time() * 1_000) + 5_000,
            )
            for index in range(3)
        )
        self.deliver_seed_runtime.append_deliver_seeds(
            endpoint_manager_id="endpoint-manager-cursor",
            deliver_seeds_by_worker_id={
                seed.worker_id: seed for seed in seeds
            },
        )

        consumed: list[DeliverSeed] = []
        cursor = None
        for _ in range(10):
            page = self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="endpoint-manager-cursor",
                cursor=cursor,
                scan_count=1,
            )
            consumed.extend(page.deliver_seeds)
            cursor = page.next_cursor
            if cursor is None:
                break
        else:
            self.fail("Redis HSCAN cursor did not terminate")

        self.assertEqual(
            {seed.worker_id for seed in seeds},
            {seed.worker_id for seed in consumed},
        )

    def tearDown(self) -> None:
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

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
            config=TaskRunningActivationConfig(task_batch_limit=10)
        )
        return created, approved, appended, activated

    def test_task_driven_vertical_redis_proof_uses_precomputed_cache(self) -> None:
        descriptor = TaskDescriptor(
            task_id=self.task_id,
            worker_group_id="image-workers",
            task_type=TaskType.TASK_DRIVEN,
            allocation_rule={"attributes.runtime": {"$eq": "python"}},
            config={
                "priority": "0",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
            empty_close_at_millis=0,
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
                config=TaskRunningActivationConfig(task_batch_limit=10)
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
            config=TaskRunningActivationConfig(task_batch_limit=10)
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
        dispatched_without_precomputation = self.pacer.dispatch_tasks(
            config=TaskDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
                max_empty_recheck_times=5,
                empty_recheck_interval_millis=1_000,
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
        dispatched = self.pacer.dispatch_tasks(
            config=TaskDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
                max_empty_recheck_times=5,
                empty_recheck_interval_millis=1_000,
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
        self.assertEqual(TaskScoreBand.ADMISSION_VISIBLE, state_without_item.band)
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
        seed = self.deliver_seed_runtime.consume_deliver_seed(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )
        self.assertIsNotNone(seed)
        assert seed is not None
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
            None,
            self.deliver_seed_runtime.consume_deliver_seed(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )

    def test_admission_recheck_exposes_task_behind_blocked_window(self) -> None:
        blocker_ids = ("blocked-1", "blocked-2")
        task_ids = (*blocker_ids, self.task_id)

        def descriptor(task_id: str) -> TaskDescriptor:
            return TaskDescriptor(
                task_id=task_id,
                worker_group_id="image-workers",
                task_type=TaskType.TASK_DRIVEN,
                allocation_rule={},
                config={
                    "priority": "0",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
                empty_close_at_millis=0,
            )

        try:
            for task_id in blocker_ids:
                self.assertEqual(
                    TaskCreationStatus.CREATED,
                    self.task_runtime.create_task(
                        descriptor=descriptor(task_id),
                        suffix=5,
                    ).status,
                )
                self.assertEqual(
                    TaskApprovalStatus.APPROVED,
                    self.task_lifecycle.approve_task(task_id=task_id).status,
                )

            time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)
            self.assertEqual(
                TaskCreationStatus.CREATED,
                self.task_runtime.create_task(
                    descriptor=descriptor(self.task_id),
                    suffix=5,
                ).status,
            )
            self.assertEqual(
                TaskApprovalStatus.APPROVED,
                self.task_lifecycle.approve_task(task_id=self.task_id).status,
            )
            self.assertEqual(
                TaskItemAppendStatus.APPENDED,
                self.task_runtime.append_items(
                    task_id=self.task_id,
                    items=(
                        TaskItem(
                            message_id=self.message_id,
                            event_code="image.resize",
                            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
                            payload={},
                        ),
                    ),
                )[self.message_id].status,
            )
            time.sleep((2 * self.task_score.SLOT_MILLIS + 20) / 1_000)

            first_round = self.activation_pacer.activate_running_visible_tasks(
                config=TaskRunningActivationConfig(task_batch_limit=2)
            )
            second_round = self.activation_pacer.activate_running_visible_tasks(
                config=TaskRunningActivationConfig(task_batch_limit=2)
            )
            state = self.task_score.get_score_states(
                task_ids=(self.task_id,)
            )[self.task_id]

            self.assertEqual(0, first_round)
            self.assertEqual(1, second_round)
            self.assertIsNotNone(state)
            self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)
        finally:
            self.redis.delete(
                *(
                    key
                    for task_id in task_ids
                    for key in (
                        f"tc:{self.prefix}:task:{task_id}",
                        f"tr:{self.prefix}:task:{task_id}:items",
                        f"tr:{self.prefix}:task:{task_id}:item-score",
                    )
                )
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
                    empty_close_at_millis=9_999_999_999_999,
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

        dispatched = self.pacer.dispatch_tasks(
            config=TaskDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=10,
                item_claim_lease_duration_millis=3_000,
                max_empty_recheck_times=5,
                empty_recheck_interval_millis=1_000,
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
            worker_id = f"worker-{index}"
            seed = self.deliver_seed_runtime.consume_deliver_seed(
                endpoint_manager_id=f"endpoint-manager-{index}",
                worker_id=worker_id,
            )
            self.assertIsNotNone(seed)
            assert seed is not None
            self.assertEqual(worker_id, seed.worker_id)
            self.assertEqual(
                f"message-{index}",
                json.loads(seed.opaque_result_context)["messageId"],
            )
            self.assertEqual(
                {"target": f"worker-{index}"},
                json.loads(seed.opaque_delivery_item)["payload"],
            )

    def test_expired_item_finalizes_before_worker_acquisition(self) -> None:
        now_millis = time.time_ns() // 1_000_000
        expire_at_millis = now_millis + 60_000
        descriptor = TaskDescriptor(
            task_id=self.task_id,
            worker_group_id="image-workers",
            task_type=TaskType.TASK_DRIVEN,
            allocation_rule={},
            config={
                "priority": "0",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
            empty_close_at_millis=0,
        )
        item = TaskItem(
            message_id=self.message_id,
            event_code="kernel-does-not-validate-event-code",
            created_at_millis=now_millis - 1_000,
            expire_at_millis=expire_at_millis,
            payload={},
        )
        created, approved, appended, activated = (
            self._create_approve_append_activate(
                descriptor=descriptor,
                items=(item,),
            )
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=expire_at_millis,
        ):
            dispatched = self.pacer.dispatch_tasks(
                config=TaskDispatchConfig(
                    task_batch_limit=10,
                    per_task_dispatch_limit=10,
                    item_claim_lease_duration_millis=5_000,
                    max_empty_recheck_times=5,
                    empty_recheck_interval_millis=1_000,
                )
            )

        item_state = self.item_score.get_item_score_states(
            task_id=self.task_id,
            message_ids=(self.message_id,),
        )[self.message_id]
        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskApprovalStatus.APPROVED, approved.status)
        self.assertEqual(TaskItemAppendStatus.APPENDED, appended[self.message_id].status)
        self.assertEqual(1, activated)
        self.assertEqual(0, dispatched)
        self.assertIs(TaskItemScoreBand.FINAL_FAILED, item_state.band)
        self.assertEqual(
            0,
            self.redis.zcard(
                f"ad:{self.prefix}:candidate:{self.task_id}:workers"
            ),
        )
        self.assertEqual(
            0,
            self.redis.llen(
                f"ad:{self.prefix}:endpoint-manager:endpoint-manager-1:deliver-seeds"
            ),
        )

    def test_task_driven_empty_rechecks_close_and_release_running_slot(self) -> None:
        self.worker_catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
                item_allocation_fields=frozenset(),
            )
        )
        self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-1",
                attributes={},
                dynamic_attribute_names=frozenset(),
            )
        )
        item = TaskItem(
            message_id=self.message_id,
            event_code="image.resize",
            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
            payload={},
        )
        self._create_approve_append_activate(
            descriptor=TaskDescriptor(
                task_id=self.task_id,
                worker_group_id="image-workers",
                task_type=TaskType.TASK_DRIVEN,
                allocation_rule={},
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
                empty_close_at_millis=0,
            ),
            items=(item,),
        )
        self.item_score.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=(self.message_id,),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=time.time_ns() // 1_000_000,
        )
        config = TaskDispatchConfig(
            task_batch_limit=10,
            per_task_dispatch_limit=10,
            item_claim_lease_duration_millis=1_000,
            max_empty_recheck_times=2,
            empty_recheck_interval_millis=100,
        )

        time.sleep(0.12)
        self.pacer.dispatch_tasks(config=config)
        first = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]
        worker_before_warmup = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=("worker-1",),
        )["worker-1"]
        warmed = self.allocation_pacer.allocate_candidate_workers(
            config=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_lease_duration_millis=1_000,
            )
        )
        worker_after_warmup = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=("worker-1",),
        )["worker-1"]
        time.sleep(0.22)
        self.pacer.dispatch_tasks(config=config)
        second = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]
        time.sleep(0.32)
        self.pacer.dispatch_tasks(config=config)
        closed = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]

        self.assertEqual(1, first.suffix)
        self.assertEqual(0, warmed)
        self.assertEqual(worker_before_warmup, worker_after_warmup)
        self.assertEqual(
            {self.task_id: 0},
            self.candidate_cache.candidate_worker_counts(
                candidate_ids=(self.task_id,),
            ),
        )
        self.assertEqual(2, second.suffix)
        self.assertEqual(TaskScoreBand.TERMINAL, closed.band)
        self.assertEqual(0, self.task_score.count_running_visible_tasks())

    def test_item_driven_empty_rechecks_resume_after_new_item(self) -> None:
        self.worker_catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
                item_allocation_fields=frozenset({"workerId"}),
            )
        )
        self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-1",
                attributes={},
                dynamic_attribute_names=frozenset(),
            )
        )
        original = TaskItem(
            message_id=self.message_id,
            event_code="image.resize",
            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
            payload={},
            allocation_rule={"workerId": {"$eq": "worker-1"}},
        )
        self._create_approve_append_activate(
            descriptor=TaskDescriptor(
                task_id=self.task_id,
                worker_group_id="image-workers",
                task_type=TaskType.ITEM_DRIVEN,
                allocation_rule=None,
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
                empty_close_at_millis=9_999_999_999_999,
            ),
            items=(original,),
        )
        self.item_score.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=(self.message_id,),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=time.time_ns() // 1_000_000,
        )
        config = TaskDispatchConfig(
            task_batch_limit=10,
            per_task_dispatch_limit=10,
            item_claim_lease_duration_millis=1_000,
            max_empty_recheck_times=2,
            empty_recheck_interval_millis=100,
        )

        time.sleep(0.12)
        self.pacer.dispatch_tasks(config=config)
        time.sleep(0.22)
        self.pacer.dispatch_tasks(config=config)
        time.sleep(0.32)
        self.pacer.dispatch_tasks(config=config)
        held = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]

        resumed_item = TaskItem(
            message_id="message-2",
            event_code="image.resize",
            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
            payload={"resume": True},
            allocation_rule={"workerId": {"$eq": "worker-1"}},
        )
        self.task_runtime.append_items(task_id=self.task_id, items=(resumed_item,))
        time.sleep(0.32)
        reset_round = self.pacer.dispatch_tasks(config=config)
        reset = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]
        time.sleep(0.12)
        dispatched = self.pacer.dispatch_tasks(config=config)
        seed = self.deliver_seed_runtime.consume_deliver_seed(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )

        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, held.band)
        self.assertEqual(2, held.suffix)
        self.assertEqual(0, reset_round)
        self.assertEqual(0, reset.suffix)
        self.assertEqual(1, dispatched)
        self.assertIsNotNone(seed)
        assert seed is not None
        self.assertEqual(
            "message-2",
            json.loads(seed.opaque_result_context)["messageId"],
        )

    def test_item_driven_empty_rechecks_close_after_explicit_threshold(self) -> None:
        item = TaskItem(
            message_id=self.message_id,
            event_code="image.resize",
            created_at_millis=time.time_ns() // 1_000_000 - 1_000,
            payload={},
            allocation_rule={"workerId": {"$eq": "worker-1"}},
        )
        self._create_approve_append_activate(
            descriptor=TaskDescriptor(
                task_id=self.task_id,
                worker_group_id="image-workers",
                task_type=TaskType.ITEM_DRIVEN,
                allocation_rule=None,
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
                empty_close_at_millis=0,
            ),
            items=(item,),
        )
        self.item_score.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=(self.message_id,),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=time.time_ns() // 1_000_000,
        )
        config = TaskDispatchConfig(
            task_batch_limit=10,
            per_task_dispatch_limit=10,
            item_claim_lease_duration_millis=1_000,
            max_empty_recheck_times=2,
            empty_recheck_interval_millis=100,
        )

        for wait_seconds in (0.12, 0.22, 0.32):
            time.sleep(wait_seconds)
            self.pacer.dispatch_tasks(config=config)

        closed = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]
        self.assertEqual(TaskScoreBand.TERMINAL, closed.band)


if __name__ == "__main__":
    unittest.main()
