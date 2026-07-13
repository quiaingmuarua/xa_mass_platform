from __future__ import annotations

from typing import Mapping, Sequence

from ..constraint_dsl import ConstraintDsl, ConstraintMap, UNRESOLVED_VALUE
from .task_dispatch_runtime import CandidateWorkerEntry
from .worker_score import (
    TimeMillis,
    WorkerId,
    WorkerScoreCore,
    WorkerScoreTransitionStatus,
)
from .worker_runtime import (
    CandidateId,
    DynamicAttributeReadResult,
    WorkerCandidateConstraint,
    WorkerDescriptor,
    WorkerDynamicAttributeRuntime,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntimeStatus,
)


WorkerCandidateMatches = dict[CandidateId, list[CandidateWorkerEntry]]


class WorkerCandidateMatcher:
    """Lease each bounded Worker to its first matching non-full candidate."""

    def __init__(
        self,
        catalog: WorkerResourceCatalog,
        dynamic_attribute_runtime: WorkerDynamicAttributeRuntime,
        worker_score: WorkerScoreCore,
    ) -> None:
        self.catalog = catalog
        self.dynamic_attribute_runtime = dynamic_attribute_runtime
        self.worker_score = worker_score

    def match_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateMatches:
        if not candidate_constraints:
            return {}

        worker_ids = tuple(dict.fromkeys(worker_ids))

        candidates, required_dynamic_attributes = self._prepare_candidates(
            candidate_constraints
        )
        matched_workers: WorkerCandidateMatches = {
            candidate_id: [] for candidate_id, _, _ in candidates
        }
        for candidate_id in candidate_constraints:
            matched_workers.setdefault(candidate_id, [])
        if not candidates:
            return matched_workers
        if not worker_ids:
            return matched_workers
        remaining_capacity = sum(
            constraints.limit for _, constraints, _ in candidates
        )

        descriptors = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=worker_ids,
        )
        dynamic_rows = self._acquire_dynamic_rows(
            worker_group_id,
            worker_ids,
            descriptors,
            required_dynamic_attributes,
        )
        for worker_id in worker_ids:
            if remaining_capacity == 0:
                break
            descriptor = descriptors.get(worker_id)
            if descriptor is None or descriptor.worker_group_id != worker_group_id:
                continue
            context = self._build_context(
                worker_id,
                descriptor,
                required_dynamic_attributes,
                dynamic_rows,
            )
            for candidate_id, constraints, match_rules in candidates:
                if len(matched_workers[candidate_id]) >= constraints.limit:
                    continue
                if ConstraintDsl.evaluate_match_rules(context, match_rules):
                    lease = self.worker_score.acquire_due_hot_score_lease(
                        home_bucket_id=worker_group_id,
                        worker_id=worker_id,
                        target_time_millis=lease_until_millis,
                    )
                    if (
                        lease.status is not WorkerScoreTransitionStatus.TRANSITIONED
                        or lease.score is None
                    ):
                        break
                    matched_workers[candidate_id].append(
                        CandidateWorkerEntry(
                            worker_id=worker_id,
                            worker_group_id=worker_group_id,
                            worker_lease_score=lease.score,
                        )
                    )
                    remaining_capacity -= 1
                    break

        return matched_workers

    def _prepare_candidates(
        self,
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> tuple[
        list[tuple[CandidateId, WorkerCandidateConstraint, ConstraintMap]],
        tuple[str, ...],
    ]:
        candidates: list[
            tuple[CandidateId, WorkerCandidateConstraint, ConstraintMap]
        ] = []
        required_attributes: dict[str, None] = {}
        ordered_constraints = sorted(
            candidate_constraints.items(),
            key=lambda item: (-item[1].priority, item[0]),
        )
        for candidate_id, constraints in ordered_constraints:
            if not candidate_id:
                raise ValueError("candidate id must be non-empty")
            if constraints.limit <= 0:
                raise ValueError("candidate limit must be positive")
            try:
                match_rules = ConstraintDsl.compile_match_rules(
                    constraints.match_rules
                )
            except ValueError:
                continue
            for field_name in match_rules:
                domain, separator, attribute_name = field_name.partition(".")
                if separator and domain == "dynamic":
                    required_attributes.setdefault(attribute_name, None)
            candidates.append((candidate_id, constraints, match_rules))

        return candidates, tuple(required_attributes)

    def _acquire_dynamic_rows(
        self,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        descriptors: Mapping[WorkerId, WorkerDescriptor | None],
        required_dynamic_attributes: Sequence[str],
    ) -> dict[str, Mapping[WorkerId, DynamicAttributeReadResult]]:
        rows_by_attribute: dict[
            str,
            Mapping[WorkerId, DynamicAttributeReadResult],
        ] = {}
        for attribute_name in required_dynamic_attributes:
            supported_worker_ids = tuple(
                worker_id
                for worker_id in worker_ids
                if (
                    (descriptor := descriptors.get(worker_id)) is not None
                    and descriptor.worker_group_id == worker_group_id
                    and attribute_name in descriptor.dynamic_attribute_names
                )
            )
            rows_by_attribute[attribute_name] = (
                self.dynamic_attribute_runtime.get_worker_dynamic_attribute_values(
                    worker_group_id=worker_group_id,
                    attribute_name=attribute_name,
                    worker_ids=supported_worker_ids,
                )
            )
        return rows_by_attribute

    @staticmethod
    def _build_context(
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
        required_dynamic_attributes: Sequence[str],
        dynamic_rows: Mapping[
            str,
            Mapping[WorkerId, DynamicAttributeReadResult],
        ],
    ) -> dict[str, object]:
        dynamic_values: dict[str, object] = {}
        for attribute_name in required_dynamic_attributes:
            result = dynamic_rows[attribute_name].get(worker_id)
            dynamic_values[attribute_name] = (
                result.value
                if result is not None and result.status is WorkerRuntimeStatus.OK
                else UNRESOLVED_VALUE
            )
        return {
            "workerId": worker_id,
            "system": descriptor.system_metadata,
            "static": descriptor.static_attributes,
            "dynamic": dynamic_values,
        }
