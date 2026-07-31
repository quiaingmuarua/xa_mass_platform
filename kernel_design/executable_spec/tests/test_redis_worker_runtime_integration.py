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

from kernel_design.executable_spec import (
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisWorkerScoreCore,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
    WorkerScorePolarity,
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
        self.score_band = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=self.score_key_prefix,
        )
        self.runtime = RedisWorkerRuntime(
            self.redis,
            self.score_band,
            prefix=self.prefix,
            initial_lane_rank=5,
        )
        self.catalog = RedisWorkerResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )

    def tearDown(self) -> None:
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_worker_id_is_globally_unique_across_groups(self) -> None:
        for worker_group_id in (self.worker_group_id, "audio-workers"):
            self.catalog.upsert_worker_group(
                descriptor=WorkerGroupDescriptor(
                    worker_group_id=worker_group_id,
                    attributes={},
                    event_codes=frozenset(),
                )
            )

        first = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="shared-worker",
                worker_group_id=self.worker_group_id,
                endpoint_manager_id="endpoint-manager-1",
                attributes={},
                dynamic_attribute_names=frozenset(),
            )
        )
        conflict = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="shared-worker",
                worker_group_id="audio-workers",
                endpoint_manager_id="endpoint-manager-2",
                attributes={},
                dynamic_attribute_names=frozenset(),
            )
        )

        self.assertEqual(WorkerRuntimeStatus.OK, first.status)
        self.assertEqual(WorkerRuntimeStatus.CONFLICT, conflict.status)
        self.assertIsNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="audio-workers",
                worker_ids=("shared-worker",),
            )["shared-worker"]
        )

    def test_worker_descriptor_sampling_is_bounded_group_local_and_lossy(
        self,
    ) -> None:
        worker_key = f"wr:{self.prefix}:workers:{self.worker_group_id}"
        valid_rows = {
            f"worker-{index:03d}": json.dumps(
                {
                    "workerId": f"worker-{index:03d}",
                    "workerGroupId": self.worker_group_id,
                    "endpointManagerId": "endpoint-manager-1",
                    "attributes": {"index": index},
                    "platformAttributes": {},
                    "dynamicAttributeNames": [],
                },
                separators=(",", ":"),
            )
            for index in range(120)
        }
        self.redis.hset(worker_key, mapping=valid_rows)
        self.redis.hset(
            f"wr:{self.prefix}:workers:other-workers",
            "other-worker",
            json.dumps(
                {
                    "workerId": "other-worker",
                    "workerGroupId": "other-workers",
                    "endpointManagerId": "endpoint-manager-2",
                    "attributes": {},
                    "platformAttributes": {},
                    "dynamicAttributeNames": [],
                },
                separators=(",", ":"),
            ),
        )

        one = self.catalog.sample_worker_descriptors(
            worker_group_id=self.worker_group_id,
            sample_limit=1,
        )
        hundred = self.catalog.sample_worker_descriptors(
            worker_group_id=self.worker_group_id,
            sample_limit=100,
        )
        repeated = {
            tuple(sorted(
                self.catalog.sample_worker_descriptors(
                    worker_group_id=self.worker_group_id,
                    sample_limit=5,
                )
            ))
            for _ in range(10)
        }

        self.assertEqual(len(one), 1)
        self.assertEqual(len(hundred), 100)
        self.assertEqual(len(set(hundred)), 100)
        self.assertTrue(
            all(
                descriptor is not None
                and descriptor.worker_group_id == self.worker_group_id
                for descriptor in hundred.values()
            )
        )
        self.assertNotIn("other-worker", hundred)
        self.assertGreater(len(repeated), 1)
        self.assertEqual(
            self.catalog.sample_worker_descriptors(
                worker_group_id="empty-workers",
                sample_limit=100,
            ),
            {},
        )

        invalid_key = f"wr:{self.prefix}:workers:invalid-workers"
        self.redis.hset(
            invalid_key,
            mapping={
                "broken": "{not-json",
                "wrong-id": json.dumps(
                    {
                        "workerId": "another-worker",
                        "workerGroupId": "invalid-workers",
                        "endpointManagerId": "endpoint-manager-1",
                        "attributes": {},
                        "platformAttributes": {},
                        "dynamicAttributeNames": [],
                    },
                    separators=(",", ":"),
                ),
                "wrong-group": json.dumps(
                    {
                        "workerId": "wrong-group",
                        "workerGroupId": "other-workers",
                        "endpointManagerId": "endpoint-manager-1",
                        "attributes": {},
                        "platformAttributes": {},
                        "dynamicAttributeNames": [],
                    },
                    separators=(",", ":"),
                ),
            },
        )
        self.assertEqual(
            self.catalog.sample_worker_descriptors(
                worker_group_id="invalid-workers",
                sample_limit=100,
            ),
            {
                "broken": None,
                "wrong-id": None,
                "wrong-group": None,
            },
        )

        for invalid_limit in (0, 101):
            with self.assertRaises(ValueError):
                self.catalog.sample_worker_descriptors(
                    worker_group_id=self.worker_group_id,
                    sample_limit=invalid_limit,
                )

    def test_upserted_worker_becomes_hot_acquire_candidate(self) -> None:
        group = WorkerGroupDescriptor(
            worker_group_id=self.worker_group_id,
            attributes={"kind": "image"},
            event_codes=frozenset({"resize"}),
        )
        worker = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id=self.worker_group_id,
            endpoint_manager_id="endpoint-manager-1",
            attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset({"battery"}),
        )
        self.catalog.upsert_worker_group(descriptor=group)

        upserted = self.runtime.upsert_worker(
            declaration=worker,
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

        self.assertEqual(upserted.status, WorkerRuntimeStatus.OK)
        self.assertEqual(set(candidates), {worker.worker_id})
        self.assertEqual(repeated_candidates, candidates)
        self.assertEqual(
            first_lease.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        self.assertEqual(second_lease.status, WorkerScoreTransitionStatus.STALE)
        descriptor = descriptors[worker.worker_id]
        assert descriptor is not None
        self.assertEqual(descriptor.attributes, worker.attributes)
        self.assertEqual(descriptor.platform_attributes, {})

    def test_reconnect_restores_polarity_and_identity_conflict_fails_closed(
        self,
    ) -> None:
        self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=self.worker_group_id,
                attributes={},
                event_codes=frozenset({"resize"}),
            )
        )
        declaration = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id=self.worker_group_id,
            endpoint_manager_id="endpoint-manager-1",
            attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset({"battery"}),
        )
        self.runtime.upsert_worker(declaration=declaration)
        rewritten = self.score_band.rewrite_current_scores(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
            target_time_millis=(time.time_ns() // 1_000_000) + 10_000,
            target_lane_rank=7,
        )[declaration.worker_id]
        self.assertEqual(
            rewritten.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        dirty = self.score_band.mark_current_lease_dirty(
            home_bucket_id=self.worker_group_id,
            worker_id=declaration.worker_id,
        )
        self.assertEqual(dirty.status, WorkerScoreTransitionStatus.TRANSITIONED)
        held = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]
        assert held is not None
        recovery_transition = self.score_band.toggle_current_polarity(
            home_bucket_id=self.worker_group_id,
            worker_id=declaration.worker_id,
            observed_score=held.score,
            target_lane_rank=held.lane_rank,
        )
        self.assertEqual(
            recovery_transition.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )

        reconnect_result = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=declaration.worker_id,
                worker_group_id=declaration.worker_group_id,
                endpoint_manager_id=declaration.endpoint_manager_id,
                attributes={"runtime": "java"},
                dynamic_attribute_names=declaration.dynamic_attribute_names,
            )
        )
        conflict = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=declaration.worker_id,
                worker_group_id=declaration.worker_group_id,
                endpoint_manager_id="other-endpoint",
                attributes={"runtime": "other"},
                dynamic_attribute_names=declaration.dynamic_attribute_names,
            )
        )
        current = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]

        self.assertEqual(reconnect_result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(conflict.status, WorkerRuntimeStatus.CONFLICT)
        assert current is not None
        self.assertEqual(current.polarity, WorkerScorePolarity.HOT_ACQUIRE)
        self.assertEqual(current.score, abs(recovery_transition.score or 0))
        self.assertEqual(current.time_millis, held.time_millis)
        self.assertEqual(current.lane_rank, 7)
        self.assertTrue(current.dirty)
        assert descriptor is not None
        self.assertEqual(descriptor.endpoint_manager_id, "endpoint-manager-1")
        self.assertEqual(descriptor.attributes, {"runtime": "java"})

    def test_reconnect_dirties_active_lease_and_rejects_old_candidate_fence(
        self,
    ) -> None:
        self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=self.worker_group_id,
                attributes={},
                event_codes=frozenset({"resize"}),
            )
        )
        declaration = WorkerDeclaration(
            worker_id="worker-1",
            worker_group_id=self.worker_group_id,
            endpoint_manager_id="endpoint-manager-1",
            attributes={"runtime": "python"},
            dynamic_attribute_names=frozenset(),
        )
        self.runtime.upsert_worker(declaration=declaration)
        time.sleep((self.score_band.SLOT_MILLIS + 20) / 1_000)
        observed = self.score_band.acquire_hot_acquire_candidates(
            home_bucket_id=self.worker_group_id,
            limit=1,
        )[declaration.worker_id]
        lease = self.score_band.acquire_observed_hot_score_leases(
            home_bucket_id=self.worker_group_id,
            observed_scores={declaration.worker_id: observed},
            target_time_millis=time.time_ns() // 1_000_000 + 5_000,
        )[declaration.worker_id]
        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, lease.status)
        assert lease.score is not None

        reconnect = self.runtime.upsert_worker(declaration=declaration)
        stale = self.score_band.renew_active_hot_score_leases(
            home_bucket_id=self.worker_group_id,
            observed_scores={declaration.worker_id: lease.score},
            target_time_millis=time.time_ns() // 1_000_000 + 6_000,
        )[declaration.worker_id]
        current = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]

        self.assertEqual(WorkerRuntimeStatus.OK, reconnect.status)
        self.assertEqual(WorkerScoreTransitionStatus.STALE, stale.status)
        assert current is not None
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, current.polarity)
        self.assertEqual(1, current.dirty)
        self.assertNotEqual(lease.score, current.score)


if __name__ == "__main__":
    unittest.main()
