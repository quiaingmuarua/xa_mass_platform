from __future__ import annotations

import os
import time
import unittest
import uuid

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.examples.local_function_adapter import (
    EventHandlerResult,
    LocalFunctionTransportAdapter,
    WorkerMeta,
)
from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    RedisWorkerScoreCore,
    TaskItemScoreBand,
    WorkerScorePolarity,
)
from kernel_design.executable_spec.assembly import (
    DeliverSeedConsumerClient,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    SeedResultCommandClient,
    TaskDescriptor,
    TaskItem,
    WorkerDeclaration,
    WorkerGroupDescriptor,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


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
        self.prefix = f"result-closure-{uuid.uuid4().hex}"
        self.config = KernelApplicationConfig(
            redis_url=_REDIS_URL,
            redis_prefix=self.prefix,
            worker_allocation_interval_millis=500,
            running_activation_interval_millis=10,
            task_item_dispatch_interval_millis=10,
            result_routing_interval_millis=10,
            stop_timeout_millis=1_000,
        )
        self.resources = ResourcesCommandClient(self.config)
        self.application = KernelApplication(self.config)
        self.adapter = LocalFunctionTransportAdapter(
            endpoint_manager_id="endpoint-1",
            deliver_seed_consumer=DeliverSeedConsumerClient(self.config),
            seed_result_commands=SeedResultCommandClient(self.config),
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
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_local_adapter_result_finalizes_item_and_releases_worker(self) -> None:
        self.resources.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        self.adapter.register_worker("worker-1", WorkerMeta({"runtime": "local"}))
        self.adapter.register_event_handler(
            "image.resize",
            lambda payload, worker: EventHandlerResult(
                "200",
                {"handled": payload["source"], "runtime": worker.attributes["runtime"]},
            ),
        )
        self.resources.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id="image-workers",
                endpoint_manager_id="endpoint-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            )
        )

        self.application.start()
        self.application.create_task(descriptor=self._task_descriptor())
        self.application.approve_task(task_id="task-1")
        self.application.append_task_items(
            task_id="task-1",
            items=(
                TaskItem(
                    message_id="message-1",
                    event_code="image.resize",
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"source": "input"},
                ),
            ),
        )

        deadline = time.monotonic() + 3
        handled = 0
        while time.monotonic() < deadline and handled == 0:
            handled = self.adapter.drain_once(limit=10)
            if handled == 0:
                time.sleep(0.02)
        self.assertEqual(1, handled)

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

    def test_adapter_rejection_demotes_worker_then_reconnect_restores_hot_acquire(
        self,
    ) -> None:
        self.resources.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        declaration = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id="image-workers",
            endpoint_manager_id="endpoint-1",
            attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset(),
        )
        self.adapter.register_worker("worker-1", WorkerMeta({"runtime": "local"}))
        self.adapter.unregister_worker("worker-1")
        self.resources.upsert_worker(declaration=declaration)

        self.application.start()
        self.application.create_task(descriptor=self._task_descriptor())
        self.application.approve_task(task_id="task-1")
        self.application.append_task_items(
            task_id="task-1",
            items=(
                TaskItem(
                    message_id="message-1",
                    event_code="image.resize",
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"source": "input"},
                ),
            ),
        )

        deadline = time.monotonic() + 3
        reported = 0
        while time.monotonic() < deadline and reported == 0:
            reported = self.adapter.drain_once(limit=10)
            if reported == 0:
                time.sleep(0.02)
        self.assertEqual(1, reported)

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

    @staticmethod
    def _task_descriptor() -> TaskDescriptor:
        return TaskDescriptor(
            task_id="task-1",
            worker_group_id="image-workers",
            allocation_rule={"attributes.runtime": {"$eq": "python"}},
            config={
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "runningVisibleMinimumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
        )


if __name__ == "__main__":
    unittest.main()
