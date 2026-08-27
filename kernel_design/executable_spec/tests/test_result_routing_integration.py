from __future__ import annotations

import json
import os
import time
import unittest

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    RedisWorkerScoreCore,
    TaskItemScoreBand,
    WorkerScorePolarity,
)
from kernel_design.executable_spec.tests.redis_test_scope import RedisTestScope
from kernel_design.executable_spec.assembly import (
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    DeliveryEndpoint,
    DeliveryReport,
    WorkerCommandConsumerClient,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    TaskApprovalStatus,
    TaskCreationStatus,
    TaskDescriptor,
    WorkerResultCommandClient,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
    TaskItemAppendStatus,
    TaskItem,
    TaskIdleDisposition,
    WorkerAllocationMechanism,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")
_PHONE_INSPECT_EVENT_CODE = "telecom.phone.inspect"
_FIXED_WORKER_RESULT = {
    "countryCallingCode": 1,
    "e164": "+14155552671",
    "isPossible": True,
    "isValid": True,
    "regionCode": "US",
}


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run result-routing Redis closure proof",
)
class ResultRoutingIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        assert redis_module is not None
        assert _REDIS_URL is not None
        cls.redis = redis_module.Redis.from_url(_REDIS_URL, decode_responses=False)
        cls.redis.ping()

    def setUp(self) -> None:
        assert _REDIS_URL is not None
        self.test_scope = RedisTestScope.create("result_routing")
        self.keyspace = self.test_scope.keyspace
        self.config = KernelApplicationConfig(
            redis_url=_REDIS_URL,
            redis_scope=self.test_scope.scope,
            worker_allocation_interval_millis=500,
            task_initialization_interval_millis=10,
            task_dispatch_interval_millis=10,
            result_routing_interval_millis=10,
            stop_timeout_millis=1_000,
        )
        self.resources = ResourcesCommandClient(self.config)
        self.application = KernelApplication(self.config)
        self.command_consumer = WorkerCommandConsumerClient(self.config)
        self.result_commands = WorkerResultCommandClient(self.config)
        self.application.start()
        self.application_open = True
        self.item_score = RedisTaskItemScoreBandCore(
            self.redis,
            keyspace=self.keyspace,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            RedisTaskScoreBandCore(
                self.redis,
                keyspace=self.keyspace,
            ),
            self.item_score,
            keyspace=self.keyspace,
        )
        self.worker_score = RedisWorkerScoreCore(
            self.redis,
            keyspace=self.keyspace,
        )

    def tearDown(self) -> None:
        self._close_application()
        self.test_scope.cleanup(self.redis)

    def test_precomputed_allocation_e2e_reaches_success_and_releases_worker(
        self,
    ) -> None:
        self._run_success_e2e(
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        )

    def test_direct_allocation_e2e_reaches_success_without_candidate_cache(
        self,
    ) -> None:
        self._run_success_e2e(WorkerAllocationMechanism.DIRECT_ITEM_RULE)
        self.assertEqual(
            0,
            self.redis.exists(
                f"{self.keyspace.base}:dispatch:candidate:task-1:workers"
            ),
        )

    def _run_success_e2e(
        self,
        allocation_mechanism: WorkerAllocationMechanism,
    ) -> None:
        group_result = self.resources.register_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="phone-tools",
                attributes={},
                event_codes=frozenset({_PHONE_INSPECT_EVENT_CODE}),
            )
        )
        worker_result = self.resources.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="phone-tools",
                endpoint_manager_id=SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                worker_properties={"runtime": "python"},
            )
        )
        creation_result = self.application.create_task(
            descriptor=self._task_descriptor(
                allocation_mechanism,
                worker_group_id="phone-tools",
            ),
        )
        approval_result = self.application.approve_task(task_id="task-1")
        append_result = self.task_runtime.append_items(
            task_id="task-1",
            items=(
                TaskItem(
                    message_id="message-1",
                    event_code=_PHONE_INSPECT_EVENT_CODE,
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"phoneNumber": "+14155552671"},
                    allocation_rule=(
                        {"workerId": {"$eq": "worker-1"}}
                        if allocation_mechanism
                        is WorkerAllocationMechanism.DIRECT_ITEM_RULE
                        else None
                    ),
                ),
            ),
        )

        self.assertEqual(WorkerRuntimeStatus.OK, group_result.status)
        self.assertEqual(WorkerRuntimeStatus.OK, worker_result.status)
        self.assertIs(TaskCreationStatus.CREATED, creation_result.status)
        self.assertIs(TaskApprovalStatus.APPROVED, approval_result.status)
        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            append_result["message-1"].status,
        )

        deadline = time.monotonic() + 3
        completed = False
        while time.monotonic() < deadline:
            completed = self._execute_phone_command(
                endpoint_manager_id=SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                worker_id="worker-1",
            )
            if completed:
                break
            time.sleep(0.02)
        self.assertTrue(completed)

        state = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            state = self.item_score.get_item_score_states(
                task_id="task-1",
                message_ids=("message-1",),
            )["message-1"]
            if state is not None and state.band is TaskItemScoreBand.FINAL_SUCCESS:
                break
            time.sleep(0.02)

        self.assertIsNotNone(state)
        assert state is not None
        self.assertIs(TaskItemScoreBand.FINAL_SUCCESS, state.band)
        stored_payload = self.redis.hget(
            f"{self.keyspace.base}:task:task-1:results",
            "message-1",
        )
        self.assertIsNotNone(stored_payload)
        self.assertEqual(
            _FIXED_WORKER_RESULT,
            json.loads(stored_payload),
        )
        self.assertEqual(
            0,
            self.redis.exists(f"{self.keyspace.base}:result:routing"),
        )

        self._close_application()
        worker_candidates = {}
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline and "worker-1" not in worker_candidates:
            worker_candidates = self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id="phone-tools",
                hot_eligibility_floor_millis=None,
                limit=10,
            )
            if "worker-1" not in worker_candidates:
                time.sleep(self.worker_score.SLOT_MILLIS / 1_000)
        self.assertIn("worker-1", worker_candidates)

    def test_adapter_rejection_releases_and_resource_refresh_preserves_hot(
        self,
    ) -> None:
        self.resources.register_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        worker_declaration = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id="image-workers",
            endpoint_manager_id="endpoint-1",
            worker_properties={"runtime": "python"},
        )
        self.resources.upsert_worker(
            declaration=worker_declaration,
        )
        creation_result = self.application.create_task(
            descriptor=self._task_descriptor(
                WorkerAllocationMechanism.DIRECT_ITEM_RULE
            ),
        )
        approval_result = self.application.approve_task(task_id="task-1")
        self.assertIs(TaskCreationStatus.CREATED, creation_result.status)
        self.assertIs(TaskApprovalStatus.APPROVED, approval_result.status)
        append_result = self.task_runtime.append_items(
            task_id="task-1",
            items=(
                TaskItem(
                    message_id="message-1",
                    event_code="image.resize",
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"source": "input"},
                    allocation_rule={"workerId": {"$eq": "worker-1"}},
                ),
            ),
        )
        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            append_result["message-1"].status,
        )

        command = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and command is None:
            command = self.command_consumer.consume_worker_command(
                endpoint_manager_id="endpoint-1",
                worker_id="worker-1",
            )
            if command is not None:
                break
            time.sleep(0.02)
        self.assertIsNotNone(command)
        assert command is not None
        leased_state = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertIsNotNone(leased_state)
        assert leased_state is not None
        self.assertIs(WorkerScorePolarity.HOT_ACQUIRE, leased_state.polarity)
        accepted = self.result_commands.append_worker_results(
            results=(
                DeliveryReport.from_command(
                    command=command,
                    src=DeliveryEndpoint.ADAPTER,
                    source_id="endpoint-1",
                    outcome_code="23002",
                    payload="null",
                ),
            )
        )
        self.assertEqual(1, accepted)

        released_state = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            released_state = self.worker_score.get_score_states(
                home_bucket_id="image-workers",
                worker_ids=["worker-1"],
            )["worker-1"]
            if (
                released_state is not None
                and released_state.polarity is WorkerScorePolarity.HOT_ACQUIRE
                and released_state.score != leased_state.score
            ):
                break
            time.sleep(0.02)

        self.assertIsNotNone(released_state)
        assert released_state is not None
        self.assertIs(
            WorkerScorePolarity.HOT_ACQUIRE,
            released_state.polarity,
        )

        self.resources.upsert_worker(
            declaration=worker_declaration,
        )
        refreshed_state = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertEqual(released_state, refreshed_state)
        self.assertEqual(
            0,
            self.redis.exists(
                f"{self.keyspace.base}:dispatch:candidate:task-1:workers"
            ),
        )

    def _execute_phone_command(
        self,
        *,
        endpoint_manager_id: str,
        worker_id: str,
    ) -> bool:
        command = self.command_consumer.consume_worker_command(
            endpoint_manager_id=endpoint_manager_id,
            worker_id=worker_id,
        )
        if command is None:
            return False
        self.assertIs(command.src, DeliveryEndpoint.TASK)
        self.assertEqual(_PHONE_INSPECT_EVENT_CODE, command.message_type)
        self.assertEqual(
            {"phoneNumber": "+14155552671"},
            json.loads(command.payload),
        )
        accepted = self.result_commands.append_worker_results(
            results=(
                DeliveryReport.from_command(
                    command=command,
                    src=DeliveryEndpoint.WORKER,
                    source_id=worker_id,
                    outcome_code="200",
                    payload=json.dumps(
                        _FIXED_WORKER_RESULT,
                        allow_nan=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    ),
                ),
            )
        )
        self.assertEqual(1, accepted)
        return True

    def _close_application(self) -> None:
        if self.application_open:
            self.application.stop()
            self.application_open = False

    @staticmethod
    def _task_descriptor(
        allocation_mechanism: WorkerAllocationMechanism,
        *,
        worker_group_id: str = "image-workers",
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id="task-1",
            worker_group_id=worker_group_id,
            worker_allocation_mechanism=allocation_mechanism,
            idle_disposition=(
                TaskIdleDisposition.CLOSE_WHEN_IDLE
                if allocation_mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else TaskIdleDisposition.PARK_WHEN_IDLE
            ),
            allocation_rule=(
                {"worker.runtime": {"$eq": "python"}}
                if allocation_mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else None
            ),
            config={
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        )


if __name__ == "__main__":
    unittest.main()
