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
    from kernel_design.examples.polling_phone_worker import (
        PHONE_INSPECT_EVENT_CODE,
        PollingPhoneWorker,
    )
except (ImportError, RuntimeError):  # pragma: no cover - missing example dependencies
    TestClient = None  # type: ignore[assignment,misc]
    create_runtime_app = None  # type: ignore[assignment]
    PHONE_INSPECT_EVENT_CODE = None  # type: ignore[assignment]
    PollingPhoneWorker = None  # type: ignore[assignment,misc]

from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    RedisWorkerScoreCore,
    TaskItemScoreBand,
    WorkerScorePolarity,
)
from kernel_design.executable_spec.assembly import (
    TaskType,
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    WorkerCommandConsumerClient,
    WorkerCommandEnvelope,
    WorkerMessageType,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    SeedResultCommandClient,
    TaskItemAppendStatus,
    decode_deliver_seed,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None
    and _REDIS_URL
    and TestClient is not None
    and create_runtime_app is not None
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
        assert create_runtime_app is not None
        self.runtime_server_context = TestClient(
            create_runtime_app(
                application=self.application,
                resources_client=self.resources,
                worker_command_consumer=WorkerCommandConsumerClient(
                    self.config
                ),
                seed_result_commands=SeedResultCommandClient(self.config),
            )
        )
        self.runtime_server = self.runtime_server_context.__enter__()
        self.runtime_server_open = True
        assert PollingPhoneWorker is not None
        self.phone_worker = PollingPhoneWorker(
            worker_id="worker-1",
            delivery_client=self.runtime_server,
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
        group_response = self.runtime_server.put(
            "/worker-groups/phone-tools",
            json={
                "attributes": {},
                "eventCodes": [PHONE_INSPECT_EVENT_CODE],
                "itemAllocationFields": (
                    ["workerId"]
                    if task_type is TaskType.ITEM_DRIVEN
                    else []
                ),
            },
        )
        worker_response = self.runtime_server.put(
            "/worker-groups/phone-tools/workers/worker-1",
            json={
                "endpointManagerId": SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                "attributes": {"runtime": "python"},
                "dynamicAttributeNames": [],
            },
        )
        creation_response = self.runtime_server.post(
            "/tasks",
            json=self._task_request(
                task_type,
                worker_group_id="phone-tools",
            ),
        )
        approval_response = self.runtime_server.post("/tasks/task-1/approve")
        item: dict[str, object] = {
            "messageId": "message-1",
            "eventCode": PHONE_INSPECT_EVENT_CODE,
            "createdAtMillis": int(time.time() * 1_000) - 1_000,
            "payload": {"phoneNumber": "+14155552671"},
        }
        if task_type is TaskType.ITEM_DRIVEN:
            item["allocationRule"] = {"workerId": {"$eq": "worker-1"}}
        append_response = self.runtime_server.post(
            "/tasks/task-1/items",
            json={"items": [item]},
        )

        self.assertEqual(200, group_response.status_code)
        self.assertEqual(200, worker_response.status_code)
        self.assertEqual(201, creation_response.status_code)
        self.assertEqual(200, approval_response.status_code)
        self.assertEqual(200, append_response.status_code)
        self.assertEqual(
            {"message-1": {"status": TaskItemAppendStatus.APPENDED.value}},
            append_response.json(),
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

    def test_adapter_rejection_evidence_demotes_then_reconnect_restores_worker(
        self,
    ) -> None:
        self.runtime_server.put(
            "/worker-groups/image-workers",
            json={
                "attributes": {},
                "eventCodes": ["image.resize"],
                "itemAllocationFields": ["workerId"],
            },
        )
        worker_request = {
            "endpointManagerId": "endpoint-1",
            "attributes": {"runtime": "python"},
            "dynamicAttributeNames": [],
        }
        self.runtime_server.put(
            "/worker-groups/image-workers/workers/worker-1",
            json=worker_request,
        )
        self.runtime_server.post(
            "/tasks",
            json=self._task_request(TaskType.ITEM_DRIVEN),
        )
        self.runtime_server.post("/tasks/task-1/approve")
        self.runtime_server.post(
            "/tasks/task-1/items",
            json={
                "items": [
                    {
                        "messageId": "message-1",
                        "eventCode": "image.resize",
                        "createdAtMillis": int(time.time() * 1_000) - 1_000,
                        "payload": {"source": "input"},
                        "allocationRule": {
                            "workerId": {"$eq": "worker-1"}
                        },
                    }
                ]
            },
        )

        command_payload = None
        deadline = time.monotonic() + 3
        poll_path = (
            "/worker-delivery/endpoint-managers/endpoint-1/"
            "workers/worker-1/commands:poll"
        )
        while time.monotonic() < deadline and command_payload is None:
            response = self.runtime_server.post(poll_path)
            if response.status_code == 200:
                command_payload = response.json()
                break
            self.assertEqual(204, response.status_code)
            time.sleep(0.02)
        self.assertIsNotNone(command_payload)
        assert command_payload is not None
        command = WorkerCommandEnvelope(
            command_id=command_payload["commandId"],
            message_type=WorkerMessageType(command_payload["messageType"]),
            execute_before_millis=command_payload["executeBeforeMillis"],
            opaque_item=command_payload["opaqueItem"],
        )
        seed = decode_deliver_seed(command.opaque_item)
        self.assertIsNotNone(seed)
        assert seed is not None
        rejection_response = self.runtime_server.post(
            "/worker-delivery/endpoint-managers/"
            "endpoint-1/results:append",
            json={
                "results": [
                    {
                        "commandId": command.command_id,
                        "opaqueResultContext": seed.opaque_result_context,
                        "outcomeCode": "3001",
                        "opaqueResultPayload": None,
                    }
                ]
            },
        )
        self.assertEqual(202, rejection_response.status_code)

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

        self.runtime_server.put(
            "/worker-groups/image-workers/workers/worker-1",
            json=worker_request,
        )
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
                {"attributes.runtime": {"$eq": "python"}}
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
