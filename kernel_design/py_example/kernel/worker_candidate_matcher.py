from __future__ import annotations

from typing import Mapping, Sequence

from ..constraint_dsl import ConstraintDsl, ConstraintMap, UNRESOLVED_VALUE
from .worker_score import WorkerId
from .worker_runtime import (
    CandidateId,
    DynamicAttributeQueryRegistry,
    DynamicAttributeReadResult,
    WorkerCandidateConstraint,
    WorkerCandidateMatches,
    WorkerDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntimeStatus,
)


class WorkerCandidateMatcher:
    """Assign each bounded worker to its first matching non-full candidate."""

    def __init__(
        self,
        catalog: WorkerResourceCatalog,
        query_dynamic_attributes_dict: DynamicAttributeQueryRegistry,
    ) -> None:
        self.catalog = catalog
        self.query_dynamic_attributes_dict = query_dynamic_attributes_dict

    def match_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> WorkerCandidateMatches:
        self._validate_worker_ids(worker_ids)
        if not candidate_constraints:
            return {}

        candidates, dynamic_attributes = self._prepare_candidates(candidate_constraints)
        matched_worker_ids: WorkerCandidateMatches = {
            candidate_id: [] for candidate_id, _, _ in candidates
        }
        if not worker_ids:
            return matched_worker_ids
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
            dynamic_attributes,
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
                dynamic_attributes,
                dynamic_rows,
            )
            for candidate_id, constraints, match_rules in candidates:
                if len(matched_worker_ids[candidate_id]) >= constraints.limit:
                    continue
                if ConstraintDsl.evaluate_match_rules(context, match_rules):
                    matched_worker_ids[candidate_id].append(worker_id)
                    remaining_capacity -= 1
                    break

        return matched_worker_ids

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
        missing_handlers: set[str] = set()
        ordered_constraints = sorted(
            candidate_constraints.items(),
            key=lambda item: (-item[1].priority, item[0]),
        )
        for candidate_id, constraints in ordered_constraints:
            if not candidate_id:
                raise ValueError("candidate id must be non-empty")
            if constraints.limit <= 0:
                raise ValueError("candidate limit must be positive")
            match_rules = ConstraintDsl.compile_match_rules(constraints.match_rules)
            acquire_fields = set(constraints.acquire_fields)
            if len(acquire_fields) != len(constraints.acquire_fields):
                raise ValueError("acquire_fields must be unique")
            if not acquire_fields.issubset(match_rules):
                raise ValueError("every acquire field must be used by match_rules")
            if any(not field.startswith("dynamic.") for field in acquire_fields):
                raise ValueError("worker matcher only acquires dynamic.* fields")
            if any(
                field.startswith("dynamic.") and field not in acquire_fields
                for field in match_rules
            ):
                raise ValueError(
                    "dynamic match fields must be declared in acquire_fields"
                )
            for field_name in constraints.acquire_fields:
                attribute_name = field_name.removeprefix("dynamic.")
                required_attributes.setdefault(attribute_name, None)
                if attribute_name not in self.query_dynamic_attributes_dict:
                    missing_handlers.add(attribute_name)
            candidates.append((candidate_id, constraints, match_rules))

        if missing_handlers:
            raise ValueError(
                "missing dynamic attribute query handlers: "
                + ", ".join(sorted(missing_handlers))
            )
        return candidates, tuple(required_attributes)

    def _acquire_dynamic_rows(
        self,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        descriptors: Mapping[WorkerId, WorkerDescriptor | None],
        dynamic_attributes: Sequence[str],
    ) -> dict[str, Mapping[WorkerId, DynamicAttributeReadResult]]:
        rows_by_attribute: dict[
            str,
            Mapping[WorkerId, DynamicAttributeReadResult],
        ] = {}
        for attribute_name in dynamic_attributes:
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
                self.query_dynamic_attributes_dict[attribute_name](
                    worker_group_id,
                    supported_worker_ids,
                )
                if supported_worker_ids
                else {}
            )
        return rows_by_attribute

    @staticmethod
    def _build_context(
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
        dynamic_attributes: Sequence[str],
        dynamic_rows: Mapping[
            str,
            Mapping[WorkerId, DynamicAttributeReadResult],
        ],
    ) -> dict[str, object]:
        dynamic_values: dict[str, object] = {}
        for attribute_name in dynamic_attributes:
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

    @staticmethod
    def _validate_worker_ids(
        worker_ids: Sequence[WorkerId],
    ) -> None:
        if len(worker_ids) != len(set(worker_ids)):
            raise ValueError("worker ids must be unique within one match call")
