from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.executable_spec as executable_spec
import kernel_design.executable_spec.kernel as kernel

from kernel_design.executable_spec import (
    MappedWorkerPropertyIndexRuntime,
    RedisHashWorkerPropertyIndexProvider,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
)

from kernel_design.executable_spec.tests.redis_worker_runtime_test_support import (
    RedisWorkerRuntimeFixture,
)


class WorkerCandidateMatcherContractTest(unittest.TestCase):
    def test_contract_remains_bounded_and_outside_kernel_owner_exports(self) -> None:
        self.assertEqual(
            {field.name for field in fields(WorkerCandidateConstraint)},
            {"priority", "limit", "allocation_rule"},
        )
        self.assertEqual(
            set(inspect.signature(WorkerCandidateMatcher.__init__).parameters),
            {"self", "catalog", "property_index"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerCandidateMatcher.match_worker_candidates
                ).parameters
            ),
            {
                "self",
                "worker_group_id",
                "worker_lease_scores",
                "candidate_constraints",
            },
        )
        self.assertIs(
            executable_spec.WorkerCandidateMatcher,
            WorkerCandidateMatcher,
        )
        self.assertFalse(hasattr(kernel, "WorkerCandidateMatcher"))


class WorkerCandidateMatcherTest(RedisWorkerRuntimeFixture):
    def setUp(self) -> None:
        super().setUp()
        self.group = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image"},
            event_codes=frozenset({"resize"}),
        )
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
        self.matcher = WorkerCandidateMatcher(self.catalog, self.index)
        self.upsert_group()

    def add_worker(
        self,
        worker_id: str,
        *,
        worker_properties: dict[str, object],
        platform_properties: dict[str, object] | None = None,
        indexed_properties: dict[str, object] | None = None,
    ) -> None:
        self.upsert_worker(
            self.worker_declaration(
                worker_id,
                worker_properties=worker_properties,
            )
        )
        if platform_properties:
            result = self.catalog.patch_worker_platform_properties(
                worker_group_id="image-workers",
                worker_id=worker_id,
                properties=platform_properties,
            )
            self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        if indexed_properties:
            self.index.update_indexed_properties(
                worker_group_id="image-workers",
                worker_id=worker_id,
                updates=indexed_properties,
            )

    def match(self, rules: dict[str, object], worker_ids: list[str]) -> list[str]:
        rows = self.matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_lease_scores={
                worker_id: 1000 + index
                for index, worker_id in enumerate(worker_ids)
            },
            candidate_constraints={
                "candidate": WorkerCandidateConstraint(
                    priority=0,
                    limit=len(worker_ids) or 1,
                    allocation_rule=rules,
                )
            },
        )
        return [entry.worker_id for entry in rows["candidate"]]

    def test_precomputed_rematch_combines_snapshots_and_explicit_indexes(self) -> None:
        self.add_worker(
            "worker-1",
            worker_properties={"arch": "arm64", "region": "stale"},
            platform_properties={"poolView": "batch"},
            indexed_properties={
                "index.worker.region": "cn-east",
                "index.platform.pool": "batch",
            },
        )
        self.add_worker(
            "worker-2",
            worker_properties={"arch": "x86_64"},
            indexed_properties={
                "index.worker.region": "cn-east",
                "index.platform.pool": "batch",
            },
        )

        self.assertEqual(
            self.match(
                {
                    "worker.arch": {"$eq": "arm64"},
                    "worker.region": {"$eq": "stale"},
                    "index.worker.region": {"$eq": "cn-east"},
                    "index.platform.pool": {"$eq": "batch"},
                },
                ["worker-1", "worker-2"],
            ),
            ["worker-1"],
        )

    def test_explicit_index_missing_never_falls_back_to_snapshot(self) -> None:
        self.add_worker(
            "worker-1",
            worker_properties={"region": "cn-east"},
        )

        self.assertEqual(
            self.match(
                {"index.worker.region": {"$eq": "cn-east"}},
                ["worker-1"],
            ),
            [],
        )

    def test_nonindexed_property_reads_worker_and_platform_snapshots(self) -> None:
        self.add_worker(
            "worker-1",
            worker_properties={"arch": "arm64"},
            platform_properties={"tier": "premium"},
        )

        self.assertEqual(
            self.match(
                {
                    "worker.arch": {"$eq": "arm64"},
                    "platform.tier": {"$eq": "premium"},
                },
                ["worker-1"],
            ),
            ["worker-1"],
        )

    def test_projection_read_failure_fails_closed(self) -> None:
        self.add_worker(
            "worker-1",
            worker_properties={},
            indexed_properties={"index.worker.region": "cn-east"},
        )

        class FailingIndex:
            def load_indexed_property_values(self, **_: object) -> object:
                raise RuntimeError("index unavailable")

        matcher = WorkerCandidateMatcher(self.catalog, FailingIndex())
        with self.assertLogs(
            "kernel_design.executable_spec.scheduling.worker_candidate.matching",
            level="WARNING",
        ) as logs:
            rows = matcher.match_worker_candidates(
                worker_group_id="image-workers",
                worker_lease_scores={"worker-1": 1000},
                candidate_constraints={
                    "candidate": WorkerCandidateConstraint(
                        priority=0,
                        limit=1,
                        allocation_rule={
                            "index.worker.region": {"$eq": "cn-east"}
                        },
                    )
                },
            )
        self.assertEqual(rows["candidate"], ())
        self.assertEqual(1, len(logs.output))
        self.assertIn("PROVIDER_FAILURE", logs.output[0])

    def test_each_index_reads_only_workers_from_referencing_candidates(self) -> None:
        self.add_worker(
            "worker-1",
            worker_properties={},
            indexed_properties={"index.worker.region": "cn-east"},
        )
        self.add_worker(
            "worker-2",
            worker_properties={},
            indexed_properties={"index.worker.region": "cn-east"},
        )
        self.add_worker(
            "worker-3",
            worker_properties={},
            indexed_properties={"index.platform.pool": "batch"},
        )
        self.redis.hmget_calls.clear()

        self.matcher.filter_candidate_worker_ids(
            worker_group_id="image-workers",
            candidate_worker_ids={
                "region": ("worker-1", "worker-2"),
                "pool": ("worker-3",),
            },
            candidate_constraints={
                "region": WorkerCandidateConstraint(
                    0,
                    2,
                    {"index.worker.region": {"$eq": "cn-east"}},
                ),
                "pool": WorkerCandidateConstraint(
                    1,
                    1,
                    {"index.platform.pool": {"$eq": "batch"}},
                ),
            },
        )

        self.assertIn(
            (
                "wr:test:property-index:image-workers:"
                "index.worker.region:values",
                ("worker-1", "worker-2"),
            ),
            self.redis.hmget_calls,
        )
        self.assertIn(
            (
                "wr:test:property-index:image-workers:"
                "index.platform.pool:values",
                ("worker-3",),
            ),
            self.redis.hmget_calls,
        )

    def test_worker_id_remains_a_builtin_match_coordinate(self) -> None:
        self.add_worker("worker-1", worker_properties={})
        self.add_worker("worker-2", worker_properties={})

        self.assertEqual(
            self.match(
                {"workerId": {"$eq": "worker-2"}},
                ["worker-1", "worker-2"],
            ),
            ["worker-2"],
        )

    def test_priority_and_per_candidate_limits_assign_each_worker_once(self) -> None:
        for worker_id in ("worker-1", "worker-2", "worker-3", "worker-4"):
            self.add_worker(worker_id, worker_properties={})

        rows = self.matcher.match_worker_candidates(
            worker_group_id="image-workers",
            worker_lease_scores={
                worker_id: 1000 + index
                for index, worker_id in enumerate(
                    ("worker-1", "worker-2", "worker-3", "worker-4")
                )
            },
            candidate_constraints={
                "fallback": WorkerCandidateConstraint(99, 2, {}),
                "preferred": WorkerCandidateConstraint(0, 1, {}),
            },
        )

        self.assertEqual(
            {
                candidate_id: [entry.worker_id for entry in entries]
                for candidate_id, entries in rows.items()
            },
            {
                "preferred": ["worker-1"],
                "fallback": ["worker-2", "worker-3"],
            },
        )

    def test_invalid_constraint_is_isolated_from_valid_candidate(self) -> None:
        self.add_worker("worker-1", worker_properties={})
        with self.assertLogs(
            "kernel_design.executable_spec.scheduling.worker_candidate.matching",
            level="WARNING",
        ) as logs:
            rows = self.matcher.match_worker_candidates(
                worker_group_id="image-workers",
                worker_lease_scores={"worker-1": 1000},
                candidate_constraints={
                    "invalid": WorkerCandidateConstraint(
                        0,
                        1,
                        {"worker.region": {"$unknown": "cn-east"}},
                    ),
                    "valid": WorkerCandidateConstraint(99, 1, {}),
                },
            )

        self.assertEqual(rows["invalid"], ())
        self.assertEqual(rows["valid"][0].worker_id, "worker-1")
        self.assertEqual(1, len(logs.output))
        self.assertIn("candidateCount=1", logs.output[0])

    def test_matcher_validates_priority_and_limit(self) -> None:
        for priority in (-1, 100, True):
            with self.subTest(priority=priority), self.assertRaises(ValueError):
                self.matcher.match_worker_candidates(
                    worker_group_id="image-workers",
                    worker_lease_scores={},
                    candidate_constraints={
                        "candidate": WorkerCandidateConstraint(
                            priority,
                            1,
                            {},
                        )
                    },
                )
        with self.assertRaises(ValueError):
            self.matcher.match_worker_candidates(
                worker_group_id="image-workers",
                worker_lease_scores={},
                candidate_constraints={
                    "candidate": WorkerCandidateConstraint(0, 0, {})
                },
            )

    def test_route_evidence_is_not_match_context(self) -> None:
        self.add_worker("worker-1", worker_properties={})
        self.assertEqual(
            self.match(
                {"endpointManagerId": {"$eq": "endpoint-manager-1"}},
                ["worker-1"],
            ),
            [],
        )

    def test_matcher_never_discovers_workers_outside_input(self) -> None:
        self.add_worker("worker-1", worker_properties={})
        self.add_worker("worker-2", worker_properties={})

        self.assertEqual(self.match({}, ["worker-1"]), ["worker-1"])
        self.assertEqual(
            self.matcher.match_worker_candidates(
                worker_group_id="image-workers",
                worker_lease_scores={"worker-1": 1000},
                candidate_constraints={},
            ),
            {},
        )


if __name__ == "__main__":
    unittest.main()
