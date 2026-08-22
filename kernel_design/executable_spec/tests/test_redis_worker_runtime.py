from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)

from kernel_design.executable_spec.tests.redis_worker_runtime_test_support import (
    RedisWorkerRuntimeFixture,
)


class RedisWorkerRuntimeTest(RedisWorkerRuntimeFixture):
    def test_worker_group_registration_is_idempotent(self) -> None:
        first = self.catalog.register_worker_group(descriptor=self.group)
        repeated = self.catalog.register_worker_group(descriptor=self.group)

        self.assertEqual(first.status, WorkerRuntimeStatus.OK)
        self.assertEqual(repeated.status, WorkerRuntimeStatus.NOOP)

    def test_worker_group_registration_conflicts_without_replacing_attributes(
        self,
    ) -> None:
        self.register_group()
        changed = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image-v2"},
            event_codes=self.group.event_codes,
        )

        result = self.catalog.register_worker_group(descriptor=changed)
        stored = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers"]
        )["image-workers"]

        self.assertEqual(result.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(stored, self.group)

    def test_worker_group_registration_conflicts_without_replacing_event_codes(
        self,
    ) -> None:
        self.register_group()
        changed = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "updated"},
            event_codes=frozenset({"other"}),
        )
        result = self.catalog.register_worker_group(
            descriptor=changed
        )
        stored = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers"]
        )["image-workers"]

        self.assertEqual(result.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(stored, self.group)

    def test_worker_group_registration_rejects_unreadable_existing_value(self) -> None:
        self.redis.hset("wr:test:groups", "image-workers", "not-json")

        result = self.catalog.register_worker_group(descriptor=self.group)

        self.assertEqual(result.status, WorkerRuntimeStatus.INVALID)
        self.assertEqual(
            self.redis.hget("wr:test:groups", "image-workers"),
            "not-json",
        )

    def test_worker_group_registration_rejects_mismatched_existing_identity(
        self,
    ) -> None:
        stored = json.dumps(
            {
                "workerGroupId": "different-workers",
                "attributes": {},
                "eventCodes": [],
            }
        )
        self.redis.hset("wr:test:groups", "image-workers", stored)

        result = self.catalog.register_worker_group(descriptor=self.group)

        self.assertEqual(result.status, WorkerRuntimeStatus.INVALID)
        self.assertEqual(
            self.redis.hget("wr:test:groups", "image-workers"),
            stored,
        )

    def test_worker_group_sample_is_one_bounded_random_hash_read(self) -> None:
        self.register_group()
        other = WorkerGroupDescriptor(
            worker_group_id="other-workers",
            attributes={"kind": "other"},
            event_codes=frozenset({"other.event"}),
        )
        self.assertEqual(
            self.catalog.register_worker_group(descriptor=other).status,
            WorkerRuntimeStatus.OK,
        )

        sampled = self.catalog.sample_worker_group_descriptors(sample_limit=100)

        self.assertEqual(set(sampled), {"image-workers", "other-workers"})
        self.assertEqual(sampled["image-workers"], self.group)
        self.assertEqual(sampled["other-workers"], other)
        self.assertEqual(
            self.redis.hrandfield_calls,
            [("wr:test:groups", 100, True)],
        )

    def test_worker_group_sample_marks_invalid_or_mismatched_rows_unreadable(
        self,
    ) -> None:
        self.redis.hset("wr:test:groups", "broken", "not-json")
        self.redis.hset(
            "wr:test:groups",
            "mismatched",
            json.dumps(
                {
                    "workerGroupId": "different",
                    "attributes": {},
                    "eventCodes": [],
                }
            ),
        )

        sampled = self.catalog.sample_worker_group_descriptors(sample_limit=100)

        self.assertEqual(sampled, {"broken": None, "mismatched": None})

    def test_worker_group_sample_rejects_invalid_limits(self) -> None:
        for sample_limit in (0, 101, True, 1.5):
            with self.subTest(sample_limit=sample_limit):
                with self.assertRaisesRegex(ValueError, "between 1 and 100"):
                    self.catalog.sample_worker_group_descriptors(
                        sample_limit=sample_limit  # type: ignore[arg-type]
                    )

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
        self.register_group()
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
        self.register_group()
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
        self.register_group()
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

    def test_worker_group_lookup_reads_only_explicit_worker_owners(self) -> None:
        self.register_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        other_group = WorkerGroupDescriptor(
            worker_group_id="other-workers",
            attributes={},
            event_codes=frozenset(),
        )
        self.assertEqual(
            self.catalog.register_worker_group(descriptor=other_group).status,
            WorkerRuntimeStatus.OK,
        )
        self.upsert_worker(
            self.worker_declaration(
                "worker-2",
                worker_group_id="other-workers",
            )
        )

        owners = self.catalog.get_worker_group_ids(
            worker_ids=["worker-2", "missing", "worker-1"]
        )

        self.assertEqual(
            list(owners.items()),
            [
                ("worker-2", "other-workers"),
                ("missing", None),
                ("worker-1", "image-workers"),
            ],
        )
        self.assertEqual(
            self.redis.hmget_calls[-1],
            (
                "wr:test:worker-id-owners",
                ("worker-2", "missing", "worker-1"),
            ),
        )

    def test_worker_group_lookup_rejects_unbounded_or_invalid_ids(self) -> None:
        self.assertEqual(
            self.catalog.get_worker_group_ids(worker_ids=[]),
            {},
        )
        with self.assertRaisesRegex(ValueError, "at most 100"):
            self.catalog.get_worker_group_ids(
                worker_ids=[f"worker-{index}" for index in range(101)]
            )
        with self.assertRaisesRegex(ValueError, "non-empty strings"):
            self.catalog.get_worker_group_ids(worker_ids=[""])

    def test_upsert_repairs_missing_score_and_refreshes_properties(self) -> None:
        self.register_group()
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
        self.register_group()
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
        self.register_group()
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
        self.register_group()
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

    def test_descriptor_field_identity_mismatch_is_not_mutated(self) -> None:
        self.register_group()
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
        self.assertEqual(patch_result.status, WorkerRuntimeStatus.CONFLICT)
        self.assertIsNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
            )["worker-1"]
        )

if __name__ == "__main__":
    unittest.main()
