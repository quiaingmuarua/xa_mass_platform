from __future__ import annotations

from heapq import merge
from typing import Mapping, Sequence

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

    One call performs one descriptor batch read and at most one dynamic-owner
    batch read per required dynamic attribute. It does not discover workers,
    rank them, carry observed scores, or create score leases.
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
        """Match one bounded worker batch against ordered candidate constraints."""

        self._validate_worker_ids(worker_ids)
        self._validate_candidate_ids(candidate_constraints)
        if not candidate_constraints:
            return []
        if not worker_ids:
            return [(candidate_id, ()) for candidate_id, _ in candidate_constraints]

        descriptor_rows = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=worker_ids,
        )
        system_fields, static_fields = self._collect_descriptor_fields(
            candidate_constraints
        )
        unconstrained_indices, constrained_indices_by_worker = (
            self._index_candidate_constraints(worker_ids, candidate_constraints)
        )

        flat_values_by_worker: dict[WorkerId, dict[str, object]] = {}
        metadata_matches_by_worker: dict[WorkerId, tuple[int, ...]] = {}
        dynamic_worker_ids: dict[str, list[WorkerId]] = {}

        for worker_id in worker_ids:
            descriptor = descriptor_rows.get(worker_id)
            if descriptor is None or descriptor.worker_group_id != worker_group_id:
                continue

            explicit_indices = constrained_indices_by_worker.get(worker_id, ())
            if not unconstrained_indices and not explicit_indices:
                continue
            applicable_indices = (
                merge(unconstrained_indices, explicit_indices)
                if explicit_indices
                else iter(unconstrained_indices)
            )
            flat_values = self._assemble_non_dynamic_flat_values(
                worker_id,
                descriptor,
                system_fields,
                static_fields,
            )
            metadata_matches = tuple(
                candidate_index
                for candidate_index in applicable_indices
                if matches_mapping(
                    flat_values,
                    candidate_constraints[candidate_index][1].non_dynamic_predicates,
                )
            )
            if not metadata_matches:
                continue

            flat_values_by_worker[worker_id] = flat_values
            metadata_matches_by_worker[worker_id] = metadata_matches
            required_dynamic_fields: dict[str, str] = {}
            for candidate_index in metadata_matches:
                required_dynamic_fields.update(
                    candidate_constraints[candidate_index][1].dynamic_fields
                )
            for attribute_name in required_dynamic_fields.values():
                if attribute_name in descriptor.dynamic_attribute_names:
                    dynamic_worker_ids.setdefault(attribute_name, []).append(worker_id)

        self._append_dynamic_batches(
            worker_group_id,
            dynamic_worker_ids,
            flat_values_by_worker,
        )

        matched_worker_ids: list[list[WorkerId]] = [
            [] for _ in candidate_constraints
        ]
        for worker_id in worker_ids:
            metadata_matches = metadata_matches_by_worker.get(worker_id)
            if metadata_matches is None:
                continue
            flat_values = flat_values_by_worker[worker_id]
            for candidate_index in metadata_matches:
                constraints = candidate_constraints[candidate_index][1]
                if matches_mapping(flat_values, constraints.dynamic_predicates):
                    matched_worker_ids[candidate_index].append(worker_id)

        return [
            (candidate_id, tuple(matched_worker_ids[candidate_index]))
            for candidate_index, (candidate_id, _) in enumerate(candidate_constraints)
        ]

    def _append_dynamic_batches(
        self,
        worker_group_id: WorkerGroupId,
        dynamic_worker_ids: Mapping[str, Sequence[WorkerId]],
        flat_values_by_worker: Mapping[WorkerId, dict[str, object]],
    ) -> None:
        for attribute_name, required_worker_ids in dynamic_worker_ids.items():
            field_name = f"dynamic.{attribute_name}"
            query_fn = self.query_dynamic_attributes_dict.get(attribute_name)
            if query_fn is None:
                for worker_id in required_worker_ids:
                    flat_values_by_worker[worker_id][field_name] = UNRESOLVED_VALUE
                continue

            query_results = query_fn(worker_group_id, tuple(required_worker_ids))
            for worker_id in required_worker_ids:
                result = query_results.get(worker_id)
                flat_values_by_worker[worker_id][field_name] = (
                    result.value
                    if result is not None and result.status is WorkerRuntimeStatus.OK
                    else UNRESOLVED_VALUE
                )

    @staticmethod
    def _index_candidate_constraints(
        worker_ids: Sequence[WorkerId],
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> tuple[tuple[int, ...], Mapping[WorkerId, tuple[int, ...]]]:
        requested_worker_ids = set(worker_ids)
        unconstrained_indices: list[int] = []
        constrained_indices: dict[WorkerId, list[int]] = {}
        for candidate_index, (_, constraints) in enumerate(candidate_constraints):
            worker_id_filter = constraints.worker_id_filter()
            if worker_id_filter is None:
                unconstrained_indices.append(candidate_index)
                continue
            for worker_id in worker_id_filter:
                if worker_id in requested_worker_ids:
                    constrained_indices.setdefault(worker_id, []).append(candidate_index)
        return (
            tuple(unconstrained_indices),
            {
                worker_id: tuple(candidate_indices)
                for worker_id, candidate_indices in constrained_indices.items()
            },
        )

    @staticmethod
    def _collect_descriptor_fields(
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> tuple[Mapping[str, str], Mapping[str, str]]:
        system_fields: dict[str, str] = {}
        static_fields: dict[str, str] = {}
        for _, constraints in candidate_constraints:
            system_fields.update(constraints.system_fields)
            static_fields.update(constraints.static_fields)
        return system_fields, static_fields

    @staticmethod
    def _assemble_non_dynamic_flat_values(
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
        system_fields: Mapping[str, str],
        static_fields: Mapping[str, str],
    ) -> dict[str, object]:
        flat_values: dict[str, object] = {"workerId": worker_id}
        for field_name, attribute_name in system_fields.items():
            if attribute_name in descriptor.system_metadata:
                flat_values[field_name] = descriptor.system_metadata[attribute_name]
        for field_name, attribute_name in static_fields.items():
            if attribute_name in descriptor.static_attributes:
                flat_values[field_name] = descriptor.static_attributes[attribute_name]
        return flat_values

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
