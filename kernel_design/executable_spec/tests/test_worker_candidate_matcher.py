from __future__ import annotations

import inspect
import unittest
from dataclasses import fields
from typing import Callable, Sequence

import kernel_design.executable_spec as executable_spec
import kernel_design.executable_spec.kernel as kernel
from kernel_design.executable_spec import (
    RedisWorkerDynamicAttributeRuntime,
    WorkerCandidateAcquisition,
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)
from kernel_design.executable_spec.kernel.worker_runtime import DynamicAttributeReadResult

from kernel_design.executable_spec.tests.redis_worker_runtime_test_support import (
    RedisWorkerRuntimeFixture,
)


_DynamicAttributeQueryHandler = Callable[
    [str, Sequence[str]],
    dict[str, DynamicAttributeReadResult],
]


def candidate_constraint(
    allocation_rule: dict[str, object] | None = None,
    *,
    priority: int = 0,
    limit: int = 1,
) -> WorkerCandidateConstraint:
    return WorkerCandidateConstraint(
        priority=priority,
        limit=limit,
        allocation_rule={} if allocation_rule is None else allocation_rule,
    )


class WorkerCandidateMatcherContractTest(unittest.TestCase):
    def test_worker_candidate_constraint_is_bounded_priority_dto(self) -> None:
        constraint = WorkerCandidateConstraint(
            priority=99,
            limit=2,
            allocation_rule={"dynamic.battery": {"$gte": 20}},
        )

        self.assertEqual(
            {field.name for field in fields(WorkerCandidateConstraint)},
            {"priority", "limit", "allocation_rule"},
        )
        self.assertEqual(constraint.priority, 99)
        self.assertEqual(constraint.limit, 2)

    def test_worker_candidate_matcher_batches_constraint_queries(self) -> None:
        match_params = set(
            inspect.signature(WorkerCandidateMatcher.match_worker_candidates).parameters
        )
        init_params = set(inspect.signature(WorkerCandidateMatcher.__init__).parameters)

        self.assertEqual(
            init_params,
            {"self", "catalog", "dynamic_attribute_runtime"},
        )
        self.assertEqual(
            match_params,
            {
                "self",
                "worker_group_id",
                "worker_lease_scores",
                "candidate_constraints",
            },
        )
        self.assertFalse(hasattr(executable_spec, "WorkerCandidateMatchResult"))
        self.assertFalse(hasattr(executable_spec, "WorkerCandidateMatches"))

    def test_assignment_symbols_are_root_exports_not_kernel_exports(self) -> None:
        self.assertIs(executable_spec.WorkerCandidateMatcher, WorkerCandidateMatcher)
        self.assertFalse(hasattr(kernel, "WorkerCandidateMatcher"))
        self.assertFalse(hasattr(kernel, "WorkerCandidateConstraint"))


