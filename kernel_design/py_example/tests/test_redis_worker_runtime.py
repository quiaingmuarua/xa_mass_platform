from __future__ import annotations

import unittest

from kernel_design.py_example import (
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    WorkerCandidateMatcher,
    WorkerConstraintQuery,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)
from kernel_design.py_example.kernel.worker_runtime import DynamicAttributeReadResult


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}

    def hset(
        self,
        name: str,
        key: str | None = None,
        value: object | None = None,
        mapping: dict[str, object] | None = None,
    ) -> int:
        hash_row = self.hashes.setdefault(name, {})
        before = set(hash_row)
        if mapping is not None:
            for field, mapped_value in mapping.items():
                hash_row[str(field)] = self._stringify(mapped_value)
        else:
            assert key is not None
            hash_row[str(key)] = self._stringify(value)
        return len(set(hash_row) - before)

    def hget(
        self,
        name: str,
        key: str,
    ) -> str | None:
        return self.hashes.get(name, {}).get(key)

    def hmget(
        self,
        name: str,
        keys: list[str],
    ) -> list[str | None]:
        hash_row = self.hashes.get(name, {})
        return [hash_row.get(key) for key in keys]

    def hdel(
        self,
        name: str,
        *keys: str,
    ) -> int:
        hash_row = self.hashes.get(name, {})
        removed = 0
        for key in keys:
            if key in hash_row:
                removed += 1
                del hash_row[key]
        return removed

    @staticmethod
    def _stringify(value: object) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return "" if value is None else str(value)


class RedisWorkerRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.catalog = RedisWorkerResourceCatalog(self.redis, prefix="test")
        self.group = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image"},
            event_codes=frozenset({"resize"}),
        )

    def register_group(self) -> None:
        result = self.catalog.register_worker_group_descriptor(descriptor=self.group)
        self.assertEqual(result.status, WorkerRuntimeStatus.OK)

    def worker_descriptor(
        self,
        worker_id: str,
        *,
        worker_group_id: str = "image-workers",
        system_metadata: dict[str, object] | None = None,
        static_attributes: dict[str, object] | None = None,
        dynamic_attribute_names: frozenset[str] = frozenset({"battery"}),
    ) -> WorkerDescriptor:
        return WorkerDescriptor(
            worker_id=worker_id,
            worker_group_id=worker_group_id,
            system_metadata=system_metadata or {},
            static_attributes=static_attributes or {},
            dynamic_attribute_names=dynamic_attribute_names,
        )

    def register_worker(
        self,
        descriptor: WorkerDescriptor,
    ) -> None:
        result = self.catalog.register_worker_descriptor(descriptor=descriptor)
        self.assertEqual(result.status, WorkerRuntimeStatus.OK)

    def test_register_and_read_worker_group_descriptor(self) -> None:
        self.register_group()

        rows = self.catalog.get_worker_group_descriptors(
            worker_group_ids=["image-workers", "missing"],
        )

        self.assertEqual(rows["image-workers"], self.group)
        self.assertIsNone(rows["missing"])
        self.assertIn("image-workers", self.redis.hashes["wr:test:groups"])

    def test_register_worker_descriptor_writes_group_hash_and_home_index(self) -> None:
        self.register_group()
        descriptor = self.worker_descriptor(
            "worker-1",
            system_metadata={"tier": "premium"},
            static_attributes={"runtime": "python"},
        )
        self.register_worker(descriptor)

        rows = self.catalog.get_worker_descriptors(worker_ids=["worker-1"])

        self.assertEqual(rows["worker-1"], descriptor)
        self.assertIn("worker-1", self.redis.hashes["wr:test:workers:image-workers"])
        self.assertEqual(
            "image-workers",
            self.redis.hashes["wr:test:worker-home"]["worker-1"],
        )

    def test_register_worker_for_missing_group_is_not_found(self) -> None:
        result = self.catalog.register_worker_descriptor(
            descriptor=self.worker_descriptor("worker-1"),
        )

        self.assertEqual(result.status, WorkerRuntimeStatus.NOT_FOUND)

    def test_get_workers_batches_across_home_groups(self) -> None:
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
            worker_ids=["image-worker", "audio-worker", "missing"],
        )

        self.assertEqual(rows["image-worker"], image_worker)
        self.assertEqual(rows["audio-worker"], audio_worker)
        self.assertIsNone(rows["missing"])

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
            worker_id="worker-1",
            metadata={"tier": "premium"},
        )
        descriptor = self.catalog.get_worker_descriptors(worker_ids=["worker-1"])[
            "worker-1"
        ]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert descriptor is not None
        self.assertEqual(descriptor.system_metadata, {"tier": "premium", "region": "us"})
        self.assertEqual(descriptor.static_attributes, {"runtime": "python"})
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))

    def test_static_attribute_refresh_replaces_only_static_attributes(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                system_metadata={"tier": "premium"},
                static_attributes={"runtime": "python", "old": True},
            )
        )

        result = self.catalog.refresh_worker_static_attributes(
            worker_id="worker-1",
            attributes={"runtime": "java"},
        )
        descriptor = self.catalog.get_worker_descriptors(worker_ids=["worker-1"])[
            "worker-1"
        ]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        assert descriptor is not None
        self.assertEqual(descriptor.system_metadata, {"tier": "premium"})
        self.assertEqual(descriptor.static_attributes, {"runtime": "java"})
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))

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
            worker_id="missing",
            updates={"battery": 1},
            observed_at_millis=1,
        )
        rejected_attr = runtime.update_worker_dynamic_attributes(
            worker_id="worker-1",
            updates={"load": 1},
            observed_at_millis=1,
        )
        missing_handler = runtime.update_worker_dynamic_attributes(
            worker_id="worker-1",
            updates={"network": "wifi"},
            observed_at_millis=1,
        )

        self.assertEqual(missing_worker["battery"].status, WorkerRuntimeStatus.NOT_FOUND)
        self.assertEqual(rejected_attr["load"].status, WorkerRuntimeStatus.REJECTED)
        self.assertEqual(missing_handler["network"].status, WorkerRuntimeStatus.NOT_FOUND)

    def test_candidate_matcher_matches_bounded_workers_and_preserves_order(self) -> None:
        self.register_group()
        other_group = WorkerGroupDescriptor(
            worker_group_id="audio-workers",
            attributes={},
            event_codes=frozenset({"transcribe"}),
        )
        self.catalog.register_worker_group_descriptor(descriptor=other_group)
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                system_metadata={"tier": "premium"},
                static_attributes={"runtime": "python"},
            )
        )
        self.register_worker(
            self.worker_descriptor(
                "worker-2",
                system_metadata={"tier": "standard"},
                static_attributes={"runtime": "java"},
            )
        )
        self.register_worker(
            self.worker_descriptor(
                "outside",
                worker_group_id="audio-workers",
                system_metadata={"tier": "premium"},
                static_attributes={"runtime": "python"},
            )
        )

        def query_battery(worker_id: str) -> DynamicAttributeReadResult:
            if worker_id == "worker-1":
                return DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
            if worker_id == "worker-2":
                return DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=10,
                )
            return DynamicAttributeReadResult(WorkerRuntimeStatus.NOT_FOUND)

        matcher = WorkerCandidateMatcher(self.catalog, {"battery": query_battery})
        rows = matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_ids=["worker-2", "outside", "worker-1"],
            candidate_constraints=[
                ("all", WorkerConstraintQuery.empty()),
                (
                    "premium-python-battery",
                    WorkerConstraintQuery(
                        {
                            "workerId": {"$in": ["worker-1", "outside"]},
                            "system.tier": {"$eq": "premium"},
                            "static.runtime": {"$eq": "python"},
                            "dynamic.battery": {"$gte": 20},
                        }
                    ),
                ),
            ],
        )

        self.assertEqual(
            rows,
            [
                ("all", ("worker-2", "worker-1")),
                ("premium-python-battery", ("worker-1",)),
            ],
        )

    def test_candidate_matcher_fails_missing_dynamic_handler_or_value(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        matcher_without_handler = WorkerCandidateMatcher(self.catalog, {})
        matcher_without_value = WorkerCandidateMatcher(
            self.catalog,
            {
                "battery": lambda worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.NOT_FOUND
                )
            },
        )
        constraints = [
            (
                "needs-battery",
                WorkerConstraintQuery({"dynamic.battery": {"$gte": 20}}),
            )
        ]

        self.assertEqual(
            [("needs-battery", ())],
            matcher_without_handler.match_worker_candidates(
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
                candidate_constraints=constraints,
            ),
        )
        self.assertEqual(
            [("needs-battery", ())],
            matcher_without_value.match_worker_candidates(
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
                candidate_constraints=constraints,
            ),
        )

    def test_candidate_matcher_never_discovers_workers_outside_input(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        self.register_worker(self.worker_descriptor("worker-2"))
        matcher = WorkerCandidateMatcher(self.catalog, {})

        rows = matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints=[("all", WorkerConstraintQuery.empty())],
        )

        self.assertEqual(rows, [("all", ("worker-1",))])

    def test_candidate_matcher_requires_declared_dynamic_attribute(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                dynamic_attribute_names=frozenset(),
            )
        )
        queried_worker_ids: list[str] = []

        def query_battery(worker_id: str) -> DynamicAttributeReadResult:
            queried_worker_ids.append(worker_id)
            return DynamicAttributeReadResult(WorkerRuntimeStatus.OK, value=90)

        matcher = WorkerCandidateMatcher(self.catalog, {"battery": query_battery})
        rows = matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints=[
                (
                    "needs-battery",
                    WorkerConstraintQuery({"dynamic.battery": {"$gte": 20}}),
                )
            ],
        )

        self.assertEqual(rows, [("needs-battery", ())])
        self.assertEqual(queried_worker_ids, [])

    def test_candidate_matcher_reads_dynamic_attribute_once_per_batch(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        query_count = 0

        def query_battery(worker_id: str) -> DynamicAttributeReadResult:
            nonlocal query_count
            query_count += 1
            return DynamicAttributeReadResult(WorkerRuntimeStatus.OK, value=90)

        matcher = WorkerCandidateMatcher(self.catalog, {"battery": query_battery})
        rows = matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints=[
                (
                    "candidate-1",
                    WorkerConstraintQuery({"dynamic.battery": {"$gte": 20}}),
                ),
                (
                    "candidate-2",
                    WorkerConstraintQuery({"dynamic.battery": {"$lte": 100}}),
                ),
            ],
        )

        self.assertEqual(
            rows,
            [
                ("candidate-1", ("worker-1",)),
                ("candidate-2", ("worker-1",)),
            ],
        )
        self.assertEqual(query_count, 1)

    def test_candidate_matcher_applies_worker_id_filter_before_dynamic_read(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        self.register_worker(self.worker_descriptor("worker-2"))
        queried_worker_ids: list[str] = []

        def query_battery(worker_id: str) -> DynamicAttributeReadResult:
            queried_worker_ids.append(worker_id)
            return DynamicAttributeReadResult(WorkerRuntimeStatus.OK, value=90)

        matcher = WorkerCandidateMatcher(self.catalog, {"battery": query_battery})
        rows = matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_ids=["worker-1", "worker-2"],
            candidate_constraints=[
                (
                    "worker-1-only",
                    WorkerConstraintQuery(
                        {
                            "workerId": {"$eq": "worker-1"},
                            "dynamic.battery": {"$gte": 20},
                        }
                    ),
                )
            ],
        )

        self.assertEqual(rows, [("worker-1-only", ("worker-1",))])
        self.assertEqual(queried_worker_ids, ["worker-1"])

    def test_candidate_matcher_rejects_metadata_before_dynamic_read(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                static_attributes={"runtime": "java"},
            )
        )
        queried_worker_ids: list[str] = []

        def query_battery(worker_id: str) -> DynamicAttributeReadResult:
            queried_worker_ids.append(worker_id)
            return DynamicAttributeReadResult(WorkerRuntimeStatus.OK, value=90)

        matcher = WorkerCandidateMatcher(self.catalog, {"battery": query_battery})
        rows = matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints=[
                (
                    "python-with-battery",
                    WorkerConstraintQuery(
                        {
                            "static.runtime": {"$eq": "python"},
                            "dynamic.battery": {"$gte": 20},
                        }
                    ),
                )
            ],
        )

        self.assertEqual(rows, [("python-with-battery", ())])
        self.assertEqual(queried_worker_ids, [])

    def test_candidate_matcher_rejects_duplicate_protocol_ids(self) -> None:
        matcher = WorkerCandidateMatcher(self.catalog, {})

        with self.assertRaises(ValueError):
            matcher.match_worker_candidates(
                worker_group_id="image-workers",
                worker_ids=["worker-1", "worker-1"],
                candidate_constraints=[("candidate-1", WorkerConstraintQuery.empty())],
            )

        with self.assertRaises(ValueError):
            matcher.match_worker_candidates(
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
                candidate_constraints=[
                    ("candidate-1", WorkerConstraintQuery.empty()),
                    ("candidate-1", WorkerConstraintQuery.empty()),
                ],
            )


if __name__ == "__main__":
    unittest.main()
