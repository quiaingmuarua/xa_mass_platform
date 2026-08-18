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
    MappedWorkerPropertyIndexRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisWorkerScoreCore,
    RedisHashWorkerPropertyIndexProvider,
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
        )
        self.catalog = RedisWorkerResourceCatalog(
            self.redis,
            prefix=self.prefix,
        )
        self.property_index_provider = (
            RedisHashWorkerPropertyIndexProvider(
                self.redis,
                prefix=self.prefix,
            )
        )
        self.property_index = MappedWorkerPropertyIndexRuntime(
            self.catalog,
            {
                "index.worker.region": self.property_index_provider.create(
                    "index.worker.region"
                ),
                "index.platform.pool": self.property_index_provider.create(
                    "index.platform.pool"
                ),
            },
        )

    def tearDown(self) -> None:
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_property_indexes_replace_load_and_isolate_groups(self) -> None:
        for worker_group_id in (self.worker_group_id, "other-workers"):
            self.catalog.upsert_worker_group(
                descriptor=WorkerGroupDescriptor(
                    worker_group_id=worker_group_id,
                    attributes={},
                    event_codes=frozenset(),
                )
            )
            self.runtime.upsert_worker(
                declaration=WorkerDeclaration(
                    worker_id=f"{worker_group_id}-worker",
                    worker_group_id=worker_group_id,
                    endpoint_manager_id="endpoint-manager-1",
                    worker_properties={},
                )
            )

        worker_id = f"{self.worker_group_id}-worker"
        self.property_index.update_indexed_properties(
            worker_group_id=self.worker_group_id,
            worker_id=worker_id,
            updates={
                "index.worker.region": "cn-east",
                "index.platform.pool": "batch",
            },
        )
        other_worker_id = "other-workers-worker"
        self.property_index.update_indexed_properties(
            worker_group_id="other-workers",
            worker_id=other_worker_id,
            updates={"index.worker.region": "cn-east"},
        )

        worker_values = self.property_index.load_indexed_property_values(
            worker_group_id=self.worker_group_id,
            index_field="index.worker.region",
            worker_ids=[worker_id, other_worker_id],
        )
        self.assertEqual(worker_values, {worker_id: "cn-east"})
        self.assertEqual(
            self.property_index.load_indexed_property_values(
                worker_group_id=self.worker_group_id,
                index_field="index.platform.pool",
                worker_ids=[worker_id, other_worker_id],
            ),
            {worker_id: "batch"},
        )

        self.property_index.update_indexed_properties(
            worker_group_id=self.worker_group_id,
            worker_id=worker_id,
            updates={"index.worker.region": "cn-west"},
        )
        self.assertEqual(
            self.property_index.load_indexed_property_values(
                worker_group_id=self.worker_group_id,
                index_field="index.worker.region",
                worker_ids=[worker_id],
            ),
            {worker_id: "cn-west"},
        )

        disabled = MappedWorkerPropertyIndexRuntime(self.catalog, {})
        with self.assertRaises(LookupError):
            disabled.load_indexed_property_values(
                worker_group_id=self.worker_group_id,
                index_field="index.worker.region",
                worker_ids=[worker_id],
            )
        reenabled = MappedWorkerPropertyIndexRuntime(
            self.catalog,
            {
                "index.worker.region": self.property_index_provider.create(
                    "index.worker.region"
                )
            },
        )
        self.assertEqual(
            reenabled.load_indexed_property_values(
                worker_group_id=self.worker_group_id,
                index_field="index.worker.region",
                worker_ids=[worker_id],
            ),
            {worker_id: "cn-west"},
        )

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
                worker_properties={},
            )
        )
        conflict = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="shared-worker",
                worker_group_id="audio-workers",
                endpoint_manager_id="endpoint-manager-2",
                worker_properties={},
            )
        )

        self.assertEqual(WorkerRuntimeStatus.OK, first.status)
        self.assertEqual(WorkerRuntimeStatus.CONFLICT, conflict.status)
        self.assertEqual(
            self.catalog.get_worker_group_ids(
                worker_ids=("shared-worker", "missing-worker")
            ),
            {
                "shared-worker": self.worker_group_id,
                "missing-worker": None,
            },
        )
        self.assertIsNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="audio-workers",
                worker_ids=("shared-worker",),
            )["shared-worker"]
        )

    def test_worker_descriptor_sampling_is_bounded_group_local_and_lossy(
        self,
    ) -> None:
        metadata_key = (
            f"wr:{self.prefix}:worker-metadata:{self.worker_group_id}"
        )
        properties_key = (
            f"wr:{self.prefix}:worker-properties:{self.worker_group_id}"
        )
        metadata_rows = {
            f"worker-{index:03d}": json.dumps(
                {
                    "workerId": f"worker-{index:03d}",
                    "workerGroupId": self.worker_group_id,
                    "endpointManagerId": "endpoint-manager-1",
                    "platformProperties": {},
                },
                separators=(",", ":"),
            )
            for index in range(120)
        }
        property_rows = {
            f"worker-{index:03d}": json.dumps(
                {"index": index},
                separators=(",", ":"),
            )
            for index in range(120)
        }
        self.redis.hset(metadata_key, mapping=metadata_rows)
        self.redis.hset(properties_key, mapping=property_rows)
        self.redis.hset(
            f"wr:{self.prefix}:worker-metadata:other-workers",
            "other-worker",
            json.dumps(
                {
                    "workerId": "other-worker",
                    "workerGroupId": "other-workers",
                    "endpointManagerId": "endpoint-manager-2",
                    "platformProperties": {},
                },
                separators=(",", ":"),
            ),
        )
        self.redis.hset(
            f"wr:{self.prefix}:worker-properties:other-workers",
            "other-worker",
            "{}",
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

        invalid_metadata_key = (
            f"wr:{self.prefix}:worker-metadata:invalid-workers"
        )
        self.redis.hset(
            invalid_metadata_key,
            mapping={
                "broken": "{not-json",
                "wrong-id": json.dumps(
                    {
                        "workerId": "another-worker",
                        "workerGroupId": "invalid-workers",
                        "endpointManagerId": "endpoint-manager-1",
                        "platformProperties": {},
                    },
                    separators=(",", ":"),
                ),
                "wrong-group": json.dumps(
                    {
                        "workerId": "wrong-group",
                        "workerGroupId": "other-workers",
                        "endpointManagerId": "endpoint-manager-1",
                        "platformProperties": {},
                    },
                    separators=(",", ":"),
                ),
            },
        )
        self.redis.hset(
            f"wr:{self.prefix}:worker-properties:invalid-workers",
            mapping={
                "broken": "{}",
                "wrong-id": "{}",
                "wrong-group": "{}",
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
            worker_properties={"runtime": "python"},
        )
        self.catalog.upsert_worker_group(descriptor=group)

        registered = self.runtime.upsert_worker(
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

        self.assertEqual(registered.status, WorkerRuntimeStatus.OK)
        self.assertEqual(set(candidates), {worker.worker_id})
        self.assertEqual(repeated_candidates, candidates)
        self.assertEqual(
            first_lease.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        self.assertEqual(second_lease.status, WorkerScoreTransitionStatus.STALE)
        descriptor = descriptors[worker.worker_id]
        assert descriptor is not None
        self.assertEqual(
            descriptor.worker_properties,
            worker.worker_properties,
        )
        self.assertEqual(descriptor.platform_properties, {})

    def test_upsert_preserves_recovery_and_identity_conflict_fails_closed(
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
            worker_properties={"runtime": "python"},
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
        )
        self.assertEqual(
            recovery_transition.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )

        repeated = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=declaration.worker_id,
                worker_group_id=declaration.worker_group_id,
                endpoint_manager_id=declaration.endpoint_manager_id,
                worker_properties={"runtime": "java"},
            )
        )
        conflict = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=declaration.worker_id,
                worker_group_id=declaration.worker_group_id,
                endpoint_manager_id="other-endpoint",
                worker_properties={"runtime": "other"},
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

        self.assertEqual(repeated.status, WorkerRuntimeStatus.OK)
        self.assertEqual(conflict.status, WorkerRuntimeStatus.CONFLICT)
        assert current is not None
        self.assertEqual(current.polarity, WorkerScorePolarity.RECOVERY_RECHECK)
        self.assertEqual(current.score, recovery_transition.score)
        self.assertEqual(current.time_millis, held.time_millis)
        self.assertEqual(current.lane_rank, 0)
        self.assertTrue(current.dirty)
        assert descriptor is not None
        self.assertEqual(descriptor.endpoint_manager_id, "endpoint-manager-1")
        self.assertEqual(descriptor.worker_properties, {"runtime": "java"})

    def test_polarity_transitions_reset_lane_rank(self) -> None:
        self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=self.worker_group_id,
                attributes={},
                event_codes=frozenset(),
            )
        )
        worker_id = "worker-polarity"
        registered = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=worker_id,
                worker_group_id=self.worker_group_id,
                endpoint_manager_id="endpoint-manager-1",
                worker_properties={},
            )
        )
        self.assertEqual(registered.status, WorkerRuntimeStatus.OK)
        initial = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[worker_id],
        )[worker_id]
        assert initial is not None
        self.assertEqual(initial.lane_rank, 0)

        rewritten = self.score_band.rewrite_current_scores(
            home_bucket_id=self.worker_group_id,
            worker_ids=[worker_id],
            target_time_millis=(time.time_ns() // 1_000_000) + 10_000,
            target_lane_rank=7,
        )[worker_id]
        self.assertEqual(
            rewritten.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        hot = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[worker_id],
        )[worker_id]
        assert hot is not None

        demoted = self.score_band.demote_observed_worker_leases_to_recovery(
            home_bucket_id=self.worker_group_id,
            observed_scores={worker_id: hot.score},
        )[worker_id]
        self.assertEqual(
            demoted.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        recovery = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[worker_id],
        )[worker_id]
        assert recovery is not None
        self.assertEqual(recovery.polarity, WorkerScorePolarity.RECOVERY_RECHECK)
        self.assertEqual(recovery.time_millis, hot.time_millis)
        self.assertEqual(recovery.lane_rank, 0)

        reopened = self.score_band.toggle_current_polarity(
            home_bucket_id=self.worker_group_id,
            worker_id=worker_id,
            observed_score=recovery.score,
        )
        self.assertEqual(
            reopened.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        hot_again = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[worker_id],
        )[worker_id]
        assert hot_again is not None
        self.assertEqual(hot_again.polarity, WorkerScorePolarity.HOT_ACQUIRE)
        self.assertEqual(hot_again.time_millis, recovery.time_millis)
        self.assertEqual(hot_again.lane_rank, 0)

        toggled = self.score_band.toggle_current_polarity(
            home_bucket_id=self.worker_group_id,
            worker_id=worker_id,
            observed_score=hot_again.score,
        )
        self.assertEqual(
            toggled.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )
        recovery_again = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[worker_id],
        )[worker_id]
        assert recovery_again is not None
        self.assertEqual(
            recovery_again.polarity,
            WorkerScorePolarity.RECOVERY_RECHECK,
        )
        self.assertEqual(recovery_again.time_millis, hot_again.time_millis)
        self.assertEqual(recovery_again.lane_rank, 0)

    def test_upsert_preserves_active_lease_fence(
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
            worker_properties={"runtime": "python"},
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

        before_refresh = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]
        refresh = self.runtime.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id=declaration.worker_id,
                worker_group_id=declaration.worker_group_id,
                endpoint_manager_id=declaration.endpoint_manager_id,
                worker_properties={"runtime": "java"},
            )
        )
        after_refresh = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]
        renewed = self.score_band.renew_active_hot_score_leases(
            home_bucket_id=self.worker_group_id,
            observed_scores={declaration.worker_id: lease.score},
            target_time_millis=time.time_ns() // 1_000_000 + 6_000,
        )[declaration.worker_id]
        current = self.score_band.get_score_states(
            home_bucket_id=self.worker_group_id,
            worker_ids=[declaration.worker_id],
        )[declaration.worker_id]

        self.assertEqual(WorkerRuntimeStatus.OK, refresh.status)
        self.assertEqual(before_refresh, after_refresh)
        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, renewed.status)
        assert current is not None
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, current.polarity)
        self.assertEqual(0, current.dirty)
        self.assertNotEqual(lease.score, current.score)


if __name__ == "__main__":
    unittest.main()