class WorkerCandidateMatcherTest(RedisWorkerRuntimeFixture):
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
        )

    def test_matcher_rejects_priority_outside_lane_range(self) -> None:
        matcher = self.matcher()
        for priority in (-1, 100, True):
            with self.subTest(priority=priority), self.assertRaises(ValueError):
                matcher.match_worker_candidates(
                    worker_group_id="image-workers",
                    worker_lease_scores={},
                    candidate_constraints={
                        "candidate": candidate_constraint(priority=priority),
                    },
                )

    def match_candidates(
        self,
        matcher: WorkerCandidateMatcher,
        *,
        worker_group_id: str = "image-workers",
        worker_ids: Sequence[str],
        candidate_constraints: dict[str, WorkerCandidateConstraint],
    ) -> WorkerCandidateAcquisition:
        worker_lease_scores = {
            worker_id: 1_000 + index
            for index, worker_id in enumerate(dict.fromkeys(worker_ids))
        }
        return matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_lease_scores=worker_lease_scores,
            candidate_constraints=candidate_constraints,
        )

    @staticmethod
    def reservation_ids(rows):
        return {
            candidate_id: [entry.worker_id for entry in entries]
            for candidate_id, entries in rows.items()
        }

    @staticmethod
    def endpoint_manager_ids(rows):
        return {
            entry.worker_id: entry.endpoint_manager_id
            for entries in rows.values()
            for entry in entries
        }

    def test_candidate_matcher_matches_bounded_workers_and_preserves_order(self) -> None:
        self.upsert_group()
        other_group = WorkerGroupDescriptor(
            worker_group_id="audio-workers",
            attributes={},
            event_codes=frozenset({"transcribe"}),
        )
        self.catalog.upsert_worker_group(descriptor=other_group)
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                attributes={"runtime": "python"},
            )
        )
        self.upsert_worker(
            self.worker_declaration(
                "worker-2",
                endpoint_manager_id="endpoint-manager-2",
                attributes={"runtime": "java"},
            )
        )
        self.upsert_worker(
            self.worker_declaration(
                "outside",
                worker_group_id="audio-workers",
                attributes={"runtime": "python"},
            )
        )
        self.catalog.update_worker_platform_attributes(
            worker_group_id="image-workers",
            worker_id="worker-1",
            attributes={"tier": "premium"},
        )
        self.catalog.update_worker_platform_attributes(
            worker_group_id="image-workers",
            worker_id="worker-2",
            attributes={"tier": "standard"},
        )
        self.catalog.update_worker_platform_attributes(
            worker_group_id="audio-workers",
            worker_id="outside",
            attributes={"tier": "premium"},
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
                "all": candidate_constraint(priority=99),
                "premium-python-battery": candidate_constraint(
                    {
                        "workerId": {"$in": ["worker-1", "outside"]},
                        "platform.tier": {"$eq": "premium"},
                        "attributes.runtime": {"$eq": "python"},
                        "dynamic.battery": {"$gte": 20},
                    },
                    priority=0,
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
        self.assertEqual(
            self.endpoint_manager_ids(rows),
            {
                "worker-1": "endpoint-manager-1",
                "worker-2": "endpoint-manager-2",
            },
        )
        self.assertEqual(
            {
                "worker-1": 1_002,
                "worker-2": 1_000,
            },
            {
                entry.worker_id: entry.worker_lease_score
                for entries in rows.values()
                for entry in entries
            },
        )

    def test_candidate_matcher_does_not_expose_endpoint_manager_id(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))

        rows = self.match_candidates(
            self.matcher(),
            worker_ids=["worker-1"],
            candidate_constraints={
                "transport-placement": candidate_constraint(
                    {"endpointManagerId": {"$eq": "endpoint-manager-1"}},
                )
            },
        )

        self.assertEqual(rows["transport-placement"], ())
        self.assertEqual(self.endpoint_manager_ids(rows), {})

    def test_candidate_matcher_rejects_missing_dynamic_handler(self) -> None:
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
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
                worker_ids=["worker-1"],
                candidate_constraints=constraints,
            )

    def test_candidate_matcher_derives_dynamic_fields_from_match_rules(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
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
            worker_ids=["worker-1"],
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
                worker_ids=[],
                candidate_constraints={
                    "candidate-1": candidate_constraint(limit=0)
                },
            )

    def test_candidate_matcher_isolates_one_corrupt_rule(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        matcher = self.matcher()

        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints={
                "corrupt": candidate_constraint(
                    {"attributes.runtime": {"$unknown": "python"}},
                    priority=0,
                ),
                "valid": candidate_constraint(priority=99),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {"corrupt": [], "valid": ["worker-1"]},
        )

    def test_candidate_matcher_fails_closed_for_unresolved_dynamic_value(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
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
            {"needs-battery": ()},
            self.match_candidates(
                matcher_without_value,
                worker_group_id="image-workers",
                worker_ids=["worker-1"],
                candidate_constraints=constraints,
            ),
        )

    def test_candidate_matcher_never_discovers_workers_outside_input(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        self.upsert_worker(self.worker_declaration("worker-2"))
        matcher = self.matcher()

        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            worker_ids=["worker-1"],
            candidate_constraints={"all": candidate_constraint()},
        )

        self.assertEqual(self.reservation_ids(rows), {"all": ["worker-1"]})

    def test_candidate_matcher_requires_declared_dynamic_attribute(self) -> None:
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
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
            worker_ids=["worker-1"],
            candidate_constraints={
                "needs-battery": candidate_constraint(
                    {"dynamic.battery": {"$gte": 20}},
                )
            },
        )

        self.assertEqual(self.reservation_ids(rows), {"needs-battery": []})
        self.assertEqual(queried_worker_ids, [])

    def test_candidate_matcher_reads_dynamic_attribute_once_per_batch(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        self.upsert_worker(self.worker_declaration("worker-2"))
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
            worker_ids=["worker-1", "worker-2"],
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
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
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
            worker_ids=["worker-1"],
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
        self.upsert_group()
        for worker_id in ("worker-1", "worker-2", "worker-3", "worker-4"):
            self.upsert_worker(self.worker_declaration(worker_id))

        matcher = self.matcher()
        rows = self.match_candidates(
            matcher,
            worker_group_id="image-workers",
            worker_ids=["worker-1", "worker-2", "worker-3", "worker-4"],
            candidate_constraints={
                "fallback": candidate_constraint(priority=99, limit=2),
                "preferred": candidate_constraint(priority=0, limit=1),
            },
        )

        self.assertEqual(
            self.reservation_ids(rows),
            {
                "preferred": ["worker-1"],
                "fallback": ["worker-2", "worker-3"],
            },
        )
        self.assertEqual(
            set(self.endpoint_manager_ids(rows)),
            {"worker-1", "worker-2", "worker-3"},
        )

    def test_candidate_matcher_batches_declared_fields_and_consumes_by_priority(self) -> None:
        self.upsert_group()
        dynamic_names = frozenset({"battery", "network"})
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                attributes={"runtime": "python"},
                dynamic_attribute_names=dynamic_names,
            )
        )
        self.upsert_worker(
            self.worker_declaration(
                "worker-2",
                attributes={"runtime": "java"},
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
                    priority=99,
                ),
                "python-network": candidate_constraint(
                    {
                        "attributes.runtime": {"$eq": "python"},
                        "dynamic.network": {"$eq": "wifi"},
                    },
                    priority=0,
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
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        self.upsert_worker(self.worker_declaration("worker-2"))

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
            worker_ids=["worker-1", "worker-2"],
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
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        self.upsert_worker(self.worker_declaration("worker-2"))
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
            worker_ids=["worker-1", "worker-2"],
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
        self.upsert_group()
        self.upsert_worker(
            self.worker_declaration(
                "worker-1",
                attributes={"runtime": "java"},
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
            worker_ids=["worker-1"],
            candidate_constraints={
                "python-with-battery": candidate_constraint(
                    {
                        "attributes.runtime": {"$eq": "python"},
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

    def test_candidate_matcher_deduplicates_input_before_matching(self) -> None:
        self.upsert_group()
        self.upsert_worker(self.worker_declaration("worker-1"))
        matcher = self.matcher()

        result = self.match_candidates(
            matcher,
            worker_ids=["worker-1", "missing", "worker-1"],
            candidate_constraints={
                "candidate-1": candidate_constraint(limit=2)
            },
        )

        self.assertEqual(self.reservation_ids(result), {"candidate-1": ["worker-1"]})
        self.assertEqual(
            self.endpoint_manager_ids(result),
            {"worker-1": "endpoint-manager-1"},
        )

    def test_candidate_matcher_returns_no_endpoint_rows_without_constraints(self) -> None:
        matcher = self.matcher()

        result = self.match_candidates(
            matcher,
            worker_ids=["worker-2", "worker-1", "worker-2"],
            candidate_constraints={},
        )

        self.assertEqual(result, {})


if __name__ == "__main__":
    unittest.main()
