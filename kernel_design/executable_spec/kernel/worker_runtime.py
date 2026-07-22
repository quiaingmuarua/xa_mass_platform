from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping, Sequence

from .worker_score import TimeMillis, WorkerId


WorkerGroupId = str
EndpointManagerId = str
EventCode = str
AttributeName = str
AttributeValue = object
DynamicAttributePayload = object


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

    event_codes is a declared capability for external bootstrap and operator
    validation. Kernel append, matching, and dispatch do not enforce it;
    event-code resolution remains worker-local after assignment.
    """

    worker_group_id: WorkerGroupId
    attributes: Mapping[str, AttributeValue]
    event_codes: frozenset[EventCode]
    item_allocation_fields: frozenset[str] = field(default_factory=frozenset)


@dataclass(frozen=True)
class WorkerDeclaration:
    """Worker-owned resource declaration used by connect/reconnect upsert.

    endpoint_manager_id locates the physical endpoint owner after this Worker
    has been selected. It is not a matching field or live transport evidence.
    Runtime/package/handler compatibility versions belong in attributes.
    """

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    attributes: Mapping[str, AttributeValue]
    dynamic_attribute_names: frozenset[AttributeName]


@dataclass(frozen=True)
class WorkerDescriptor:
    """Complete worker-runtime resource metadata/query projection."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    attributes: Mapping[str, AttributeValue]
    platform_attributes: Mapping[str, AttributeValue]
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


class WorkerRuntime(ABC):
    """Worker runtime owner surface."""

    @abstractmethod
    def upsert_worker(
        self,
        *,
        declaration: WorkerDeclaration,
    ) -> WorkerRuntimeResult:
        """Create or reconnect one Worker without exposing score policy."""
        pass


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

    @abstractmethod
    def supports_candidate_query(
        self,
        *,
        attribute_name: AttributeName,
        operator_rule: Mapping[str, object],
    ) -> bool:
        """Return whether one dynamic field predicate has a bounded index."""
        pass

    @abstractmethod
    def query_candidate_worker_ids(
        self,
        *,
        worker_group_id: WorkerGroupId,
        attribute_name: AttributeName,
        operator_rule: Mapping[str, object],
        limit: int,
    ) -> tuple[WorkerId, ...]:
        """Query one handler-owned index for bounded candidate Worker ids."""
        pass


class WorkerResourceCatalog(ABC):
    """Worker-runtime resource declaration surface.

    It owns worker-group declarations, bounded descriptor reads, and
    low-frequency platform attribute updates. Worker upsert belongs to
    WorkerRuntime because first appearance must also establish the worker score.
    This catalog does not expose dynamic attribute values or score mutation.
    """

    @abstractmethod
    def upsert_worker_group(
        self,
        *,
        descriptor: WorkerGroupDescriptor,
    ) -> WorkerRuntimeResult:
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
    def update_worker_platform_attributes(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        attributes: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        """Merge platform-owned attributes inside an explicit worker group."""
        pass
