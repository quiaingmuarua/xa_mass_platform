from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence

from ...constraint_dsl import ConstraintEvaluator, ConstraintMap, UNRESOLVED_VALUE
from ...kernel.assignment_dispatch_runtime import (
    CandidateId,
    CandidateWorkerEntry,
)
from ...kernel.worker_score import Score, WorkerId
from ...kernel.worker_runtime import (
    DynamicAttributeReadResult,
    WorkerDescriptor,
    WorkerDynamicAttributeRuntime,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntimeStatus,
)


@dataclass(frozen=True)
class WorkerCandidateConstraint:
    """One candidate's match rules and per-call worker allocation bound."""

    priority: int
    limit: int
    match_rules: Mapping[str, object]


WorkerCandidateAcquisition = Mapping[
    CandidateId,
    tuple[CandidateWorkerEntry, ...],
]


class WorkerCandidateMatcher:
    """Match each bounded Worker to its first non-full candidate."""

    def __init__(
        self,
        catalog: WorkerResourceCatalog,
        dynamic_attribute_runtime: WorkerDynamicAttributeRuntime,
    ) -> None:
        self.catalog = catalog
        self.dynamic_attribute_runtime = dynamic_attribute_runtime

    def match_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_lease_scores: Mapping[WorkerId, Score],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> WorkerCandidateAcquisition:
        worker_ids = tuple(worker_lease_scores)
        if not candidate_constraints:
            return {}

        candidates, required_dynamic_attributes = self._prepare_candidates(
            candidate_constraints
        )
        mutable_matches: dict[CandidateId, list[CandidateWorkerEntry]] = {
            candidate_id: [] for candidate_id, _, _ in candidates
        }
        for candidate_id in candidate_constraints:
            mutable_matches.setdefault(candidate_id, [])
        if not candidates:
            return self._freeze_acquisitions(mutable_matches)
        if not worker_ids:
            return self._freeze_acquisitions(mutable_matches)
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
                if len(mutable_matches[candidate_id]) >= constraints.limit:
                    continue
                if ConstraintEvaluator.evaluate_match_rules(context, match_rules):
                    mutable_matches[candidate_id].append(
                        CandidateWorkerEntry(
                            worker_id=worker_id,
                            worker_group_id=worker_group_id,
                            endpoint_manager_id=descriptor.endpoint_manager_id,
                            worker_lease_score=worker_lease_scores[worker_id],
                        )
                    )
                    remaining_capacity -= 1
                    break

        return self._freeze_acquisitions(mutable_matches)

    @staticmethod
    def _freeze_acquisitions(
        matches: Mapping[CandidateId, Sequence[CandidateWorkerEntry]],
    ) -> WorkerCandidateAcquisition:
        return {
            candidate_id: tuple(entries)
            for candidate_id, entries in matches.items()
        }

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
                match_rules = ConstraintEvaluator.compile_match_rules(
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
            "platform": descriptor.platform_attributes,
            "attributes": descriptor.attributes,
            "dynamic": dynamic_values,
        }
