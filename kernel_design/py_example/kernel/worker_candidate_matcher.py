from __future__ import annotations

from typing import Sequence

from .constraint_evaluator import ConstraintFieldResolution
from .worker_constraint_query import WorkerConstraintQuery
from .worker_score import WorkerId
from .worker_runtime import (
    AttributeName,
    CandidateId,
    DynamicAttributeQueryRegistry,
    WorkerCandidateConstraint,
    WorkerCandidateMatch,
    WorkerDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntimeStatus,
)


DynamicResolutionCache = dict[
    tuple[WorkerId, AttributeName],
    ConstraintFieldResolution,
]


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
        dynamic_cache: DynamicResolutionCache = {}

        matches: list[WorkerCandidateMatch] = []
        for candidate_id, constraints in candidate_constraints:
            worker_id_filter = constraints.worker_id_filter()
            matched_worker_ids = tuple(
                worker_id
                for worker_id in worker_ids
                if (worker_id_filter is None or worker_id in worker_id_filter)
                and self._matches_worker(
                    worker_id,
                    descriptors.get(worker_id),
                    constraints,
                    dynamic_cache,
                )
            )
            matches.append((candidate_id, matched_worker_ids))
        return matches

    def _matches_worker(
        self,
        worker_id: WorkerId,
        descriptor: WorkerDescriptor | None,
        constraints: WorkerConstraintQuery,
        dynamic_cache: DynamicResolutionCache,
    ) -> bool:
        if descriptor is None:
            return False

        return constraints.matches(
            lambda field_name: self._resolve_field_value(
                worker_id,
                descriptor,
                field_name,
                dynamic_cache,
            )
        )

    def _resolve_field_value(
        self,
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
        field_name: str,
        dynamic_cache: DynamicResolutionCache,
    ) -> ConstraintFieldResolution:
        if field_name == "worker.id":
            return ConstraintFieldResolution.present_value(worker_id)
        if field_name.startswith("system."):
            key = field_name.removeprefix("system.")
            if key not in descriptor.system_metadata:
                return ConstraintFieldResolution.missing()
            return ConstraintFieldResolution.present_value(descriptor.system_metadata[key])
        if field_name.startswith("static."):
            key = field_name.removeprefix("static.")
            if key not in descriptor.static_attributes:
                return ConstraintFieldResolution.missing()
            return ConstraintFieldResolution.present_value(descriptor.static_attributes[key])
        if field_name.startswith("dynamic."):
            attr_name = field_name.removeprefix("dynamic.")
            if attr_name not in descriptor.dynamic_attribute_names:
                return ConstraintFieldResolution.missing()

            cache_key = (worker_id, attr_name)
            cached = dynamic_cache.get(cache_key)
            if cached is not None:
                return cached

            query_fn = self.query_dynamic_attributes_dict.get(attr_name)
            if query_fn is None:
                resolution = ConstraintFieldResolution.unresolved()
            else:
                result = query_fn(worker_id)
                if result.status is WorkerRuntimeStatus.OK:
                    resolution = ConstraintFieldResolution.present_value(result.value)
                else:
                    resolution = ConstraintFieldResolution.unresolved()
            dynamic_cache[cache_key] = resolution
            return resolution
        return ConstraintFieldResolution.missing()

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
