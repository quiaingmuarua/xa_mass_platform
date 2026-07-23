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
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_worker_adapter_app = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    RedisWorkerScoreCore,
    TaskItemScoreBand,
    WorkerScorePolarity,
)
from kernel_design.executable_spec.assembly import (
    TaskType,
    DeliverSeedConsumerClient,
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
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None
    and _REDIS_URL
    and TestClient is not None
    and create_worker_adapter_app is not None,
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
                deliver_seed_consumer=DeliverSeedConsumerClient(self.config),
                seed_result_commands=SeedResultCommandClient(self.config),
            )
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
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
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
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            )
        )

        self.application.start()
        creation_result = self.application.create_task(
            descriptor=self._task_descriptor(task_type)
        )
        approval_result = self.application.approve_task(task_id="task-1")
        append_results = self.application.append_task_items(
            task_id="task-1",
            items=(
                TaskItem(
                    message_id="message-1",
                    event_code="image.resize",
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"source": "input"},
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
        poll_response = None
        while time.monotonic() < deadline:
            poll_response = self.worker_adapter.post(
                "/workers/worker-1/commands:poll"
            )
            if poll_response.status_code == 200:
                break
            self.assertEqual(204, poll_response.status_code)
            time.sleep(0.02)
        self.assertIsNotNone(poll_response)
        assert poll_response is not None
        self.assertEqual(200, poll_response.status_code)
        command = poll_response.json()
        self.assertEqual("TASK_SEED", command["messageType"])
        delivery_item = json.loads(command["opaqueDeliveryItem"])
        self.assertEqual("image.resize", delivery_item["eventCode"])

        result_response = self.worker_adapter.post(
            "/workers/worker-1/results",
            json={
                "commandId": command["commandId"],
                "messageType": "TASK_SEED_RESULT",
                "opaqueResultContext": command["opaqueResultContext"],
                "outcomeCode": "200",
                "opaqueResultPayload": json.dumps(
                    {
                        "handled": delivery_item["payload"]["source"],
                        "runtime": "polling",
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                ),
            },
        )
        self.assertEqual(202, result_response.status_code)
        self.assertEqual({"accepted": True}, result_response.json())

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
            {"handled": "input", "runtime": "polling"},
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
                home_bucket_id="image-workers",
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

        consumer = DeliverSeedConsumerClient(self.config)
        seeds = {}
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and not seeds:
            seeds = consumer.consume_deliver_seeds(worker_ids=("worker-1",))
            if not seeds:
                time.sleep(0.02)
        self.assertIn("worker-1", seeds)
        SeedResultCommandClient(self.config).append_seed_results(
            results=(
                SeedResult(
                    opaque_result_context=seeds[
                        "worker-1"
                    ].opaque_result_context,
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
    def _task_descriptor(task_type: TaskType) -> TaskDescriptor:
        return TaskDescriptor(
            task_id="task-1",
            worker_group_id="image-workers",
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
