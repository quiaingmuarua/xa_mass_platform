from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Mapping, Sequence

from .worker_score import WorkerId


WorkerGroupId = str
EndpointManagerId = str
EventCode = str
AttributeValue = object


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
    """Worker capability-group declaration and query projection.

    One group names a coarse plugin/capability bucket and the scheduling
    namespace for Workers assigned to it. event_codes is a control-plane
    catalog summary and may lag the handlers currently installed on Workers.
    Kernel append, matching, and dispatch do not compare TaskItem event codes
    with this metadata. Server recommendation and worker-local handler
    resolution remain outside the scheduling hot path.
    """

    worker_group_id: WorkerGroupId
    attributes: Mapping[str, AttributeValue]
    event_codes: frozenset[EventCode]


@dataclass(frozen=True)
class WorkerDeclaration:
    """Worker-owned resource declaration used by Server Bind upsert.

    Supplying worker_group_id asserts that this logical execution slot conforms
    to the selected group's capability contract. The kernel protects the group
    identity and scheduling coordinates but does not re-prove handler coverage
    during each upsert or dispatch.

    endpoint_manager_id locates the physical endpoint owner after this Worker
    has been selected. It is not a matching field or live transport evidence.
    Runtime/package/handler compatibility versions belong in
    worker_properties.
    """

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    worker_properties: Mapping[str, AttributeValue]


@dataclass(frozen=True)
class WorkerDescriptor:
    """Complete worker-runtime resource metadata/query projection."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    worker_properties: Mapping[str, AttributeValue]
    platform_properties: Mapping[str, AttributeValue]


@dataclass(frozen=True)
class WorkerRuntimeResult:
    status: WorkerRuntimeStatus
    reason: str | None = None


class WorkerRuntime(ABC):
    """Worker runtime owner surface."""

    @abstractmethod
    def upsert_worker(
        self,
        *,
        declaration: WorkerDeclaration,
    ) -> WorkerRuntimeResult:
        """Upsert immutable identity and replace worker-owned properties."""
        pass


class WorkerResourceCatalog(ABC):
    """Worker-runtime resource declaration surface.

    It owns worker-group declarations, bounded descriptor reads, and
    low-frequency platform property patches. Worker upsert belongs to
    WorkerRuntime because first appearance must also establish the worker score.
    This catalog does not expose score mutation.
    """

    MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT = 100
    MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT = 100
    MAX_WORKER_GROUP_LOOKUP_LIMIT = 100

    @abstractmethod
    def register_worker_group(
        self,
        *,
        descriptor: WorkerGroupDescriptor,
    ) -> WorkerRuntimeResult:
        """Create one immutable WorkerGroup declaration.

        A byte-independent equivalent declaration is idempotent. An existing
        different declaration conflicts and is never replaced.
        """
        pass

    @abstractmethod
    def sample_worker_group_descriptors(
        self,
        *,
        sample_limit: int,
    ) -> Mapping[WorkerGroupId, WorkerGroupDescriptor | None]:
        """Read one unordered, incomplete WorkerGroup descriptor sample.

        The owner performs one random HASH sample. Results have no ordering,
        stability, pagination, total-count, or completeness guarantee. An
        unreadable or identity-mismatched row is represented by the sampled
        WorkerGroup id mapped to None.
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
    def get_worker_group_ids(
        self,
        *,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerGroupId | None]:
        """Resolve immutable Group owners for one bounded explicit Worker set."""
        pass

    @abstractmethod
    def sample_worker_descriptors(
        self,
        *,
        worker_group_id: WorkerGroupId,
        sample_limit: int,
    ) -> Mapping[WorkerId, WorkerDescriptor | None]:
        """Read one unordered, incomplete descriptor sample from one group.

        The owner performs one same-key random HASH sample. The caller owns the
        positive bound, up to MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT. Results have
        no ordering, stability, pagination, total-count, or completeness
        guarantee. An unreadable or identity-mismatched row is represented by
        the sampled Worker id mapped to None.
        """
        pass

    @abstractmethod
    def patch_worker_platform_properties(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        properties: Mapping[str, AttributeValue | None],
    ) -> WorkerRuntimeResult:
        """Patch platform-owned properties inside an explicit worker group."""
        pass
