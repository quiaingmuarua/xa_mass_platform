from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    MappedWorkerPropertyIndexRuntime,
    RedisHashWorkerPropertyIndexProvider,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)

from kernel_design.executable_spec.tests.redis_worker_runtime_test_support import (
    RedisWorkerRuntimeFixture,
)


class RedisWorkerRuntimeTest(RedisWorkerRuntimeFixture):
    def setUp(self) -> None:
        super().setUp()
        self.index_provider = RedisHashWorkerPropertyIndexProvider(
            self.redis,
            prefix="test",
        )
        self.index = MappedWorkerPropertyIndexRuntime(
            self.catalog,
            {
                "index.worker.region": self.index_provider.create(
                    "index.worker.region"
                ),
                "index.platform.pool": self.index_provider.create(
                    "index.platform.pool"
                ),
            },
        )

    def test_worker_group_replaces_attributes(self) -> None:
        self.upsert_group()
        changed = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image-v2"},
            event_codes=self.group.event_codes,
        )

        result = self.catalog.upsert_worker_group(descriptor=changed)
        stored = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers"]
        )["image-workers"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(stored, changed)

    def test_worker_group_replaces_event_code_catalog_summary(self) -> None:
        self.upsert_group()
        changed = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "updated"},
            event_codes=frozenset({"other"}),
        )
        result = self.catalog.upsert_worker_group(
            descriptor=changed
        )
        repeated = self.catalog.upsert_worker_group(descriptor=changed)
        stored = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers"]
        )["image-workers"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(repeated.status, WorkerRuntimeStatus.NOOP)
        self.assertEqual(stored, changed)

    def test_worker_group_replacement_retries_after_concurrent_change(
        self,
    ) -> None:
        self.upsert_group()
        changed = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "final"},
            event_codes=frozenset({"final.event"}),
        )

        def replace_observed(key: str, field: str) -> None:
            self.redis.hset(
                key,
                field,
                json.dumps(
                    {
                        "attributes": {"kind": "raced"},
                        "eventCodes": ["raced.event"],
                        "workerGroupId": "image-workers",
                    }
                ),
            )

        self.redis.before_hash_cas = replace_observed

        result = self.catalog.upsert_worker_group(descriptor=changed)
        stored = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers"]
        )["image-workers"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(stored, changed)

    def test_worker_group_field_identity_mismatch_is_not_read(self) -> None:
        self.redis.hset(
            "wr:test:groups",
            "image-workers",
            json.dumps(
                {
                    "workerGroupId": "other-workers",
                    "attributes": {},
                    "eventCodes": ["resize"],
                }
            ),
        )

        self.assertIsNone(
            self.catalog.get_worker_group_descriptors(
                worker_group_ids=["image-workers"]
            )["image-workers"]
        )

    def test_upsert_replaces_worker_properties_and_preserves_platform(self) -> None:
        self.upsert_group()
        first = self.worker_declaration(
            "worker-1",
            worker_properties={"arch": "arm64", "removed": True},
        )
        self.upsert_worker(first)
        patched = self.catalog.patch_worker_platform_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            properties={"pool": "batch"},
        )
        self.assertEqual(patched.status, WorkerRuntimeStatus.OK)

        repeated = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1", worker_properties={"arch": "x86_64"}
            )
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(repeated.status, WorkerRuntimeStatus.OK)
        self.assertEqual(descriptor.worker_properties, {"arch": "x86_64"})
        self.assertEqual(descriptor.platform_properties, {"pool": "batch"})
        self.assertEqual(
            self.runtime.upsert_worker(
                declaration=self.worker_declaration(
                    "worker-1",
                    worker_properties={"arch": "x86_64"},
                )
            ).status,
            WorkerRuntimeStatus.NOOP,
        )

    def test_repeated_upsert_preserves_every_existing_score_state(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration("worker-1")
        self.upsert_worker(declaration)
        score_key = "wr:test:score:image-workers"
        initial_score = self.redis.zscore(score_key, "worker-1")
        assert initial_score is not None

        existing_scores = (
            initial_score,
            initial_score + 1,
            -initial_score,
            initial_score + self.score_band.SLOT_FACTOR,
        )
        for existing_score in existing_scores:
            with self.subTest(existing_score=existing_score):
                self.redis.zadd(score_key, {"worker-1": existing_score})
                result = self.runtime.upsert_worker(
                    declaration=self.worker_declaration(
                        "worker-1",
                        worker_properties={"score-proof": existing_score},
                    )
                )

                self.assertIn(
                    result.status,
                    (WorkerRuntimeStatus.OK, WorkerRuntimeStatus.NOOP),
                )
                self.assertEqual(
                    self.redis.zscore(score_key, "worker-1"),
                    existing_score,
                )

    def test_upsert_repairs_partial_resource_stages(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration(
            "worker-1",
            worker_properties={"runtime": "initial"},
        )
        self.upsert_worker(declaration)

        self.redis.hdel("wr:test:worker-id-owners", "worker-1")
        owner_repair = self.runtime.upsert_worker(declaration=declaration)
        self.assertEqual(owner_repair.status, WorkerRuntimeStatus.OK)

        self.redis.hdel("wr:test:worker-metadata:image-workers", "worker-1")
        metadata_repair = self.runtime.upsert_worker(
            declaration=declaration
        )
        self.assertEqual(metadata_repair.status, WorkerRuntimeStatus.OK)

        self.redis.hdel("wr:test:worker-properties:image-workers", "worker-1")
        properties_repair = self.runtime.upsert_worker(
            declaration=declaration
        )
        self.assertEqual(properties_repair.status, WorkerRuntimeStatus.OK)

        self.redis.zrem("wr:test:score:image-workers", "worker-1")
        score_repair = self.runtime.upsert_worker(declaration=declaration)
        self.assertEqual(score_repair.status, WorkerRuntimeStatus.OK)
        self.assertIsNotNone(
            self.redis.zscore("wr:test:score:image-workers", "worker-1")
        )
        self.assertEqual(
            self.runtime.upsert_worker(declaration=declaration).status,
            WorkerRuntimeStatus.NOOP,
        )

    def test_upsert_repairs_missing_score_and_refreshes_properties(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration(
            "worker-1",
            worker_properties={"runtime": "initial"},
        )
        self.upsert_worker(declaration)
        score_key = "wr:test:score:image-workers"
        self.redis.zrem(score_key, "worker-1")

        updated = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                worker_properties={"runtime": "updated"},
            )
        )

        self.assertEqual(updated.status, WorkerRuntimeStatus.OK)
        self.assertIsNotNone(self.redis.zscore(score_key, "worker-1"))
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertEqual(
            descriptor.worker_properties,
            {"runtime": "updated"},
        )

    def test_platform_properties_patch_and_null_delete(self) -> None:
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                worker_properties={"arch": "arm64"},
            )
        )
        self.catalog.patch_worker_platform_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            properties={"pool": "batch", "tier": "premium"},
        )
        result = self.catalog.patch_worker_platform_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            properties={"pool": "burst", "tier": None},
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(descriptor.worker_properties, {"arch": "arm64"})
        self.assertEqual(descriptor.platform_properties, {"pool": "burst"})

    def test_platform_patch_and_worker_upsert_are_hash_isolated(self) -> None:
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                worker_properties={"arch": "arm64"},
            )
        )
        result = self.catalog.patch_worker_platform_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            properties={"pool": "batch"},
        )
        refreshed = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                worker_properties={"arch": "x86_64"},
            )
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(refreshed.status, WorkerRuntimeStatus.OK)
        self.assertEqual(descriptor.worker_properties, {"arch": "x86_64"})
        self.assertEqual(descriptor.platform_properties, {"pool": "batch"})

    def test_worker_redis_shape_separates_metadata_and_properties(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration(
            "worker-1",
            worker_properties={"arch": "arm64"},
        )
        self.upsert_worker(declaration)
        raw_metadata = self.redis.hget(
            "wr:test:worker-metadata:image-workers",
            "worker-1",
        )
        raw_properties = self.redis.hget(
            "wr:test:worker-properties:image-workers",
            "worker-1",
        )
        self.assertEqual(
            json.loads(raw_metadata),
            {
                "endpointManagerId": "endpoint-manager-1",
                "platformProperties": {},
                "workerGroupId": "image-workers",
                "workerId": "worker-1",
            },
        )
        self.assertEqual(json.loads(raw_properties), {"arch": "arm64"})

    def test_legacy_worker_resource_shapes_are_not_read(self) -> None:
        self.redis.hset(
            "wr:test:groups",
            "legacy-group",
            json.dumps(
                {
                    "workerGroupId": "legacy-group",
                    "attributes": {},
                    "eventCodes": ["resize"],
                    "item" + "AllocationFields": ["workerId"],
                }
            ),
        )
        self.redis.hset(
            "wr:test:workers:legacy-group",
            "worker-1",
            json.dumps(
                {
                    "workerId": "worker-1",
                    "workerGroupId": "legacy-group",
                    "endpointManagerId": "endpoint-manager-1",
                    "attributes": {},
                    "platform" + "Attributes": {},
                }
            ),
        )

        self.assertIsNone(
            self.catalog.get_worker_group_descriptors(
                worker_group_ids=["legacy-group"]
            )["legacy-group"]
        )
        self.assertIsNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="legacy-group",
                worker_ids=["worker-1"],
            )["worker-1"]
        )

    def test_descriptor_field_identity_mismatch_is_not_mutated_or_indexed(self) -> None:
        self.upsert_group()
        self.redis.hset(
            "wr:test:worker-metadata:image-workers",
            "worker-1",
            json.dumps(
                {
                    "workerId": "another-worker",
                    "workerGroupId": "image-workers",
                    "endpointManagerId": "endpoint-manager-1",
                    "platformProperties": {},
                }
            ),
        )
        self.redis.hset(
            "wr:test:worker-properties:image-workers",
            "worker-1",
            "{}",
        )

        patch_result = self.catalog.patch_worker_platform_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            properties={"pool": "batch"},
        )
        index_result = self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"index.worker.region": "cn-east"},
        )

        self.assertEqual(patch_result.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(
            index_result["index.worker.region"].status,
            WorkerRuntimeStatus.NOT_FOUND,
        )
        self.assertIsNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
            )["worker-1"]
        )

    def test_index_updates_are_independent_from_properties(self) -> None:
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                worker_properties={"region": "snapshot-region"},
            )
        )
        result = self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={
                "index.worker.region": "cn-east",
                "index.platform.pool": "batch",
            },
        )

        self.assertEqual(
            result["index.worker.region"].status,
            WorkerRuntimeStatus.OK,
        )
        self.assertEqual(
            result["index.platform.pool"].status,
            WorkerRuntimeStatus.OK,
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertEqual(
            descriptor.worker_properties,
            {"region": "snapshot-region"},
        )
        self.assertEqual(descriptor.platform_properties, {})
        worker_values = self.index.load_indexed_property_values(
            worker_group_id="image-workers",
            index_field="index.worker.region",
            worker_ids=["worker-1"],
        )
        platform_values = self.index.load_indexed_property_values(
            worker_group_id="image-workers",
            index_field="index.platform.pool",
            worker_ids=["worker-1"],
        )
        self.assertEqual(worker_values, {"worker-1": "cn-east"})
        self.assertEqual(platform_values, {"worker-1": "batch"})
        self.assertEqual(
            self.redis.hashes[
                "wr:test:property-index:image-workers:"
                "index.worker.region:values"
            ]["worker-1"],
            '{"value":"cn-east"}',
        )

    def test_index_replacement_and_delete_remove_old_membership(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"index.worker.region": "cn-east"},
        )
        self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"index.worker.region": "cn-west"},
        )

        self.assertEqual(
            self.index.load_indexed_property_values(
                worker_group_id="image-workers",
                index_field="index.worker.region",
                worker_ids=["worker-1"],
            ),
            {"worker-1": "cn-west"},
        )
        self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"index.worker.region": None},
        )
        self.assertEqual(
            self.index.load_indexed_property_values(
                worker_group_id="image-workers",
                index_field="index.worker.region",
                worker_ids=["worker-1"],
            ),
            {},
        )

    def test_index_returns_field_local_rejections(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        results = self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={
                "index.worker.region": "cn-east",
                "index.unknown": "x",
                "worker.invalid": "batch",
            },
        )
        invalid = self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"index.worker.region": object()},
        )
        missing = self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="missing",
            updates={"index.platform.pool": "batch"},
        )

        self.assertEqual(
            results["index.worker.region"].status,
            WorkerRuntimeStatus.OK,
        )
        self.assertEqual(
            results["index.unknown"].status,
            WorkerRuntimeStatus.NOT_FOUND,
        )
        self.assertEqual(
            invalid["index.worker.region"].status,
            WorkerRuntimeStatus.INVALID,
        )
        self.assertEqual(
            results["worker.invalid"].status,
            WorkerRuntimeStatus.INVALID,
        )
        self.assertEqual(
            missing["index.platform.pool"].status,
            WorkerRuntimeStatus.NOT_FOUND,
        )

    def test_indexes_load_only_requested_workers_across_multiple_fields(self) -> None:
        self.upsert_group()
        for worker_id, region, pool in (
            ("worker-1", "cn-east", "batch"),
            ("worker-2", "cn-east", "interactive"),
            ("worker-3", "cn-west", "batch"),
        ):
            self.upsert_worker(self.worker_declaration(worker_id))
            self.index.update_indexed_properties(
                worker_group_id="image-workers",
                worker_id=worker_id,
                updates={
                    "index.worker.region": region,
                    "index.platform.pool": pool,
                },
            )

        self.assertEqual(
            self.index.load_indexed_property_values(
                worker_group_id="image-workers",
                index_field="index.worker.region",
                worker_ids=["worker-1", "worker-3"],
            ),
            {"worker-1": "cn-east", "worker-3": "cn-west"},
        )
        self.assertEqual(
            self.index.load_indexed_property_values(
                worker_group_id="image-workers",
                index_field="index.platform.pool",
                worker_ids=["worker-2", "missing"],
            ),
            {"worker-2": "interactive"},
        )

    def test_index_load_never_returns_outside_requested_workers(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        self.index.update_indexed_properties(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"index.worker.region": "cn-east"},
        )
        self.assertEqual(
            self.index.load_indexed_property_values(
                worker_group_id="image-workers",
                index_field="index.worker.region",
                worker_ids=["missing"],
            ),
            {},
        )


if __name__ == "__main__":
    unittest.main()
