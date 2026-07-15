from __future__ import annotations

import unittest

from kernel_design.py_example import (
    RedisWorkerDynamicAttributeRuntime,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)

from kernel_design.py_example.tests.worker_runtime_test_support import (
    WorkerRuntimeRedisFixture,
)


class RedisWorkerRuntimeTest(WorkerRuntimeRedisFixture):
    def test_register_and_read_worker_group_descriptor(self) -> None:
        self.register_group()

        rows = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers", "missing"],
        )

        self.assertEqual(rows["image-workers"], self.group)
        self.assertIsNone(rows["missing"])
        self.assertIn("image-workers", self.redis.hashes["wr:test:groups"])

    def test_register_worker_descriptor_writes_selected_group_hash(self) -> None:
        self.register_group()
        descriptor = self.worker_descriptor(
            "worker-1",
            system_metadata={"tier": "premium"},
            static_attributes={"runtime": "python"},
        )
        self.register_worker(descriptor)

        rows = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )

        self.assertEqual(rows["worker-1"], descriptor)
        self.assertIn("worker-1", self.redis.hashes["wr:test:workers:image-workers"])
        self.assertEqual(
            set(self.redis.hashes),
            {"wr:test:groups", "wr:test:workers:image-workers"},
        )
        self.assertIn(
            "worker-1",
            self.redis.zsets["wr:test:score:image-workers"],
        )

    def test_register_worker_for_missing_group_is_not_found(self) -> None:
        result = self.runtime.register_worker_descriptor(
            descriptor=self.worker_descriptor("worker-1"),
            lane_rank=self.LANE_RANK,
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(self.redis.zsets, {})

    def test_register_worker_requires_endpoint_manager_id(self) -> None:
        self.register_group()

        result = self.runtime.register_worker_descriptor(
            descriptor=self.worker_descriptor(
                "worker-1",
                endpoint_manager_id="",
            ),
            lane_rank=self.LANE_RANK,
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.INVALID)
        self.assertEqual(self.redis.zsets, {})

    def test_registered_worker_enters_hot_acquire_after_current_slot(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))

        self.assertEqual(
            self.score_band.acquire_hot_acquire_candidates(
                home_bucket_id="image-workers",
                limit=10,
            ),
            {},
        )

        self.redis.now_millis += self.score_band.SLOT_MILLIS
        candidates = self.score_band.acquire_hot_acquire_candidates(
            home_bucket_id="image-workers",
            limit=10,
        )

        self.assertEqual(set(candidates), {"worker-1"})

    def test_existing_worker_score_blocks_descriptor_replacement(self) -> None:
        self.register_group()
        original = self.worker_descriptor(
            "worker-1",
            static_attributes={"runtime": "python"},
        )
        self.register_worker(original)

        result = self.runtime.register_worker_descriptor(
            descriptor=self.worker_descriptor(
                "worker-1",
                static_attributes={"runtime": "java"},
            ),
            lane_rank=self.LANE_RANK,
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(
            self.catalog.get_worker_descriptors(
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
            )["worker-1"],
            original,
        )

    def test_get_workers_is_scoped_to_one_explicit_group(self) -> None:
        self.register_group()
        other_group = WorkerGroupDescriptor(
            worker_group_id="audio-workers",
            attributes={},
            event_codes=frozenset({"transcribe"}),
        )
        self.catalog.register_worker_group_descriptor(descriptor=other_group)
        image_worker = self.worker_descriptor("image-worker")
        audio_worker = self.worker_descriptor(
            "audio-worker",
            worker_group_id="audio-workers",
        )
        self.register_worker(image_worker)
        self.register_worker(audio_worker)

        rows = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["image-worker", "audio-worker", "missing"],
        )

        self.assertEqual(rows["image-worker"], image_worker)
        self.assertIsNone(rows["audio-worker"])
        self.assertIsNone(rows["missing"])

        audio_rows = self.catalog.get_worker_descriptors(
            worker_group_id="audio-workers",
            worker_ids=["audio-worker"],
        )
        self.assertEqual(audio_rows["audio-worker"], audio_worker)

    def test_system_metadata_update_merges_without_touching_other_fields(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                system_metadata={"tier": "standard", "region": "us"},
                static_attributes={"runtime": "python"},
            )
        )

        result = self.catalog.update_worker_system_metadata(
            worker_group_id="image-workers",
            worker_id="worker-1",
            metadata={"tier": "premium"},
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert descriptor is not None
        self.assertEqual(descriptor.system_metadata, {"tier": "premium", "region": "us"})
        self.assertEqual(descriptor.static_attributes, {"runtime": "python"})
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))
        self.assertEqual(descriptor.endpoint_manager_id, "endpoint-manager-1")

    def test_static_attribute_refresh_replaces_only_static_attributes(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                endpoint_manager_id="endpoint-manager-1",
                system_metadata={"tier": "premium"},
                static_attributes={"runtime": "python", "old": True},
            )
        )

        result = self.catalog.refresh_worker_static_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            attributes={"runtime": "java"},
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert descriptor is not None
        self.assertEqual(descriptor.system_metadata, {"tier": "premium"})
        self.assertEqual(descriptor.static_attributes, {"runtime": "java"})
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))
        self.assertEqual(descriptor.endpoint_manager_id, "endpoint-manager-1")

    def test_catalog_updates_do_not_discover_worker_group(self) -> None:
        self.register_group()
        original = self.worker_descriptor(
            "worker-1",
            system_metadata={"tier": "standard"},
            static_attributes={"runtime": "python"},
        )
        self.register_worker(original)

        metadata_result = self.catalog.update_worker_system_metadata(
            worker_group_id="wrong-group",
            worker_id="worker-1",
            metadata={"tier": "premium"},
        )
        static_result = self.catalog.refresh_worker_static_attributes(
            worker_group_id="wrong-group",
            worker_id="worker-1",
            attributes={"runtime": "java"},
        )
        stored = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(metadata_result.status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(static_result.status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(stored, original)

    def test_dynamic_attribute_runtime_dispatches_allowed_updates(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        calls: list[tuple[str, object, int]] = []

        def update_battery(
            worker_id: str,
            payload: object,
            observed_at_millis: int,
        ) -> WorkerRuntimeResult:
            calls.append((worker_id, payload, observed_at_millis))
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

        runtime = RedisWorkerDynamicAttributeRuntime(
            self.catalog,
            {"battery": update_battery},
        )

        result = runtime.update_worker_dynamic_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"battery": 87},
            observed_at_millis=10_000,
        )

        self.assertEqual(result["battery"].status, WorkerRuntimeStatus.OK)
        self.assertEqual(calls, [("worker-1", 87, 10_000)])
        self.assertFalse(any(":score:" in key for key in self.redis.hashes))

    def test_dynamic_attribute_runtime_rejects_missing_worker_or_handler(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                dynamic_attribute_names=frozenset({"battery", "network"}),
            )
        )
        runtime = RedisWorkerDynamicAttributeRuntime(
            self.catalog,
            {"battery": lambda *_: WorkerRuntimeResult(WorkerRuntimeStatus.OK)},
        )

        missing_worker = runtime.update_worker_dynamic_attributes(
            worker_group_id="image-workers",
            worker_id="missing",
            updates={"battery": 1},
            observed_at_millis=1,
        )
        rejected_attr = runtime.update_worker_dynamic_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"load": 1},
            observed_at_millis=1,
        )
        missing_handler = runtime.update_worker_dynamic_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            updates={"network": "wifi"},
            observed_at_millis=1,
        )
        wrong_group = runtime.update_worker_dynamic_attributes(
            worker_group_id="wrong-group",
            worker_id="worker-1",
            updates={"battery": 1},
            observed_at_millis=1,
        )

        self.assertEqual(missing_worker["battery"].status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(rejected_attr["load"].status, WorkerRuntimeStatus.REJECTED)
        self.assertEqual(missing_handler["network"].status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(wrong_group["battery"].status, WorkerRuntimeStatus.NOT_FOUND)


if __name__ == "__main__":
    unittest.main()
