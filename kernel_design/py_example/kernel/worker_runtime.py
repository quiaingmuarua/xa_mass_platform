from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Mapping, Sequence

from .worker_score import TimeMillis, WorkerId


WorkerGroupId = str
EventCode = str
AttributeName = str
AttributeValue = object
DynamicAttributePayload = object
CandidateId = str
WorkerCandidateMatches = dict[CandidateId, list[WorkerId]]


@dataclass(frozen=True)
class WorkerCandidateConstraint:
    """One candidate's match rules and per-call worker allocation bound."""

    priority: int
    limit: int
    match_rules: Mapping[str, object]


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
    system_metadata: Mapping[str, AttributeValue]
    static_attributes: Mapping[str, AttributeValue]
    dynamic_attribute_names: frozenset[AttributeName]


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


class WorkerDynamicAttributeRuntime(ABC):
    """Worker-runtime dynamic attribute owner route.

    Dynamic attributes are a policy extension route backed by owner-local
    handlers. This surface accepts bounded point updates and bounded owner reads
    while hiding the internal function tables. It does not expose worker
    lifecycle truth or worker score lease mutation authority.
    """

    @abstractmethod
    def update_worker_dynamic_attributes(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        updates: Mapping[AttributeName, DynamicAttributePayload],
        observed_at_millis: TimeMillis,
    ) -> Mapping[AttributeName, WorkerRuntimeResult]:
        """Update accepted dynamic attributes through owner-local handlers.

        worker_group_id is the required logical resource locator; implementations
        may choose a different physical bucket internally. They must reject
        unknown attributes and attributes not listed in the worker descriptor's
        dynamic_attribute_names allowlist. Accepted updates are dispatched to
        the owner-local dynamic attribute function table. This method is not a
        query surface and must not rewrite worker score leases directly.
        """
        pass

    @abstractmethod
    def get_worker_dynamic_attribute_values(
        self,
        *,
        worker_group_id: WorkerGroupId,
        attribute_name: AttributeName,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, DynamicAttributeReadResult]:
        """Read one dynamic attribute for one bounded supported-worker batch.

        The caller must derive worker_ids from an already loaded descriptor
        batch. Implementations validate handler availability before reading and
        must not discover workers outside the supplied ids.
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
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerDescriptor | None]:
        """Read one bounded worker batch inside one explicit worker group."""
        pass

    @abstractmethod
    def update_worker_system_metadata(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        metadata: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        """Platform-owned metadata update inside an explicit worker group."""
        pass

    @abstractmethod
    def refresh_worker_static_attributes(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        attributes: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        """Refresh static attributes inside an explicit worker group."""
        pass
