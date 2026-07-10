from __future__ import annotations

from typing import Sequence

from ..constraint_dsl import (
    UNRESOLVED_VALUE,
    WorkerConstraintQuery,
    matches_mapping,
)
from .worker_score import WorkerId
from .worker_runtime import (
    CandidateId,
    DynamicAttributeQueryRegistry,
    WorkerCandidateConstraint,
    WorkerCandidateMatch,
    WorkerDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntimeStatus,
)


class WorkerCandidateMatcher:
    """Storage-independent bounded worker candidate matcher.

    Assignment-dispatch supplies one selected worker group, a bounded worker-id
    batch, and one constraint query per dispatch candidate. Input order carries
    candidate and worker priority; this mechanism only applies hard constraints.
    It does not discover workers, rank them, carry observed scores, or create
    score leases. Future matching variants should be selected explicitly by an
    owner-defined mode instead of storage-specific subclasses.
    """

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
        """Match bounded workers against an ordered constraint batch."""

        self._validate_worker_ids(worker_ids)
        self._validate_candidate_ids(candidate_constraints)
        if not candidate_constraints:
            return []

        descriptor_rows = self.catalog.get_worker_descriptors(worker_ids=worker_ids)
        descriptors = {
            worker_id: descriptor
            for worker_id, descriptor in descriptor_rows.items()
            if descriptor is not None and descriptor.worker_group_id == worker_group_id
        }
        matched_worker_ids: dict[CandidateId, list[WorkerId]] = {
            candidate_id: [] for candidate_id, _ in candidate_constraints
        }
        for worker_id in worker_ids:
            descriptor = descriptors.get(worker_id)
            if descriptor is None:
                continue

            applicable_constraints = [
                (candidate_id, constraints)
                for candidate_id, constraints in candidate_constraints
                if self._worker_id_matches(worker_id, constraints)
            ]
            if not applicable_constraints:
                continue

            flat_values = self._assemble_non_dynamic_flat_values(
                worker_id,
                descriptor,
                applicable_constraints,
            )
            metadata_matches = [
                (candidate_id, constraints)
                for candidate_id, constraints in applicable_constraints
                if matches_mapping(flat_values, constraints.non_dynamic_predicates)
            ]
            if not metadata_matches:
                continue

            self._append_dynamic_flat_values(
                worker_id,
                descriptor,
                metadata_matches,
                flat_values,
            )
            for candidate_id, constraints in metadata_matches:
                if matches_mapping(flat_values, constraints.dynamic_predicates):
                    matched_worker_ids[candidate_id].append(worker_id)

        return [
            (candidate_id, tuple(matched_worker_ids[candidate_id]))
            for candidate_id, _ in candidate_constraints
        ]

    @staticmethod
    def _worker_id_matches(
        worker_id: WorkerId,
        constraints: WorkerConstraintQuery,
    ) -> bool:
        worker_id_filter = constraints.worker_id_filter()
        return worker_id_filter is None or worker_id in worker_id_filter

    @staticmethod
    def _assemble_non_dynamic_flat_values(
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
        applicable_constraints: Sequence[WorkerCandidateConstraint],
    ) -> dict[str, object]:
        system_fields: dict[str, str] = {}
        static_fields: dict[str, str] = {}
        for _, constraints in applicable_constraints:
            system_fields.update(constraints.system_fields)
            static_fields.update(constraints.static_fields)

        flat_values: dict[str, object] = {"workerId": worker_id}
        for field_name, attribute_name in system_fields.items():
            if attribute_name in descriptor.system_metadata:
                flat_values[field_name] = descriptor.system_metadata[attribute_name]
        for field_name, attribute_name in static_fields.items():
            if attribute_name in descriptor.static_attributes:
                flat_values[field_name] = descriptor.static_attributes[attribute_name]
        return flat_values

    def _append_dynamic_flat_values(
        self,
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
        applicable_constraints: Sequence[WorkerCandidateConstraint],
        flat_values: dict[str, object],
    ) -> None:
        dynamic_fields: dict[str, str] = {}
        for _, constraints in applicable_constraints:
            dynamic_fields.update(constraints.dynamic_fields)

        for field_name, attribute_name in dynamic_fields.items():
            if attribute_name not in descriptor.dynamic_attribute_names:
                continue
            query_fn = self.query_dynamic_attributes_dict.get(attribute_name)
            if query_fn is None:
                flat_values[field_name] = UNRESOLVED_VALUE
                continue
            result = query_fn(worker_id)
            flat_values[field_name] = (
                result.value
                if result.status is WorkerRuntimeStatus.OK
                else UNRESOLVED_VALUE
            )

    @staticmethod
    def _validate_worker_ids(worker_ids: Sequence[WorkerId]) -> None:
        if len(worker_ids) != len(set(worker_ids)):
            raise ValueError("worker ids must be unique within one match call")

    @staticmethod
    def _validate_candidate_ids(
        candidate_constraints: Sequence[tuple[CandidateId, WorkerConstraintQuery]],
    ) -> None:
        candidate_ids = [candidate_id for candidate_id, _ in candidate_constraints]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError("candidate ids must be unique within one match call")
