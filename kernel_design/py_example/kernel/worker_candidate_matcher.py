from __future__ import annotations

from typing import Mapping, Sequence

from ..constraint_dsl import (
    ConstraintDsl,
    UNRESOLVED_VALUE,
)
from .worker_constraint_query import WorkerConstraintQuery
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

        self._validate_dynamic_acquire_fields(candidate_constraints)
        self._require_dynamic_handlers(candidate_constraints)
        if not worker_ids:
            return [(candidate_id, ()) for candidate_id, _ in candidate_constraints]

        descriptor_rows = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=worker_ids,
        )
        catalog_rules_by_candidate = [
            {
                field_name: operator_map
                for field_name, operator_map in constraints.match_rules.items()
                if field_name not in constraints.acquire_fields
            }
            for _, constraints in candidate_constraints
        ]
        dynamic_rules_by_candidate = [
            {
                field_name: constraints.match_rules[field_name]
                for field_name in constraints.acquire_fields
            }
            for _, constraints in candidate_constraints
        ]
        contexts_by_worker, dynamic_values_by_worker = self._assemble_contexts(
            worker_group_id,
            worker_ids,
            descriptor_rows,
        )

        matched_worker_ids: list[list[WorkerId]] = [
            [] for _ in candidate_constraints
        ]
        pending_pairs: list[tuple[int, WorkerId]] = []
        dynamic_demand_by_attribute: dict[str, dict[WorkerId, None]] = {}

        for candidate_index, (_, constraints) in enumerate(candidate_constraints):
            for worker_id in worker_ids:
                descriptor = descriptor_rows.get(worker_id)
                context = contexts_by_worker.get(worker_id)
                if descriptor is None or context is None:
                    continue
                if not ConstraintDsl.evaluate_match_rules(
                    context,
                    catalog_rules_by_candidate[candidate_index],
                ):
                    continue
                if not self._supports_dynamic_fields(descriptor, constraints):
                    continue
                if not constraints.acquire_fields:
                    matched_worker_ids[candidate_index].append(worker_id)
                    continue

                pending_pairs.append((candidate_index, worker_id))
                for field_name in constraints.acquire_fields:
                    attribute_name = field_name.removeprefix("dynamic.")
                    dynamic_demand_by_attribute.setdefault(attribute_name, {})[
                        worker_id
                    ] = None

        self._append_dynamic_batches(
            worker_group_id,
            dynamic_demand_by_attribute,
            dynamic_values_by_worker,
        )

        for candidate_index, worker_id in pending_pairs:
            constraints = candidate_constraints[candidate_index][1]
            if ConstraintDsl.evaluate_match_rules(
                contexts_by_worker[worker_id],
                dynamic_rules_by_candidate[candidate_index],
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
            field_name.removeprefix("dynamic.")
            for _, constraints in candidate_constraints
            for field_name in constraints.acquire_fields
            if field_name.removeprefix("dynamic.")
            not in self.query_dynamic_attributes_dict
        }
        if missing_handlers:
            raise ValueError(
                "missing dynamic attribute query handlers: "
                + ", ".join(sorted(missing_handlers))
            )

    @staticmethod
    def _validate_dynamic_acquire_fields(
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> None:
        for _, constraints in candidate_constraints:
            acquire_fields = set(constraints.acquire_fields)
            if any(
                not field_name.startswith("dynamic.")
                for field_name in acquire_fields
            ):
                raise ValueError(
                    "worker matcher only acquires dynamic.* fields"
                )
            if any(
                field_name.startswith("dynamic.")
                and field_name not in acquire_fields
                for field_name in constraints.match_rules
            ):
                raise ValueError(
                    "dynamic match fields must be declared in acquire_fields"
                )

    def _append_dynamic_batches(
        self,
        worker_group_id: WorkerGroupId,
        dynamic_demand_by_attribute: Mapping[str, Mapping[WorkerId, None]],
        dynamic_values_by_worker: Mapping[WorkerId, dict[str, object]],
    ) -> None:
        for attribute_name, required_workers in dynamic_demand_by_attribute.items():
            query_results = self.query_dynamic_attributes_dict[attribute_name](
                worker_group_id,
                tuple(required_workers),
            )
            for worker_id in required_workers:
                result = query_results.get(worker_id)
                dynamic_values_by_worker[worker_id][attribute_name] = (
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
            field_name.removeprefix("dynamic.") in descriptor.dynamic_attribute_names
            for field_name in constraints.acquire_fields
        )

    @staticmethod
    def _assemble_contexts(
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        descriptor_rows: Mapping[WorkerId, WorkerDescriptor | None],
    ) -> tuple[
        dict[WorkerId, dict[str, object]],
        dict[WorkerId, dict[str, object]],
    ]:
        contexts_by_worker: dict[WorkerId, dict[str, object]] = {}
        dynamic_values_by_worker: dict[WorkerId, dict[str, object]] = {}
        for worker_id in worker_ids:
            descriptor = descriptor_rows.get(worker_id)
            if descriptor is None or descriptor.worker_group_id != worker_group_id:
                continue
            dynamic_values: dict[str, object] = {}
            contexts_by_worker[worker_id] = {
                "workerId": worker_id,
                "system": descriptor.system_metadata,
                "static": descriptor.static_attributes,
                "dynamic": dynamic_values,
            }
            dynamic_values_by_worker[worker_id] = dynamic_values
        return contexts_by_worker, dynamic_values_by_worker

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
