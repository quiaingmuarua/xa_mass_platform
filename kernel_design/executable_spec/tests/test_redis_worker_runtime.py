from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerRuntime,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
    WorkerScorePolarity,
    WorkerScoreTransitionStatus,
)

from kernel_design.executable_spec.tests.redis_worker_runtime_test_support import (
    RedisWorkerRuntimeFixture,
)


class RedisWorkerRuntimeTest(RedisWorkerRuntimeFixture):
    def test_runtime_rejects_invalid_initial_lane_rank(self) -> None:
        with self.assertRaisesRegex(ValueError, "initial_lane_rank"):
            RedisWorkerRuntime(
                self.redis,
                self.score_band,
                prefix="test",
                initial_lane_rank=100,
            )

    def test_upsert_and_read_worker_group_descriptor(self) -> None:
        self.upsert_group()

        rows = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers", "missing"],
        )

        self.assertEqual(rows["image-workers"], self.group)
        self.assertIsNone(rows["missing"])
        self.assertIn("image-workers", self.redis.hashes["wr:test:groups"])

    def test_worker_group_upsert_replaces_only_attributes(self) -> None:
        item_fields = frozenset({"workerId", "dynamic.battery"})
        self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={"kind": "image", "removed": True},
                event_codes=self.group.event_codes,
                item_allocation_fields=item_fields,
            )
        )

        updated = self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={"kind": "gpu"},
                event_codes=self.group.event_codes,
                item_allocation_fields=item_fields,
            )
        )
        conflict = self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={"kind": "other"},
                event_codes=frozenset({"other"}),
                item_allocation_fields=item_fields,
            )
        )
        field_conflict = self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="image-workers",
                attributes={"kind": "other"},
                event_codes=self.group.event_codes,
                item_allocation_fields=frozenset({"workerId"}),
            )
        )
        stored = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers"]
        )["image-workers"]

        self.assertEqual(updated.status, WorkerRuntimeStatus.OK)
        self.assertEqual(conflict.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(field_conflict.status, WorkerRuntimeStatus.CONFLICT)
        assert stored is not None
        self.assertEqual(stored.attributes, {"kind": "gpu"})
        self.assertEqual(stored.event_codes, self.group.event_codes)
        self.assertEqual(stored.item_allocation_fields, item_fields)

    def test_upsert_worker_writes_selected_group_hash(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration(
            "worker-1",
            attributes={"runtime": "python"},
        )
        self.upsert_worker(declaration)

        rows = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )

        self.assertEqual(rows["worker-1"], self.expected_descriptor(declaration))
        self.assertIn("worker-1", self.redis.hashes["wr:test:workers:image-workers"])
        self.assertEqual(
            set(self.redis.hashes),
            {
                "wr:test:groups",
                "wr:test:worker-id-owners",
                "wr:test:workers:image-workers",
            },
        )
        self.assertEqual(
            "image-workers",
            self.redis.hashes["wr:test:worker-id-owners"]["worker-1"],
        )
        self.assertIn(
            "worker-1",
            self.redis.zsets["wr:test:score:image-workers"],
        )

    def test_upsert_worker_for_missing_group_is_not_found(self) -> None:
        result = self.runtime.upsert_worker(
            declaration=self.worker_declaration("worker-1"),
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(self.redis.zsets, {})

    def test_upsert_worker_requires_endpoint_manager_id(self) -> None:
        self.upsert_group()

        result = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                endpoint_manager_id="",
            ),
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.INVALID)
        self.assertEqual(self.redis.zsets, {})

    def test_worker_runtime_rejects_legacy_descriptor_json(self) -> None:
        self.upsert_group()
        self.redis.hset(
            "wr:test:workers:image-workers",
            "worker-1",
            (
                '{"workerId":"worker-1","workerGroupId":"image-workers",'
                '"endpointManagerId":"endpoint-manager-1",'
                '"systemMetadata":{},"staticAttributes":{},'
                '"dynamicAttributeNames":[]}'
            ),
        )

        result = self.runtime.upsert_worker(
            declaration=self.worker_declaration("worker-1"),
        )
        rows = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.INVALID)
        self.assertIsNone(rows["worker-1"])
        self.assertEqual(self.redis.zsets, {})

    def test_upserted_worker_enters_hot_acquire_after_current_slot(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))

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

    def test_existing_worker_upsert_replaces_only_worker_attributes(self) -> None:
        self.upsert_group()
        original = self.worker_declaration(
            "worker-1",
            attributes={"runtime": "python", "removed": True},
        )
        self.upsert_worker(original)

        self.catalog.update_worker_platform_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            attributes={"tier": "premium"},
        )

        result = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                attributes={"runtime": "java"},
            ),
        )

        stored = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert stored is not None
        self.assertEqual(stored.attributes, {"runtime": "java"})
        self.assertEqual(stored.platform_attributes, {"tier": "premium"})
        state = self.score_band.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        assert state is not None
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, state.polarity)
        self.assertEqual(1, state.dirty)

    def test_reconnect_flips_recovery_score_without_changing_coordinate(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration(
            "worker-1",
            attributes={"runtime": "python"},
        )
        self.upsert_worker(declaration)
        hot = self.score_band.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        assert hot is not None
        recovery = self.score_band.toggle_current_polarity(
            home_bucket_id="image-workers",
            worker_id="worker-1",
            observed_score=hot.score,
            target_lane_rank=hot.lane_rank,
        )
        self.assertEqual(
            recovery.status,
            WorkerScoreTransitionStatus.TRANSITIONED,
        )

        result = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                attributes={"runtime": "java"},
            )
        )
        hot_after_reconnect = self.score_band.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert hot_after_reconnect is not None
        self.assertEqual(hot_after_reconnect.polarity, WorkerScorePolarity.HOT_ACQUIRE)
        self.assertEqual(hot_after_reconnect.score, abs(recovery.score or 0) + 1)
        self.assertEqual(hot_after_reconnect.lane_rank, self.LANE_RANK)
        self.assertEqual(hot_after_reconnect.dirty, 1)

    def test_worker_identity_conflict_does_not_change_descriptor_or_score(self) -> None:
        self.upsert_group()
        declaration = self.worker_declaration(
            "worker-1",
            attributes={"runtime": "python"},
        )
        self.upsert_worker(declaration)
        before_score = self.score_band.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        endpoint_conflict = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                endpoint_manager_id="other-endpoint",
                attributes={"runtime": "java"},
            )
        )
        dynamic_conflict = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "worker-1",
                attributes={"runtime": "java"},
                dynamic_attribute_names=frozenset({"load"}),
            )
        )
        stored = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]
        after_score = self.score_band.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(endpoint_conflict.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(dynamic_conflict.status, WorkerRuntimeStatus.CONFLICT)
        self.assertEqual(stored, self.expected_descriptor(declaration))
        self.assertEqual(after_score, before_score)

    def test_upsert_repairs_descriptor_only_and_score_only_residue(self) -> None:
        self.upsert_group()
        descriptor_only = self.worker_declaration("descriptor-only")
        self.upsert_worker(descriptor_only)
        del self.redis.zsets["wr:test:score:image-workers"]["descriptor-only"]

        repaired_descriptor_only = self.runtime.upsert_worker(
            declaration=descriptor_only
        )

        score_only = self.worker_declaration("score-only")
        self.upsert_worker(score_only)
        self.redis.hdel("wr:test:workers:image-workers", "score-only")
        repaired_score_only = self.runtime.upsert_worker(declaration=score_only)

        self.assertEqual(
            repaired_descriptor_only.status,
            WorkerRuntimeStatus.OK,
        )
        self.assertEqual(repaired_score_only.status, WorkerRuntimeStatus.OK)
        states = self.score_band.get_score_states(
            home_bucket_id="image-workers",
            worker_ids=["descriptor-only", "score-only"],
        )
        self.assertTrue(all(state is not None for state in states.values()))
        descriptors = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["descriptor-only", "score-only"],
        )
        self.assertTrue(
            all(descriptor is not None for descriptor in descriptors.values())
        )

    def test_same_worker_id_conflicts_across_worker_groups(self) -> None:
        self.upsert_group()
        self.catalog.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id="audio-workers",
                attributes={},
                event_codes=frozenset({"transcribe"}),
            )
        )

        image = self.runtime.upsert_worker(
            declaration=self.worker_declaration("shared")
        )
        audio = self.runtime.upsert_worker(
            declaration=self.worker_declaration(
                "shared",
                worker_group_id="audio-workers",
                endpoint_manager_id="audio-endpoint",
            )
        )

        self.assertEqual(image.status, WorkerRuntimeStatus.OK)
        self.assertEqual(audio.status, WorkerRuntimeStatus.CONFLICT)
        self.assertIsNotNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="image-workers",
                worker_ids=["shared"],
            )["shared"]
        )
        self.assertIsNone(
            self.catalog.get_worker_descriptors(
                worker_group_id="audio-workers",
                worker_ids=["shared"],
            )["shared"]
        )

    def test_get_workers_is_scoped_to_one_explicit_group(self) -> None:
        self.upsert_group()
        other_group = WorkerGroupDescriptor(
            worker_group_id="audio-workers",
            attributes={},
            event_codes=frozenset({"transcribe"}),
        )
        self.catalog.upsert_worker_group(descriptor=other_group)
        image_worker = self.worker_declaration("image-worker")
        audio_worker = self.worker_declaration(
            "audio-worker",
            worker_group_id="audio-workers",
        )
        self.upsert_worker(image_worker)
        self.upsert_worker(audio_worker)

        rows = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["image-worker", "audio-worker", "missing"],
        )

        self.assertEqual(rows["image-worker"], self.expected_descriptor(image_worker))
        self.assertIsNone(rows["audio-worker"])
        self.assertIsNone(rows["missing"])

        audio_rows = self.catalog.get_worker_descriptors(
            worker_group_id="audio-workers",
            worker_ids=["audio-worker"],
        )
        self.assertEqual(
            audio_rows["audio-worker"],
            self.expected_descriptor(audio_worker),
        )

    def test_platform_attributes_update_merges_without_touching_other_fields(self) -> None:
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                attributes={"runtime": "python"},
            )
        )
        self.catalog.update_worker_platform_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            attributes={"tier": "standard", "region": "us"},
        )

        result = self.catalog.update_worker_platform_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            attributes={"tier": "premium"},
        )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert descriptor is not None
        self.assertEqual(descriptor.platform_attributes, {"tier": "premium", "region": "us"})
        self.assertEqual(descriptor.attributes, {"runtime": "python"})
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))
        self.assertEqual(descriptor.endpoint_manager_id, "endpoint-manager-1")

    def test_catalog_updates_do_not_discover_worker_group(self) -> None:
        self.upsert_group()
        original = self.worker_declaration(
            "worker-1",
            attributes={"runtime": "python"},
        )
        self.upsert_worker(original)

        platform_result = self.catalog.update_worker_platform_attributes(
            worker_group_id="wrong-group",
            worker_id="worker-1",
            attributes={"tier": "premium"},
        )
        stored = self.catalog.get_worker_descriptors(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
        )["worker-1"]

        self.assertEqual(platform_result.status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(stored, self.expected_descriptor(original))

    def test_dynamic_attribute_runtime_dispatches_allowed_updates(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
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
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
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

    def test_dynamic_attribute_candidate_query_is_bounded_and_handler_owned(
        self,
    ) -> None:
        calls: list[tuple[str, dict[str, object], int]] = []

        def query_battery_candidates(
            worker_group_id: str,
            operator_rule: dict[str, object],
            limit: int,
        ) -> tuple[str, ...]:
            calls.append((worker_group_id, dict(operator_rule), limit))
            return "worker-2", "worker-1", "worker-2"

        runtime = RedisWorkerDynamicAttributeRuntime(
            self.catalog,
            update_handlers={},
            candidate_query_handlers={
                "battery": (
                    frozenset({"$gte", "$lte"}),
                    query_battery_candidates,
                )
            },
        )

        self.assertTrue(
            runtime.supports_candidate_query(
                attribute_name="battery",
                operator_rule={"$gte": 80},
            )
        )
        self.assertFalse(
            runtime.supports_candidate_query(
                attribute_name="battery",
                operator_rule={"$eq": 80},
            )
        )
        self.assertEqual(
            ("worker-2", "worker-1"),
            runtime.query_candidate_worker_ids(
                worker_group_id="image-workers",
                attribute_name="battery",
                operator_rule={"$gte": 80},
                limit=2,
            ),
        )
        self.assertEqual(
            [("image-workers", {"$gte": 80}, 2)],
            calls,
        )


if __name__ == "__main__":
    unittest.main()
