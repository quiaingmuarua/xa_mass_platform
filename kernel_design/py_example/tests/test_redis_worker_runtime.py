from __future__ import annotations

import unittest
from typing import Callable, Sequence

from kernel_design.py_example import (
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisZsetWorkerScoreCore,
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)
from kernel_design.py_example.kernel.worker_runtime import (
    DynamicAttributeReadResult,
)


_DynamicAttributeQueryHandler = Callable[
    [str, Sequence[str]],
    dict[str, DynamicAttributeReadResult],
]


def candidate_constraint(
    match_rules: dict[str, object] | None = None,
    *,
    priority: int = 0,
    limit: int = 1,
) -> WorkerCandidateConstraint:
    return WorkerCandidateConstraint(
        priority=priority,
        limit=limit,
        match_rules={} if match_rules is None else match_rules,
    )


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.zsets: dict[str, dict[str, int]] = {}
        self.hmget_calls: list[tuple[str, tuple[str, ...]]] = []
        self.now_millis = 100_000

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
        self.hmget_calls.append((name, tuple(keys)))
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

    def zadd(
        self,
        name: str,
        mapping: dict[str, int],
        *,
        nx: bool = False,
    ) -> int:
        zset = self.zsets.setdefault(name, {})
        added = 0
        for member, score in mapping.items():
            if nx and member in zset:
                continue
            if member not in zset:
                added += 1
            zset[member] = score
        return added

    def eval(self, script: str, numkeys: int, *args: object) -> list[object]:
        if numkeys != 1:
            raise ValueError("unsupported fake redis script")
        key = str(args[0])
        if "local now_min_score" in script:
            return self._acquire_due_hot_lease(key, args[1:])
        if "local observed_score" not in script:
            raise ValueError("unsupported fake redis script")
        worker_id = str(args[1])
        observed_score = int(args[2])
        next_score = int(args[3])
        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        if stored != observed_score:
            return ["stale", stored]
        self.zadd(key, {worker_id: next_score})
        return ["transitioned", next_score]

    def _acquire_due_hot_lease(
        self,
        key: str,
        argv: tuple[object, ...],
    ) -> list[object]:
        worker_id = str(argv[0])
        now_min_score = int(argv[1])
        target_min_score = int(argv[2])
        slot_factor = int(argv[3])
        dirty_factor = int(argv[4])
        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        if stored <= 0:
            return ["invalid", stored]
        if stored >= now_min_score:
            return ["stale", stored]
        if target_min_score <= now_min_score:
            return ["invalid", stored]
        lane_rank = (stored % slot_factor) // dirty_factor
        next_score = target_min_score + lane_rank * dirty_factor
        self.zadd(key, {worker_id: next_score})
        return ["transitioned", next_score]

    def zscore(self, name: str, member: str) -> int | None:
        return self.zsets.get(name, {}).get(member)

    def zrangebyscore(
        self,
        name: str,
        min_score: int,
        max_score: int,
        *,
        start: int = 0,
        num: int | None = None,
        withscores: bool = False,
    ) -> list[object]:
        rows = sorted(
            (score, member)
            for member, score in self.zsets.get(name, {}).items()
            if min_score <= score <= max_score
        )
        sliced = rows[start:] if num is None else rows[start : start + num]
        if withscores:
            return [(member, score) for score, member in sliced]
        return [member for _, member in sliced]

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    @staticmethod
    def _stringify(value: object) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return "" if value is None else str(value)


