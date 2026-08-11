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

    from kernel_design.runtime_server import (
        create_app as create_runtime_app,
    )
except (ImportError, RuntimeError):  # pragma: no cover - missing HTTP dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_runtime_app = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    RedisWorkerScoreCore,
    TaskItemScoreBand,
    WorkerScorePolarity,
)
from kernel_design.executable_spec.assembly import (
    TaskType,
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    DeliveryEndpoint,
    DeliveryReport,
    WorkerCommandConsumerClient,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    WorkerResultCommandClient,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
    TaskItemAppendStatus,
    TaskItem,
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
    redis_module is not None
    and _REDIS_URL
    and TestClient is not None
    and create_runtime_app is not None,
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
        self.command_consumer = WorkerCommandConsumerClient(self.config)
        self.result_commands = WorkerResultCommandClient(self.config)
        assert create_runtime_app is not None
        self.runtime_server_context = TestClient(
            create_runtime_app(
                application=self.application,
            )
        )
        self.runtime_server = self.runtime_server_context.__enter__()
        self.runtime_server_open = True
        self.item_score = RedisTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            RedisTaskScoreBandCore(
                self.redis,
                score_key=f"tr:{self.prefix}:task:score",
            ),
            self.item_score,
            prefix=self.prefix,
        )
        self.worker_score = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=f"wr:{self.prefix}:score",
        )

    def tearDown(self) -> None:
        self._close_runtime_server()
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
        creation_response = self.runtime_server.post(
            "/tasks",
            json=self._task_request(
                task_type,
                worker_group_id="phone-tools",
            ),
        )
        approval_response = self.runtime_server.post("/tasks/task-1/approve")
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
                        if task_type is TaskType.ITEM_DRIVEN
                        else None
                    ),
                ),
            ),
        )

        self.assertEqual(WorkerRuntimeStatus.OK, group_result.status)
        self.assertEqual(WorkerRuntimeStatus.OK, worker_result.status)
        self.assertEqual(201, creation_response.status_code)
        self.assertEqual(200, approval_response.status_code)
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
            f"tr:{self.prefix}:task:task-1:results",
            "message-1",
        )
        self.assertIsNotNone(stored_payload)
        self.assertEqual(
            _FIXED_WORKER_RESULT,
            json.loads(stored_payload),
        )
        self.assertEqual(
            0,
            self.redis.exists(f"rr:{self.prefix}:worker-results"),
        )

        self._close_runtime_server()
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

    def test_adapter_rejection_demotes_and_resource_refresh_preserves_recovery(
        self,
    ) -> None:
        self.resources.upsert_worker_group(
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
        self.runtime_server.post(
            "/tasks",
            json=self._task_request(TaskType.ITEM_DRIVEN),
        )
        self.runtime_server.post("/tasks/task-1/approve")
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

        self.resources.upsert_worker(
            declaration=worker_declaration,
        )
        refreshed_state = self.worker_score.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertEqual(recovery_state, refreshed_state)
        self.assertEqual(
            0,
            self.redis.exists(
                f"ad:{self.prefix}:candidate:task-1:workers"
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

    def _close_runtime_server(self) -> None:
        if self.runtime_server_open:
            self.runtime_server_context.__exit__(None, None, None)
            self.runtime_server_open = False

    @staticmethod
    def _task_request(
        task_type: TaskType,
        *,
        worker_group_id: str = "image-workers",
    ) -> dict[str, object]:
        return {
            "taskId": "task-1",
            "workerGroupId": worker_group_id,
            "taskType": task_type.value,
            "allocationRule": (
                {"worker.runtime": {"$eq": "python"}}
                if task_type is TaskType.TASK_DRIVEN
                else None
            ),
            "config": {
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        }


if __name__ == "__main__":
    unittest.main()
