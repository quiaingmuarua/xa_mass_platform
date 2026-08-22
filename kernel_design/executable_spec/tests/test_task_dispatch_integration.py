from __future__ import annotations

import json
import os
import time
import unittest
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    DeliveryCommand,
    WorkerCommandAppendStatus,
    WorkerCommandOfferStatus,
    DeliveryEndpoint,
    DueTaskItemAdmissionPolicy,
    RunningSoftLimitSystemAdmissionPolicy,
    RedisCandidateWorkerCache,
    RedisWorkerCommandRuntime,
    RedisTaskRuntime,
    RedisTaskResourceCatalog,
    RedisTaskItemScoreBandCore,
    RedisTaskScoreBandCore,
    RedisWorkerScoreCore,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    TaskCreationStatus,
    TaskCreationResult,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskDispatchConfig,
    TaskDispatchPacer,
    TaskCallItemSubmission,
    TaskCallSubmissionStatus,
    TaskRunningActivationConfig,
    TaskRunningActivationPacer,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
    TaskItemScoreBand,
    TaskScoreBand,
    TaskScoreBandCore,
    WorkerAllocationMechanism,
    WorkerCandidateMatcher,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)
from kernel_design.executable_spec.tests.redis_test_scope import RedisTestScope
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
        self.test_scope = RedisTestScope.create("task_dispatch")
        self.keyspace = self.test_scope.keyspace
        self.task_id = "task-1"
        self.message_id = "message-1"
        self.task_score = RedisTaskScoreBandCore(
            self.redis,
            keyspace=self.keyspace,
        )
        self.item_score = RedisTaskItemScoreBandCore(
            self.redis,
            keyspace=self.keyspace,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            self.task_score,
            self.item_score,
            keyspace=self.keyspace,
        )
        self.task_catalog = RedisTaskResourceCatalog(
            self.redis,
            keyspace=self.keyspace,
        )
        self.candidate_cache = RedisCandidateWorkerCache(
            self.redis,
            keyspace=self.keyspace,
        )
        self.warmup_schedule = RedisCandidateWarmupSchedule(
            self.redis,
            keyspace=self.keyspace,
        )
        self.worker_command_runtime = RedisWorkerCommandRuntime(
            self.redis,
            keyspace=self.keyspace,
        )
        self.worker_score = RedisWorkerScoreCore(
            self.redis,
            keyspace=self.keyspace,
        )
        self.worker_catalog = RedisWorkerResourceCatalog(
            self.redis,
            keyspace=self.keyspace,
        )
        self.worker_runtime = RedisWorkerRuntime(
            self.redis,
            self.worker_score,
            keyspace=self.keyspace,
        )
        candidate_acquirer = WorkerCandidateAcquirer(
            self.candidate_cache,
            self.worker_score,
            WorkerCandidateMatcher(
                self.worker_catalog,
            ),
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
            self.worker_command_runtime,
            self.item_score,
            task_item_dispatcher,
        )
        self.task_call_submission = TaskCallItemSubmission(
            self.task_score,
            self.task_runtime,
        )

    def test_adapter_mailbox_worker_field_has_one_atomic_consumer(self) -> None:
        seed = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"image.resize","payload":{}}',
            opaque_result_context='{"taskId":"task-1"}',
        )
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.APPENDED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": seed}
            ),
        )
        conflicting_seed = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"other","payload":{}}',
            opaque_result_context='{"taskId":"task-2"}',
        )
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": conflicting_seed}
            ),
        )
        competing_runtime = RedisWorkerCommandRuntime(
            self.redis,
            keyspace=self.keyspace,
        )

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = tuple(
                executor.map(
                    lambda runtime: runtime.consume_worker_command(
                        endpoint_manager_id="endpoint-manager-1",
                        worker_id="worker-1",
                    ),
                    (self.worker_command_runtime, competing_runtime),
                )
            )

        self.assertEqual(1, sum(bool(result) for result in results))
        self.assertEqual(
            [conflicting_seed],
            [result for result in results if result],
        )

    def test_direct_offer_and_task_append_share_one_worker_slot(self) -> None:
        direct = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"direct","payload":{}}',
            opaque_result_context='{"authority":"direct"}',
        )
        task = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"task","payload":{}}',
            opaque_result_context='{"authority":"task"}',
        )

        self.assertEqual(
            {"worker-1": WorkerCommandOfferStatus.OFFERED},
            self.worker_command_runtime.offer_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": direct},
            ),
        )
        self.assertEqual(
            {"worker-1": WorkerCommandOfferStatus.OCCUPIED},
            self.worker_command_runtime.offer_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": direct},
            ),
        )
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": task},
            ),
        )
        self.assertEqual(
            task,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )

    def test_consumed_direct_offer_is_not_recalled_by_later_task(self) -> None:
        direct = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"direct","payload":{}}',
            opaque_result_context='{"authority":"direct"}',
        )
        task = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"task","payload":{}}',
            opaque_result_context='{"authority":"task"}',
        )
        self.worker_command_runtime.offer_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": direct},
        )

        consumed_direct = self.worker_command_runtime.consume_worker_command(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": task},
        )

        self.assertEqual(direct, consumed_direct)
        self.assertEqual(
            task,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )

    def test_adapter_mailbox_bounded_batches_consume_sparse_worker_fields(
        self,
    ) -> None:
        commands_by_worker_id = {
            f"mailbox-worker-{index}": self._worker_command(
                worker_id=f"mailbox-worker-{index}",
                opaque_delivery_item=f'{{"index":{index}}}',
                opaque_result_context=f'{{"index":{index}}}',
            )
            for index in range(3)
        }
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-batch",
            worker_commands_by_worker_id=commands_by_worker_id,
        )

        consumed: dict[str, DeliveryCommand] = {}
        for _ in range(10):
            commands = self.worker_command_runtime.consume_worker_commands(
                endpoint_manager_id="endpoint-manager-batch",
                limit=1,
            )
            consumed.update(commands)
            if not commands:
                break
        else:
            self.fail("Worker command HASH did not drain")

        self.assertEqual(
            commands_by_worker_id,
            consumed,
        )

    def test_point_poll_and_adapter_batch_compete_for_one_command(self) -> None:
        command = self._worker_command(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"image.resize","payload":{}}',
            opaque_result_context='{"taskId":"task-1"}',
        )
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": command},
        )
        competing_runtime = RedisWorkerCommandRuntime(
            self.redis,
            keyspace=self.keyspace,
        )

        with ThreadPoolExecutor(max_workers=2) as executor:
            point_future = executor.submit(
                self.worker_command_runtime.consume_worker_command,
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            )
            batch_future = executor.submit(
                competing_runtime.consume_worker_commands,
                endpoint_manager_id="endpoint-manager-1",
                limit=10,
            )
            point_result = point_future.result()
            batch_result = batch_future.result()

        consumed_commands = [
            candidate
            for candidate in (
                point_result,
                batch_result.get("worker-1"),
            )
            if candidate is not None
        ]
        self.assertEqual([command], consumed_commands)
        self.assertEqual(
            0,
            self.redis.hlen(
                f"{self.keyspace.base}:delivery:commands:endpoint-manager-1"
            ),
        )

    def tearDown(self) -> None:
        self.test_scope.cleanup(self.redis)

    @staticmethod
    def _worker_command(
        *,
        worker_id: str,
        opaque_delivery_item: str,
        opaque_result_context: str,
    ) -> DeliveryCommand:
        decoded_delivery = json.loads(opaque_delivery_item)
        if (
            isinstance(decoded_delivery, dict)
            and isinstance(decoded_delivery.get("eventCode"), str)
            and "payload" in decoded_delivery
        ):
            message_type = decoded_delivery["eventCode"]
            payload = json.dumps(
                decoded_delivery["payload"],
                sort_keys=True,
                separators=(",", ":"),
            )
        else:
            message_type = "test.event"
            payload = opaque_delivery_item
        return DeliveryCommand.create(
            src=DeliveryEndpoint.TASK,
            dst=DeliveryEndpoint.WORKER,
            message_type=message_type,
            execute_before_millis=int(time.time() * 1_000) + 5_000,
            payload=payload,
            forward=opaque_result_context,
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
            config=TaskRunningActivationConfig(task_batch_limit=10)
        )
        return created, approved, appended, activated

    def test_precomputed_allocation_redis_proof_uses_candidate_cache(self) -> None:
        descriptor = TaskDescriptor(
            task_id=self.task_id,
            worker_group_id="image-workers",
            worker_allocation_mechanism=(
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
            ),
            idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
            allocation_rule={"worker.runtime": {"$eq": "python"}},
            config={
                "priority": "0",
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
            f"{self.keyspace.base}:dispatch:candidate_warmups",
            self.task_id,
        )
        candidate_count_before_worker_registration = (
            self.candidate_cache.candidate_worker_counts(
                candidate_ids=(self.task_id,),
            )[self.task_id]
        )

        group_result = self.worker_catalog.register_worker_group(
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
                worker_properties={"runtime": "python"},
            )
        )
        unmatched_worker = self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-2",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-2",
                worker_properties={"runtime": "java"},
            )
        )
        time.sleep((self.worker_score.SLOT_MILLIS + 20) / 1_000)
        dispatched_without_precomputation = self.pacer.dispatch_tasks(
            config=TaskDispatchConfig(
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
            hot_eligibility_floor_millis=None,
            limit=10,
        )
        time.sleep((self.task_score.SLOT_MILLIS + 20) / 1_000)

        dispatch_started_millis = time.time_ns() // 1_000_000
        dispatched = self.pacer.dispatch_tasks(
            config=TaskDispatchConfig(
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
            f"{self.keyspace.base}:dispatch:candidate_warmups",
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
        command = self.worker_command_runtime.consume_worker_command(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )
        self.assertIsNotNone(command)
        assert command is not None
        self.assertIs(command.src, DeliveryEndpoint.TASK)
        self.assertIs(command.dst, DeliveryEndpoint.WORKER)
        self.assertEqual("image.resize", command.message_type)
        delivery_item = json.loads(command.payload)
        result_context = json.loads(command.forward)
        self.assertEqual({"source": "s3://input"}, delivery_item)
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
            command.execute_before_millis,
            dispatch_started_millis + 3_000,
        )
        self.assertEqual(
            None,
            self.worker_command_runtime.consume_worker_command(
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
                worker_allocation_mechanism=(
                    WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                ),
                idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
                allocation_rule={},
                config={
                    "priority": "0",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
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
            self.redis.unlink(
                *(
                    key
                    for task_id in task_ids
                    for key in (
                        f"{self.keyspace.base}:task:{task_id}:descriptor",
                        f"{self.keyspace.base}:task:{task_id}:items",
                        f"{self.keyspace.base}:task:{task_id}:item_score",
                    )
                )
            )

    def test_direct_allocation_redis_proof_uses_complete_item_rules(self) -> None:
        group_result = self.worker_catalog.register_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        worker_results = tuple(
            self.worker_runtime.upsert_worker(
                declaration=WorkerDeclaration(
                    worker_id=f"worker-{index}",
                    worker_group_id="image-workers",
                    endpoint_manager_id=f"endpoint-manager-{index}",
                    worker_properties={"runtime": "python"},
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
                    worker_allocation_mechanism=(
                        WorkerAllocationMechanism.DIRECT_ITEM_RULE
                    ),
                    idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
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
            f"{self.keyspace.base}:dispatch:candidate_warmups",
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
                f"{self.keyspace.base}:dispatch:candidate_warmups",
                self.task_id,
            )
        )
        for index in (1, 2):
            worker_id = f"worker-{index}"
            command = self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id=f"endpoint-manager-{index}",
                worker_id=worker_id,
            )
            self.assertIsNotNone(command)
            assert command is not None
            self.assertEqual(
                f"message-{index}",
                json.loads(command.forward)["messageId"],
            )
            self.assertEqual(
                {"target": f"worker-{index}"},
                json.loads(command.payload),
            )

    def test_expired_item_finalizes_before_worker_acquisition(self) -> None:
        now_millis = time.time_ns() // 1_000_000
        expire_at_millis = now_millis + 60_000
        descriptor = TaskDescriptor(
            task_id=self.task_id,
            worker_group_id="image-workers",
            worker_allocation_mechanism=(
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
            ),
            idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
            allocation_rule={},
            config={
                "priority": "0",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
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
                f"{self.keyspace.base}:dispatch:candidate:"
                f"{self.task_id}:workers"
            ),
        )
        self.assertEqual(
            0,
            self.redis.hlen(
                f"{self.keyspace.base}:delivery:commands:endpoint-manager-1"
            ),
        )

    def test_close_when_idle_task_closes_immediately(self) -> None:
        self.worker_catalog.register_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-1",
                worker_properties={},
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
                worker_allocation_mechanism=(
                    WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                ),
                idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
                allocation_rule={},
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
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
        )

        time.sleep(0.12)
        worker_before_warmup = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=("worker-1",),
        )["worker-1"]
        self.pacer.dispatch_tasks(config=config)
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
        closed = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]

        self.assertEqual(0, warmed)
        self.assertEqual(worker_before_warmup, worker_after_warmup)
        self.assertEqual(
            {self.task_id: 0},
            self.candidate_cache.candidate_worker_counts(
                candidate_ids=(self.task_id,),
            ),
        )
        self.assertEqual(TaskScoreBand.TERMINAL, closed.band)
        self.assertEqual(0, self.task_score.count_running_capacity_tasks())

    def test_park_when_idle_task_resumes_through_call_submission(self) -> None:
        self.worker_catalog.register_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        self.worker_runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-manager-1",
                worker_properties={},
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
                worker_allocation_mechanism=(
                    WorkerAllocationMechanism.DIRECT_ITEM_RULE
                ),
                idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
                allocation_rule=None,
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
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
        )

        time.sleep(0.12)
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
        submitted = self.task_call_submission.submit(
            task_id=self.task_id,
            items=(resumed_item,),
        )
        time.sleep(0.12)
        dispatched = self.pacer.dispatch_tasks(config=config)
        command = self.worker_command_runtime.consume_worker_command(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )

        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, held.band)
        self.assertEqual(TaskScoreBandCore.MAX_SUFFIX, held.suffix)
        self.assertEqual(
            (TaskScoreBandCore.MAX_TIME_SLOT - 1)
            * TaskScoreBandCore.SLOT_MILLIS,
            held.time_millis,
        )
        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, submitted.status)
        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            submitted.item_results["message-2"].status,
        )
        self.assertEqual(1, dispatched)
        self.assertIsNotNone(command)
        assert command is not None
        self.assertEqual(
            "message-2",
            json.loads(command.forward)["messageId"],
        )

    def test_empty_admission_task_accepts_first_call_and_activates(self) -> None:
        descriptor = TaskDescriptor(
            task_id=self.task_id,
            worker_group_id="image-workers",
            worker_allocation_mechanism=(
                WorkerAllocationMechanism.DIRECT_ITEM_RULE
            ),
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
            allocation_rule=None,
            config={
                "priority": "80",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
        )
        created = self.task_runtime.create_task(descriptor=descriptor, suffix=5)
        approved = self.task_lifecycle.approve_task(task_id=self.task_id)
        admission = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]
        submitted = self.task_call_submission.submit(
            task_id=self.task_id,
            items=(
                TaskItem(
                    message_id=self.message_id,
                    event_code="image.resize",
                    created_at_millis=time.time_ns() // 1_000_000 - 1_000,
                    payload={},
                    allocation_rule={},
                ),
            ),
        )

        time.sleep((2 * self.task_score.SLOT_MILLIS + 20) / 1_000)
        activated = self.activation_pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(task_batch_limit=10)
        )
        running = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]

        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskApprovalStatus.APPROVED, approved.status)
        self.assertEqual(TaskScoreBand.ADMISSION_VISIBLE, admission.band)
        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, submitted.status)
        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            submitted.item_results[self.message_id].status,
        )
        self.assertEqual(1, activated)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, running.band)

    def test_direct_allocation_can_close_immediately_when_idle(self) -> None:
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
                worker_allocation_mechanism=(
                    WorkerAllocationMechanism.DIRECT_ITEM_RULE
                ),
                idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
                allocation_rule=None,
                config={
                    "priority": "80",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
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
        )

        time.sleep(0.12)
        self.pacer.dispatch_tasks(config=config)

        closed = self.task_score.get_score_states(task_ids=(self.task_id,))[
            self.task_id
        ]
        self.assertEqual(TaskScoreBand.TERMINAL, closed.band)


if __name__ == "__main__":
    unittest.main()
