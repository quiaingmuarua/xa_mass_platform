from __future__ import annotations

from typing import Mapping, Sequence

from ..constraint_dsl import ConstraintDsl, UNRESOLVED_VALUE
from .worker_constraint_query import WorkerConstraintQuery
from .worker_score import WorkerId
from .worker_runtime import (
    CandidateId,
    DynamicAttributeQueryRegistry,
    DynamicAttributeReadResult,
    WorkerCandidateConstraint,
    WorkerCandidateMatch,
    WorkerDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntimeStatus,
)


class WorkerCandidateMatcher:
    """Assign each bounded worker to its first matching candidate constraint."""

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
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> Sequence[WorkerCandidateMatch]:
        self._validate_protocol_ids(worker_ids, candidate_constraints)
        if not candidate_constraints:
            return []

        dynamic_attributes = self._prepare_dynamic_attributes(candidate_constraints)
        if not worker_ids:
            return [(candidate_id, ()) for candidate_id, _ in candidate_constraints]

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
        matched_worker_ids: list[list[WorkerId]] = [
            [] for _ in candidate_constraints
        ]

        for worker_id in worker_ids:
            descriptor = descriptors.get(worker_id)
            if descriptor is None or descriptor.worker_group_id != worker_group_id:
                continue
            context = self._build_context(
                worker_id,
                descriptor,
                dynamic_attributes,
                dynamic_rows,
            )
            for candidate_index, (_, constraints) in enumerate(candidate_constraints):
                if ConstraintDsl.evaluate_match_rules(context, constraints.match_rules):
                    matched_worker_ids[candidate_index].append(worker_id)
                    break

        return [
            (candidate_id, tuple(matched_worker_ids[candidate_index]))
            for candidate_index, (candidate_id, _) in enumerate(candidate_constraints)
        ]

    def _prepare_dynamic_attributes(
        self,
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> tuple[str, ...]:
        required_attributes: dict[str, None] = {}
        missing_handlers: set[str] = set()
        for _, constraints in candidate_constraints:
            acquire_fields = set(constraints.acquire_fields)
            if any(not field.startswith("dynamic.") for field in acquire_fields):
                raise ValueError("worker matcher only acquires dynamic.* fields")
            if any(
                field.startswith("dynamic.") and field not in acquire_fields
                for field in constraints.match_rules
            ):
                raise ValueError(
                    "dynamic match fields must be declared in acquire_fields"
                )
            for field_name in constraints.acquire_fields:
                attribute_name = field_name.removeprefix("dynamic.")
                required_attributes.setdefault(attribute_name, None)
                if attribute_name not in self.query_dynamic_attributes_dict:
                    missing_handlers.add(attribute_name)

        if missing_handlers:
            raise ValueError(
                "missing dynamic attribute query handlers: "
                + ", ".join(sorted(missing_handlers))
            )
        return tuple(required_attributes)

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
    def _validate_protocol_ids(
        worker_ids: Sequence[WorkerId],
        candidate_constraints: Sequence[tuple[CandidateId, WorkerConstraintQuery]],
    ) -> None:
        if len(worker_ids) != len(set(worker_ids)):
            raise ValueError("worker ids must be unique within one match call")
        candidate_ids = [candidate_id for candidate_id, _ in candidate_constraints]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError("candidate ids must be unique within one match call")
