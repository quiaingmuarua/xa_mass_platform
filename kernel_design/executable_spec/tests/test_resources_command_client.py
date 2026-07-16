from __future__ import annotations

import inspect
import os
import time
import unittest
import uuid
from unittest.mock import Mock, patch

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec.assembly import (
    KernelApplicationConfig,
    ResourcesCommandClient,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)
from kernel_design.executable_spec.redis_runtime import RedisWorkerScoreCore


class ResourcesCommandClientTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis_client = Mock()
        self.score = Mock()
        self.catalog = Mock()
        self.runtime = Mock()
        patches = (
            patch("redis.Redis.from_url", return_value=self.redis_client),
            patch(
                "kernel_design.executable_spec.assembly.resources_command_client."
                "RedisWorkerScoreCore",
                return_value=self.score,
            ),
            patch(
                "kernel_design.executable_spec.assembly.resources_command_client."
                "RedisWorkerResourceCatalog",
                return_value=self.catalog,
            ),
            patch(
                "kernel_design.executable_spec.assembly.resources_command_client."
                "RedisWorkerRuntime",
                return_value=self.runtime,
            ),
        )
        for active in patches:
            active.start()
            self.addCleanup(active.stop)

        self.config = KernelApplicationConfig(
            redis_url="redis://redis:6379/9",
            redis_prefix="resources-test",
            worker_allocation_interval_millis=11,
            running_activation_interval_millis=12,
            task_item_dispatch_interval_millis=13,
            stop_timeout_millis=14,
        )
        self.client = ResourcesCommandClient(self.config)

    def test_public_surface_contains_only_resource_upsert_commands(self) -> None:
        public_instance_methods = {
            name
            for name, method in inspect.getmembers(
                ResourcesCommandClient,
                predicate=inspect.isfunction,
            )
            if not name.startswith("_")
        }
        self.assertEqual(
            {"upsert_worker", "upsert_worker_group"},
            public_instance_methods,
        )
        for forbidden in (
            "start",
            "stop",
            "update_worker_dynamic_attributes",
            "worker_score",
            "worker_runtime",
        ):
            self.assertFalse(hasattr(self.client, forbidden))
        self.assertNotIn(
            "lane_rank",
            inspect.signature(ResourcesCommandClient.upsert_worker).parameters,
        )

    def test_composes_only_worker_upsert_owners_from_shared_config(self) -> None:
        import redis

        redis.Redis.from_url.assert_called_once_with(  # type: ignore[attr-defined]
            "redis://redis:6379/9",
            decode_responses=False,
        )
        from kernel_design.executable_spec.assembly import resources_command_client

        resources_command_client.RedisWorkerScoreCore.assert_called_once_with(
            self.redis_client,
            score_key_prefix="wr:resources-test:score",
        )
        resources_command_client.RedisWorkerResourceCatalog.assert_called_once_with(
            self.redis_client,
            prefix="resources-test",
        )
        resources_command_client.RedisWorkerRuntime.assert_called_once_with(
            self.redis_client,
            self.score,
            prefix="resources-test",
            initial_lane_rank=50,
        )

    def test_upsert_delegates_without_application_lifecycle(self) -> None:
        group = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={},
            event_codes=frozenset({"image.resize"}),
        )
        worker = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id=group.worker_group_id,
            endpoint_manager_id="endpoint-1",
            attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset(),
        )
        group_result = WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        worker_result = WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        self.catalog.upsert_worker_group.return_value = group_result
        self.runtime.upsert_worker.return_value = worker_result

        self.assertIs(
            group_result,
            self.client.upsert_worker_group(descriptor=group),
        )
        self.assertIs(
            worker_result,
            self.client.upsert_worker(declaration=worker),
        )
        self.runtime.upsert_worker.assert_called_once_with(
            declaration=worker,
        )

    def test_from_json_uses_the_shared_application_config_contract(self) -> None:
        import redis

        redis.Redis.from_url.reset_mock()  # type: ignore[attr-defined]

        ResourcesCommandClient.from_json(
            '{"redis":{"url":"redis://redis:6379/7","prefix":"shared"},'
            '"assignmentDispatch":{"workerAllocationIntervalMillis":25}}'
        )

        redis.Redis.from_url.assert_called_once_with(  # type: ignore[attr-defined]
            "redis://redis:6379/7",
            decode_responses=False,
        )


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run ResourcesCommandClient Redis proof",
)
class ResourcesCommandClientIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        assert redis_module is not None
        assert _REDIS_URL is not None
        cls.redis = redis_module.Redis.from_url(_REDIS_URL, decode_responses=False)
        cls.redis.ping()

    def setUp(self) -> None:
        assert _REDIS_URL is not None
        self.prefix = f"resource-client-{uuid.uuid4().hex}"
        self.config = KernelApplicationConfig(
            redis_url=_REDIS_URL,
            redis_prefix=self.prefix,
        )
        self.client = ResourcesCommandClient(self.config)

    def tearDown(self) -> None:
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_upsert_initializes_hot_worker_without_start(self) -> None:
        group_id = "image-workers"
        group_result = self.client.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=group_id,
                attributes={},
                event_codes=frozenset({"image.resize"}),
            )
        )
        worker_result = self.client.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id=group_id,
                endpoint_manager_id="endpoint-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=frozenset(),
            )
        )
        score = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=f"wr:{self.prefix}:score",
        )

        deadline = time.monotonic() + 1
        candidates = {}
        while time.monotonic() < deadline and not candidates:
            candidates = score.acquire_hot_acquire_candidates(
                home_bucket_id=group_id,
                limit=10,
            )
            if not candidates:
                time.sleep(score.SLOT_MILLIS / 1_000)

        self.assertEqual(WorkerRuntimeStatus.OK, group_result.status)
        self.assertEqual(WorkerRuntimeStatus.OK, worker_result.status)
        self.assertIn("worker-1", candidates)


if __name__ == "__main__":
    unittest.main()