class RedisWorkerRuntimeTest(unittest.TestCase):
    LANE_RANK = 5

    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.catalog = RedisWorkerResourceCatalog(self.redis, prefix="test")
        self.score_band = RedisZsetWorkerScoreCore(
            self.redis,
            score_key_prefix="wr:test:score",
        )
        self.runtime = RedisWorkerRuntime(
            self.redis,
            self.score_band,
            prefix="test",
        )
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
        result = self.runtime.register_worker_descriptor(
            descriptor=descriptor,
            lane_rank=self.LANE_RANK,
        )
        self.assertEqual(result.status, WorkerRuntimeStatus.OK)

    def matcher(
        self,
        query_handlers: dict[str, _DynamicAttributeQueryHandler] | None = None,
    ) -> WorkerCandidateMatcher:
        dynamic_attribute_runtime = RedisWorkerDynamicAttributeRuntime(
            self.catalog,
            {},
            query_handlers,
        )
        return WorkerCandidateMatcher(
            self.catalog,
            dynamic_attribute_runtime,
            self.score_band,
        )

    def match_candidates(
        self,
        matcher: WorkerCandidateMatcher,
        *,
        worker_group_id: str = "image-workers",
        worker_ids: Sequence[str] | None = None,
        candidate_constraints: dict[str, WorkerCandidateConstraint],
    ):
        self.redis.now_millis += self.score_band.SLOT_MILLIS
        if worker_ids is None:
            worker_ids = self.score_band.acquire_hot_acquire_candidates(
                home_bucket_id=worker_group_id,
                limit=100,
            )
        return matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=worker_ids,
            candidate_constraints=candidate_constraints,
            lease_until_millis=self.redis.now_millis + 1_000,
        )

    @staticmethod
    def reservation_ids(rows):
        return {
            candidate_id: [entry.worker_id for entry in entries]
            for candidate_id, entries in rows.items()
        }

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

    def test_registered_worker_enters_hot_acquire_after_current_slot(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))

        self.assertEqual(
            self.score_band.acquire_hot_acquire_candidates(
                home_bucket_id="image-workers",
                limit=10,
            ),
            [],
        )

        self.redis.now_millis += self.score_band.SLOT_MILLIS
        candidates = self.score_band.acquire_hot_acquire_candidates(
            home_bucket_id="image-workers",
            limit=10,
        )

        self.assertEqual(candidates, ["worker-1"])

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

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            self.assertEqual(worker_group_id, "image-workers")
            values = {"worker-1": 90, "worker-2": 10}
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=values[worker_id],
                )
                for worker_id in worker_ids
                if worker_id in values
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            worker_ids=["worker-2", "outside", "worker-1"],
            candidate_constraints={
                "all": candidate_constraint(priority=0),
                "premium-python-battery": candidate_constraint(
                    {
                        "workerId": {"$in": ["worker-1", "outside"]},
                        "system.tier": {"$eq": "premium"},
                        "static.runtime": {"$eq": "python"},
                        "dynamic.battery": {"$gte": 20},
                    },
                    priority=100,
                ),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {
                "premium-python-battery": ["worker-1"],
                "all": ["worker-2"],
            },
        )
        self.assertEqual(tuple(rows), ("premium-python-battery", "all"))

    def test_candidate_matcher_rejects_missing_dynamic_handler(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                dynamic_attribute_names=frozenset(),
            )
        )
        matcher_without_handler = self.matcher()
        constraints = {
            "needs-battery": candidate_constraint(
                {"dynamic.battery": {"$gte": 20}},
            )
        }

        with self.assertRaisesRegex(
            ValueError,
            "missing dynamic attribute query handler: battery",
        ):
            self.match_candidates(
                matcher_without_handler,
                worker_group_id="image-workers",
                candidate_constraints=constraints,
            )

    def test_candidate_matcher_derives_dynamic_fields_from_match_rules(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        queried_worker_ids: list[str] = []

        def query_battery(
            worker_group_id: str,
            worker_ids: Sequence[str],
        ) -> dict[str, DynamicAttributeReadResult]:
            queried_worker_ids.extend(worker_ids)
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
                for worker_id in worker_ids
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "needs-battery": candidate_constraint(
                    {"dynamic.battery": {"$gte": 20}}
                )
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"needs-battery": ["worker-1"]},
        )
        self.assertEqual(queried_worker_ids, ["worker-1"])

    def test_candidate_matcher_validates_candidate_limit(self) -> None:
        matcher = self.matcher()

        with self.assertRaisesRegex(ValueError, "candidate limit must be positive"):
            self.match_candidates(
                matcher,
                worker_group_id="image-workers",
                candidate_constraints={
                    "candidate-1": candidate_constraint(limit=0)
                },
            )

    def test_candidate_matcher_isolates_one_corrupt_rule(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        matcher = self.matcher()

        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "corrupt": candidate_constraint(
                    {"static.runtime": {"$unknown": "python"}},
                    priority=100,
                ),
                "valid": candidate_constraint(priority=0),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"corrupt": [], "valid": ["worker-1"]},
        )

    def test_candidate_matcher_fails_closed_for_unresolved_dynamic_value(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        matcher_without_value = self.matcher(
            {
                "battery": lambda _, worker_ids: {
                    worker_id: DynamicAttributeReadResult(
                        WorkerRuntimeStatus.NOT_FOUND
                    )
                    for worker_id in worker_ids
                }
            },
        )
        constraints = {
            "needs-battery": candidate_constraint(
                {"dynamic.battery": {"$gte": 20}},
            )
        }

        self.assertEqual(
            {"needs-battery": []},
            self.match_candidates(
                matcher_without_value,
                worker_group_id="image-workers",
                candidate_constraints=constraints,
            ),
        )

    def test_candidate_matcher_never_discovers_workers_outside_input(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        self.register_worker(self.worker_descriptor("worker-2"))
        matcher = self.matcher()

        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints={"all": candidate_constraint()},
        )

        self.assertEqual(self.reservation_ids(rows), {"all": ["worker-1"]})

    def test_candidate_matcher_requires_declared_dynamic_attribute(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                dynamic_attribute_names=frozenset(),
            )
        )
        queried_worker_ids: list[str] = []

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            queried_worker_ids.extend(worker_ids)
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
                for worker_id in worker_ids
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "needs-battery": candidate_constraint(
                    {"dynamic.battery": {"$gte": 20}},
                )
            },
        )

        self.assertEqual(self.reservation_ids(rows), {"needs-battery": []})
        self.assertEqual(queried_worker_ids, [])

    def test_candidate_matcher_reads_dynamic_attribute_once_per_batch(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        self.register_worker(self.worker_descriptor("worker-2"))
        query_batches: list[tuple[str, tuple[str, ...]]] = []

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            query_batches.append((worker_group_id, worker_ids))
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
                for worker_id in worker_ids
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "candidate-2": candidate_constraint(
                    {"dynamic.battery": {"$lte": 100}},
                ),
                "candidate-1": candidate_constraint(
                    {"dynamic.battery": {"$gte": 20}},
                    limit=2,
                ),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {
                "candidate-1": ["worker-1", "worker-2"],
                "candidate-2": [],
            },
        )
        self.assertEqual(
            query_batches,
            [("image-workers", ("worker-1", "worker-2"))],
        )

    def test_candidate_matcher_splits_only_the_dynamic_domain_dot(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                dynamic_attribute_names=frozenset({"battery.level"}),
            )
        )
        queried_worker_ids: list[str] = []

        def query_battery_level(
            worker_group_id: str,
            worker_ids: Sequence[str],
        ) -> dict[str, DynamicAttributeReadResult]:
            queried_worker_ids.extend(worker_ids)
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=87,
                )
                for worker_id in worker_ids
            }

        matcher = self.matcher({"battery.level": query_battery_level})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "candidate-1": candidate_constraint(
                    {"dynamic.battery.level": {"$gte": 80}},
                )
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"candidate-1": ["worker-1"]},
        )
        self.assertEqual(queried_worker_ids, ["worker-1"])

    def test_candidate_matcher_enforces_per_candidate_worker_limit(self) -> None:
        self.register_group()
        for worker_id in ("worker-1", "worker-2", "worker-3"):
            self.register_worker(self.worker_descriptor(worker_id))

        matcher = self.matcher()
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "fallback": candidate_constraint(priority=0, limit=2),
                "preferred": candidate_constraint(priority=100, limit=1),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {
                "preferred": ["worker-1"],
                "fallback": ["worker-2", "worker-3"],
            },
        )

    def test_candidate_matcher_batches_declared_fields_and_consumes_by_priority(self) -> None:
        self.register_group()
        dynamic_names = frozenset({"battery", "network"})
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                static_attributes={"runtime": "python"},
                dynamic_attribute_names=dynamic_names,
            )
        )
        self.register_worker(
            self.worker_descriptor(
                "worker-2",
                static_attributes={"runtime": "java"},
                dynamic_attribute_names=dynamic_names,
            )
        )
        query_batches: list[tuple[str, tuple[str, ...]]] = []

        def query_attribute(
            attribute_name: str,
        ) -> _DynamicAttributeQueryHandler:
            def query(
                worker_group_id: str,
                worker_ids: Sequence[str],
            ) -> dict[str, DynamicAttributeReadResult]:
                query_batches.append((attribute_name, tuple(worker_ids)))
                return {
                    worker_id: DynamicAttributeReadResult(
                        WorkerRuntimeStatus.OK,
                        value=90 if attribute_name == "battery" else "wifi",
                    )
                    for worker_id in worker_ids
                }

            return query

        matcher = self.matcher(
            {
                "battery": query_attribute("battery"),
                "network": query_attribute("network"),
            },
        )
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            worker_ids=["worker-2", "worker-1"],
            candidate_constraints={
                "battery": candidate_constraint(
                    {"dynamic.battery": {"$gte": 20}},
                    priority=0,
                ),
                "python-network": candidate_constraint(
                    {
                        "static.runtime": {"$eq": "python"},
                        "dynamic.network": {"$eq": "wifi"},
                    },
                    priority=100,
                ),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {
                "python-network": ["worker-1"],
                "battery": ["worker-2"],
            },
        )
        self.assertEqual(
            query_batches,
            [
                ("network", ("worker-2", "worker-1")),
                ("battery", ("worker-2", "worker-1")),
            ],
        )
        self.assertEqual(
            self.redis.hmget_calls,
            [("wr:test:workers:image-workers", ("worker-2", "worker-1"))],
        )

    def test_candidate_matcher_fails_closed_for_missing_batch_rows(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        self.register_worker(self.worker_descriptor("worker-2"))

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            self.assertEqual(worker_ids, ("worker-1", "worker-2"))
            return {
                "worker-1": DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "needs-battery": candidate_constraint(
                    {"dynamic.battery": {"$gte": 20}},
                )
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"needs-battery": ["worker-1"]},
        )

    def test_candidate_matcher_batches_acquire_before_worker_id_rule(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        self.register_worker(self.worker_descriptor("worker-2"))
        queried_worker_ids: list[str] = []

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            queried_worker_ids.extend(worker_ids)
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
                for worker_id in worker_ids
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "worker-1-only": candidate_constraint(
                    {
                        "workerId": {"$eq": "worker-1"},
                        "dynamic.battery": {"$gte": 20},
                    },
                )
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"worker-1-only": ["worker-1"]},
        )
        self.assertEqual(queried_worker_ids, ["worker-1", "worker-2"])

    def test_candidate_matcher_batches_acquire_before_static_rule(self) -> None:
        self.register_group()
        self.register_worker(
            self.worker_descriptor(
                "worker-1",
                static_attributes={"runtime": "java"},
            )
        )
        queried_worker_ids: list[str] = []

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            queried_worker_ids.extend(worker_ids)
            return {
                worker_id: DynamicAttributeReadResult(
                    WorkerRuntimeStatus.OK,
                    value=90,
                )
                for worker_id in worker_ids
            }

        matcher = self.matcher({"battery": query_battery})
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            candidate_constraints={
                "python-with-battery": candidate_constraint(
                    {
                        "static.runtime": {"$eq": "python"},
                        "dynamic.battery": {"$gte": 20},
                    },
                )
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"python-with-battery": []},
        )
        self.assertEqual(queried_worker_ids, ["worker-1"])

    def test_candidate_matcher_does_not_reallocate_active_reservation(self) -> None:
        self.register_group()
        self.register_worker(self.worker_descriptor("worker-1"))
        matcher = self.matcher()

        first = self.match_candidates(
            matcher,
            candidate_constraints={"candidate-1": candidate_constraint()},
        )
        second = self.match_candidates(
            matcher,
            candidate_constraints={"candidate-2": candidate_constraint()},
        )

        self.assertEqual(self.reservation_ids(first), {"candidate-1": ["worker-1"]})
        self.assertEqual(self.reservation_ids(second), {"candidate-2": []})


if __name__ == "__main__":
    unittest.main()
