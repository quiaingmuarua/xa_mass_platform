from __future__ import annotations

import os
import time
import unittest
import uuid

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.py_example import (
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisZsetWorkerScoreCore,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
    WorkerScoreTransitionStatus,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class RedisWorkerRuntimeIntegrationTest(unittest.TestCase):
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
        self.prefix = f"integration-{uuid.uuid4().hex}"
        self.worker_group_id = "image-workers"
        self.score_key_prefix = f"wr:{self.prefix}:score"
        self.score_band = RedisZsetWorkerScoreCore(
            self.redis,
            score_key_prefix=self.score_key_prefix,
        )
        self.runtime = RedisWorkerRuntime(
            self.redis,
            self.score_band,
            prefix=self.prefix,
        )
        self.catalog = RedisWorkerResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )

    def tearDown(self) -> None:
        self.redis.delete(
            f"wr:{self.prefix}:groups",
            f"wr:{self.prefix}:workers:{self.worker_group_id}",
            f"{self.score_key_prefix}:{self.worker_group_id}",
        )

    def test_registered_worker_becomes_hot_acquire_candidate(self) -> None:
        group = WorkerGroupDescriptor(
            worker_group_id=self.worker_group_id,
            attributes={"kind": "image"},
            event_codes=frozenset({"resize"}),
        )
        worker = WorkerDescriptor(
            worker_id="worker-1",
            worker_group_id=self.worker_group_id,
            endpoint_manager_id="endpoint-manager-1",
            system_metadata={"tier": "premium"},
            static_attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset({"battery"}),
        )
        self.catalog.register_worker_group_descriptor(descriptor=group)

        registered = self.runtime.register_worker_descriptor(
            descriptor=worker,
            lane_rank=5,
        )
        time.sleep((self.score_band.SLOT_MILLIS + 20) / 1_000)
        candidates = self.score_band.acquire_hot_acquire_candidates(
            home_bucket_id=self.worker_group_id,
            limit=10,
        )
        repeated_candidates = self.score_band.acquire_hot_acquire_candidates(
            home_bucket_id=self.worker_group_id,
            limit=10,
        )
        observed_score = candidates[worker.worker_id]
        first_lease = self.score_band.acquire_observed_hot_score_leases(
            home_bucket_id=self.worker_group_id,
            observed_scores={worker.worker_id: observed_score},
            target_time_millis=(time.time_ns() // 1_000_000) + 5_000,
        )[worker.worker_id]
        second_lease = self.score_band.acquire_observed_hot_score_leases(
            home_bucket_id=self.worker_group_id,
            observed_scores={worker.worker_id: observed_score},
            target_time_millis=(time.time_ns() // 1_000_000) + 6_000,
        )[worker.worker_id]
        descriptors = self.catalog.get_worker_descriptors(
            worker_group_id=self.worker_group_id,
            worker_ids=[worker.worker_id],
        )

        self.assertEqual(registered.status, WorkerRuntimeStatus.OK)
        self.assertEqual(set(candidates), {worker.worker_id})
        self.assertEqual(repeated_candidates, candidates)
        self.assertEqual(
            first_lease.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        self.assertEqual(second_lease.status, WorkerScoreTransitionStatus.STALE)
        self.assertEqual(descriptors[worker.worker_id], worker)


if __name__ == "__main__":
    unittest.main()
