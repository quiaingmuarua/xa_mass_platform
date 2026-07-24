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

try:
    from fastapi.testclient import TestClient

    from kernel_design.examples.worker_adapter_server import (
        create_app as create_worker_adapter_app,
    )
    from kernel_design.examples.polling_phone_worker import (
        PHONE_INSPECT_EVENT_CODE,
        PollingPhoneWorker,
    )
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_worker_adapter_app = None  # type: ignore[assignment]
    PHONE_INSPECT_EVENT_CODE = None  # type: ignore[assignment]
    PollingPhoneWorker = None  # type: ignore[assignment,misc]

from kernel_design.executable_spec import (
    RedisSeedResultRuntime,
    RedisTaskItemScoreBandCore,
    RedisWorkerScoreCore,
    TaskItemScoreBand,
    WorkerScorePolarity,
)
from kernel_design.executable_spec.assembly import (
    TaskType,
    WorkerCommandConsumerClient,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    SeedResult,
    SeedResultCommandClient,
    TaskApprovalStatus,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendStatus,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
    decode_deliver_seed,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None
    and _REDIS_URL
    and TestClient is not None
    and create_worker_adapter_app is not None
    and PollingPhoneWorker is not None,
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
        self.prefix = f"result-closure-{uuid.uuid4().hex}"
        self.config = KernelApplicationConfig(
            redis_url=_REDIS_URL,
            redis_prefix=self.prefix,
            worker_allocation_interval_millis=500,
            running_activation_interval_millis=10,
            task_dispatch_interval_millis=10,
            result_routing_interval_millis=10,
            stop_timeout_millis=1_000,
        )
        self.resources = ResourcesCommandClient(self.config)
        self.application = KernelApplication(self.config)
        assert create_worker_adapter_app is not None
        self.worker_adapter = TestClient(
            create_worker_adapter_app(
                endpoint_manager_id="endpoint-1",
                worker_command_consumer=WorkerCommandConsumerClient(self.config),
                seed_result_commands=SeedResultCommandClient(self.config),
            )
        )
        assert PollingPhoneWorker is not None
        self.phone_worker = PollingPhoneWorker(
            worker_id="worker-1",
            adapter_client=self.worker_adapter,
        )
        self.item_score = RedisTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.worker_score = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=f"wr:{self.prefix}:score",
        )

    def tearDown(self) -> None:
        self.application.stop()
        self.worker_adapter.close()
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_task_driven_process_e2e_reaches_success_and_releases_worker(
        self,
    ) -> None:
        self._run_success_e2e(TaskType.TASK_DRIVEN)

    def test_item_driven_process_e2e_reaches_success_without_candidate_cache(
        self,
    ) -> None:
        self._run_success_e2e(TaskType.ITEM_DRIVEN)
        self.assertEqual(
            0,
            self.redis.exists(
                f"ad:{self.prefix}:candidate:task-1:workers"
            ),
        )
        self.assertEqual(
            0,
            self.redis.exists(f"ad:{self.prefix}:candidate-warmups"),
        )

    def _run_success_e2e(self, task_type: TaskType) -> None:
        group_result = self.resources.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="phone-tools",
                attributes={},
                event_codes=frozenset({PHONE_INSPECT_EVENT_CODE}),
                item_allocation_fields=(
                    frozenset({"workerId"})
                    if task_type is TaskType.ITEM_DRIVEN
                    else frozenset()
                ),
            )
        )
        worker_result = self.resources.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="phone-tools",
                endpoint_manager_id="endpoint-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            )
        )

        self.application.start()
        creation_result = self.application.create_task(
            descriptor=self._task_descriptor(
                task_type,
                worker_group_id="phone-tools",
            )
        )
        approval_result = self.application.approve_task(task_id="task-1")
        append_results = self.application.append_task_items(
            task_id="task-1",
            items=(
                TaskItem(
                    message_id="message-1",
                    event_code=PHONE_INSPECT_EVENT_CODE,
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"phoneNumber": "+14155552671"},
                    allocation_rule=(
                        {"workerId": {"$eq": "worker-1"}}
                        if task_type is TaskType.ITEM_DRIVEN
                        else None
                    ),
                ),
            ),
        )

        self.assertIs(WorkerRuntimeStatus.OK, group_result.status)
        self.assertIs(WorkerRuntimeStatus.OK, worker_result.status)
        self.assertIs(TaskCreationStatus.CREATED, creation_result.status)
        self.assertIs(TaskApprovalStatus.APPROVED, approval_result.status)
        self.assertIs(
            TaskItemAppendStatus.APPENDED,
            append_results["message-1"].status,
        )

        deadline = time.monotonic() + 3
        completed = False
        while time.monotonic() < deadline:
            completed = self.phone_worker.poll_once()
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
            f"tr:{self.prefix}:task:task-1:results",
            "message-1",
        )
        self.assertIsNotNone(stored_payload)
        self.assertEqual(
            {
                "countryCallingCode": 1,
                "e164": "+14155552671",
                "isPossible": True,
                "isValid": True,
                "regionCode": "US",
            },
            json.loads(stored_payload),
        )
        self.assertEqual(
            0,
            self.redis.exists(f"rr:{self.prefix}:seed-results"),
        )

        self.application.stop()
        worker_candidates = {}
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline and "worker-1" not in worker_candidates:
            worker_candidates = self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id="phone-tools",
                limit=10,
            )
            if "worker-1" not in worker_candidates:
                time.sleep(self.worker_score.SLOT_MILLIS / 1_000)
        self.assertIn("worker-1", worker_candidates)

    def test_adapter_rejection_evidence_demotes_then_reconnect_restores_worker(
        self,
    ) -> None:
        self.resources.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
                item_allocation_fields=frozenset({"workerId"}),
            )
        )
        declaration = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id="image-workers",
            endpoint_manager_id="endpoint-1",
            attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset(),
        )
        self.resources.upsert_worker(declaration=declaration)

        self.application.start()
        self.application.create_task(
            descriptor=self._task_descriptor(TaskType.ITEM_DRIVEN)
        )
        self.application.approve_task(task_id="task-1")
        self.application.append_task_items(
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

        consumer = WorkerCommandConsumerClient(self.config)
        command = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and command is None:
            command = consumer.consume_worker_command(
                endpoint_manager_id="endpoint-1",
                worker_id="worker-1",
            )
            if command is None:
                time.sleep(0.02)
        self.assertIsNotNone(command)
        assert command is not None
        seed = decode_deliver_seed(command.opaque_item)
        self.assertIsNotNone(seed)
        assert seed is not None
        RedisSeedResultRuntime(
            self.redis,
            prefix=self.prefix,
        ).append_seed_results(
            results=(
                SeedResult(
                    command_id=command.command_id,
                    opaque_result_context=seed.opaque_result_context,
                    outcome_code="3001",
                ),
            )
        )

        recovery_state = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            recovery_state = self.worker_score.get_score_states(
                home_bucket_id="image-workers",
                worker_ids=["worker-1"],
            )["worker-1"]
            if (
                recovery_state is not None
                and recovery_state.polarity is WorkerScorePolarity.RECOVERY_RECHECK
            ):
                break
            time.sleep(0.02)

        self.assertIsNotNone(recovery_state)
        assert recovery_state is not None
        self.assertIs(
            WorkerScorePolarity.RECOVERY_RECHECK,
            recovery_state.polarity,
        )

        self.resources.upsert_worker(declaration=declaration)
        hot_state = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertIsNotNone(hot_state)
        assert hot_state is not None
        self.assertIs(WorkerScorePolarity.HOT_ACQUIRE, hot_state.polarity)
        self.assertEqual(1, hot_state.dirty)
        self.assertEqual(
            0,
            self.redis.exists(
                f"ad:{self.prefix}:candidate:task-1:workers"
            ),
        )

    @staticmethod
    def _task_descriptor(
        task_type: TaskType,
        *,
        worker_group_id: str = "image-workers",
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id="task-1",
            worker_group_id=worker_group_id,
            task_type=task_type,
            allocation_rule=(
                {"attributes.runtime": {"$eq": "python"}}
                if task_type is TaskType.TASK_DRIVEN
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
