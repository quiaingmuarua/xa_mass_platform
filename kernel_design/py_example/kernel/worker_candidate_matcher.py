from __future__ import annotations

from typing import Mapping, Sequence

from ..constraint_dsl import UNRESOLVED_VALUE, WorkerConstraintQuery, matches_mapping
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

    Descriptor predicates prune candidate-worker pairs before dynamic reads.
    Each declared dynamic attribute is then batch-read at most once for the
    workers still needed by those surviving pairs.
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

        self._require_dynamic_handlers(candidate_constraints)
        if not worker_ids:
            return [(candidate_id, ()) for candidate_id, _ in candidate_constraints]

        descriptor_rows = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=worker_ids,
        )
        system_fields, static_fields = self._collect_descriptor_fields(
            candidate_constraints
        )
        flat_values_by_worker = self._assemble_metadata_values(
            worker_group_id,
            worker_ids,
            descriptor_rows,
            system_fields,
            static_fields,
        )

        matched_worker_ids: list[list[WorkerId]] = [
            [] for _ in candidate_constraints
        ]
        pending_pairs: list[tuple[int, WorkerId]] = []
        dynamic_demand_by_attribute: dict[str, dict[WorkerId, None]] = {}

        for candidate_index, (_, constraints) in enumerate(candidate_constraints):
            for worker_id in worker_ids:
                descriptor = descriptor_rows.get(worker_id)
                flat_values = flat_values_by_worker.get(worker_id)
                if descriptor is None or flat_values is None:
                    continue
                if not matches_mapping(flat_values, constraints.metadata_rules):
                    continue
                if not self._supports_dynamic_fields(descriptor, constraints):
                    continue
                if not constraints.dynamic_rules:
                    matched_worker_ids[candidate_index].append(worker_id)
                    continue

                pending_pairs.append((candidate_index, worker_id))
                for field_name in constraints.acquire_fields:
                    attribute_name = constraints.dynamic_fields[field_name]
                    dynamic_demand_by_attribute.setdefault(attribute_name, {})[
                        worker_id
                    ] = None

        self._append_dynamic_batches(
            worker_group_id,
            dynamic_demand_by_attribute,
            flat_values_by_worker,
        )

        for candidate_index, worker_id in pending_pairs:
            constraints = candidate_constraints[candidate_index][1]
            if matches_mapping(
                flat_values_by_worker[worker_id],
                constraints.dynamic_rules,
            ):
                matched_worker_ids[candidate_index].append(worker_id)

        return [
            (candidate_id, tuple(matched_worker_ids[candidate_index]))
            for candidate_index, (candidate_id, _) in enumerate(candidate_constraints)
        ]

    def _require_dynamic_handlers(
        self,
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> None:
        missing_handlers = {
            constraints.dynamic_fields[field_name]
            for _, constraints in candidate_constraints
            for field_name in constraints.acquire_fields
            if constraints.dynamic_fields[field_name]
            not in self.query_dynamic_attributes_dict
        }
        if missing_handlers:
            raise ValueError(
                "missing dynamic attribute query handlers: "
                + ", ".join(sorted(missing_handlers))
            )

    def _append_dynamic_batches(
        self,
        worker_group_id: WorkerGroupId,
        dynamic_demand_by_attribute: Mapping[str, Mapping[WorkerId, None]],
        flat_values_by_worker: Mapping[WorkerId, dict[str, object]],
    ) -> None:
        for attribute_name, required_workers in dynamic_demand_by_attribute.items():
            field_name = f"dynamic.{attribute_name}"
            query_results = self.query_dynamic_attributes_dict[attribute_name](
                worker_group_id,
                tuple(required_workers),
            )
            for worker_id in required_workers:
                result = query_results.get(worker_id)
                flat_values_by_worker[worker_id][field_name] = (
                    result.value
                    if result is not None and result.status is WorkerRuntimeStatus.OK
                    else UNRESOLVED_VALUE
                )

    @staticmethod
    def _supports_dynamic_fields(
        descriptor: WorkerDescriptor,
        constraints: WorkerConstraintQuery,
    ) -> bool:
        return all(
            constraints.dynamic_fields[field_name]
            in descriptor.dynamic_attribute_names
            for field_name in constraints.acquire_fields
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

    @classmethod
    def _assemble_metadata_values(
        cls,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        descriptor_rows: Mapping[WorkerId, WorkerDescriptor | None],
        system_fields: Mapping[str, str],
        static_fields: Mapping[str, str],
    ) -> dict[WorkerId, dict[str, object]]:
        values_by_worker: dict[WorkerId, dict[str, object]] = {}
        for worker_id in worker_ids:
            descriptor = descriptor_rows.get(worker_id)
            if descriptor is None or descriptor.worker_group_id != worker_group_id:
                continue
            values_by_worker[worker_id] = cls._assemble_worker_metadata_values(
                worker_id,
                descriptor,
                system_fields,
                static_fields,
            )
        return values_by_worker

    @staticmethod
    def _assemble_worker_metadata_values(
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
