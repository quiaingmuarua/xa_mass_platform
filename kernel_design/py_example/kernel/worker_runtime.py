from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Callable, Mapping, Sequence

from .worker_constraint_query import WorkerConstraintQuery
from .worker_score import TimeMillis, WorkerId


WorkerGroupId = str
EventCode = str
AttributeName = str
AttributeValue = object
DynamicAttributePayload = object
CandidateId = str
WorkerCandidateConstraint = tuple[CandidateId, WorkerConstraintQuery]
WorkerCandidateMatch = tuple[CandidateId, tuple[WorkerId, ...]]


class WorkerRuntimeStatus(Enum):
    """Generic status for worker-runtime owner operations."""

    OK = "ok"
    NOOP = "noop"
    REJECTED = "rejected"
    NOT_FOUND = "not_found"
    STALE = "stale"
    CONFLICT = "conflict"
    INVALID = "invalid"


@dataclass(frozen=True)
class WorkerGroupDescriptor:
    """Worker group metadata/query projection.

    event_codes is the group promise. It validates item event families after a
    task has already selected a worker group; it is not a worker-group discovery
    mechanism.
    """

    worker_group_id: WorkerGroupId
    attributes: Mapping[str, AttributeValue]
    event_codes: frozenset[EventCode]


@dataclass(frozen=True)
class WorkerDescriptor:
    """Worker resource metadata/query projection.

    First-layer fields stop at identity, group identity, and attribute buckets.
    Runtime/package/handler compatibility versions belong in static_attributes.
    """

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    system_attributes: Mapping[str, AttributeValue]
    static_attributes: Mapping[str, AttributeValue]
    dynamic_attributes: frozenset[AttributeName]


@dataclass(frozen=True)
class WorkerRuntimeResult:
    status: WorkerRuntimeStatus
    reason: str | None = None


@dataclass(frozen=True)
class DynamicAttributeReadResult:
    status: WorkerRuntimeStatus
    value: AttributeValue | None = None
    observed_at_millis: TimeMillis | None = None
    reason: str | None = None


DynamicAttributeUpdateFn = Callable[
    [WorkerId, DynamicAttributePayload, TimeMillis],
    WorkerRuntimeResult,
]
DynamicAttributeQueryFn = Callable[
    [WorkerId],
    DynamicAttributeReadResult,
]
DynamicAttributeUpdateRegistry = Mapping[AttributeName, DynamicAttributeUpdateFn]
DynamicAttributeQueryRegistry = Mapping[AttributeName, DynamicAttributeQueryFn]

# Dynamic attribute registries are worker-runtime internal function tables.
# They are not public ports and are not externally registered plugin surfaces.


class WorkerDynamicAttributeRuntime(ABC):
    """Worker-runtime dynamic attribute update route.

    Dynamic attributes are a policy extension route backed by owner-local
    handlers. This surface accepts bounded point updates and dispatches them to
    the internal function table. It does not expose dynamic attribute query
    values, worker lifecycle truth, or worker score lease mutation authority.
    """

    @abstractmethod
    def update_worker_dynamic_attributes(
        self,
        *,
        worker_id: WorkerId,
        updates: Mapping[AttributeName, DynamicAttributePayload],
        observed_at_millis: TimeMillis,
    ) -> Mapping[AttributeName, WorkerRuntimeResult]:
        """Update accepted dynamic attributes through owner-local handlers.

        Implementations must reject unknown attributes and attributes not listed
        in the worker descriptor's dynamic_attributes allowlist. Accepted
        updates are dispatched to the owner-local dynamic attribute function
        table. This method is not a query surface and must not rewrite worker
        score leases directly.
        """
        pass


class WorkerResourceCatalog(ABC):
    """Worker-runtime resource declaration surface.

    This is the registration/connect/bootstrap-facing surface. It owns worker
    group descriptors, worker descriptors, and low-frequency metadata updates.
    It does not expose dynamic attribute values or worker score leases.
    """

    @abstractmethod
    def register_worker_group_descriptor(
        self,
        *,
        descriptor: WorkerGroupDescriptor,
    ) -> WorkerRuntimeResult:
        pass

    @abstractmethod
    def register_worker_descriptor(
        self,
        *,
        descriptor: WorkerDescriptor,
    ) -> WorkerRuntimeResult:
        """Register or replace a worker descriptor after platform validation.

        Implementations must validate the worker group exists and the worker can
        satisfy the worker group's event-code promise. Version compatibility is
        represented through static_attributes, not a top-level field.
        """
        pass

    @abstractmethod
    def get_worker_group_descriptors(
        self,
        *,
        worker_group_ids: Sequence[WorkerGroupId],
    ) -> Mapping[WorkerGroupId, WorkerGroupDescriptor | None]:
        pass

    @abstractmethod
    def get_worker_descriptors(
        self,
        *,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerDescriptor | None]:
        pass

    @abstractmethod
    def update_worker_system_attributes(
        self,
        *,
        worker_id: WorkerId,
        attributes: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        """Platform-owned low-frequency metadata update."""
        pass

    @abstractmethod
    def refresh_worker_static_attributes(
        self,
        *,
        worker_id: WorkerId,
        attributes: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        """Worker register/connect metadata refresh after platform validation."""
        pass


class WorkerCandidateMatcher(ABC):
    """Worker-runtime bounded batch matching surface.

    Assignment-dispatch supplies one selected worker group, a bounded worker id
    batch, and one constraint query per dispatch candidate. Candidate priority
    is represented by input order. The matcher returns one match row for every
    input candidate in that same order. It must not discover workers outside the
    supplied worker_ids, rank workers, carry observed worker scores, or create
    score leases / holds.
    """

    @abstractmethod
    def match_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
        candidate_constraints: Sequence[WorkerCandidateConstraint],
    ) -> Sequence[WorkerCandidateMatch]:
        """Match bounded workers against a batch of candidate constraints.

        One call handles one selected worker group only; callers must partition
        candidates by worker_group_id before calling this method. Candidate ids
        must be unique within one call. The result must contain exactly one row
        for every input candidate; an empty matched-worker tuple means no match.
        Returned worker ids must be a subset of worker_ids and should preserve
        worker_ids order unless the worker-runtime matcher owns a named
        non-ranking pruning rule. Candidate ids are opaque caller-owned
        correlation ids; worker-runtime must not interpret them as task
        lifecycle truth.
        """
        pass
