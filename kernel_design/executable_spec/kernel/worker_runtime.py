from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from types import MappingProxyType
from typing import Mapping, Sequence

from .worker_score import WorkerId


WorkerGroupId = str
EndpointManagerId = str
EventCode = str
AttributeName = str
AttributeValue = object
IndexedPropertyPayload = object


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


class WorkerPropertyIndex(ABC):
    """One configured property index implementation.

    The immutable assembly map owns the qualified index-field identity. Storage
    and value encoding stay behind this interface.
    """

    @abstractmethod
    def update(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        value: IndexedPropertyPayload | None,
    ) -> WorkerRuntimeResult:
        pass

    @abstractmethod
    def load(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, IndexedPropertyPayload]:
        """Load the current sparse projection for bounded Worker ids."""
        pass


class WorkerPropertyIndexRuntime(ABC):
    """Worker-runtime indexed-property owner route.

    Indexed properties are explicit last-applied scheduling projections. They
    are independent from descriptor property snapshots and do not expose
    Worker lifecycle, candidate discovery, or score mutation authority.
    """

    MAX_INDEXED_PROPERTY_READ_LIMIT = 100

    @abstractmethod
    def update_indexed_properties(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        updates: Mapping[AttributeName, IndexedPropertyPayload | None],
    ) -> Mapping[AttributeName, WorkerRuntimeResult]:
        """Update qualified ``index.*`` projections independently by field."""
        pass

    @abstractmethod
    def load_indexed_property_values(
        self,
        *,
        worker_group_id: WorkerGroupId,
        index_field: str,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, IndexedPropertyPayload]:
        """Route one bounded point read to its configured projection."""
        pass


class WorkerResourceCatalog(ABC):
    """Worker-runtime resource declaration surface.

    It owns worker-group declarations, bounded descriptor reads, and
    low-frequency platform property patches. Worker upsert belongs to
    WorkerRuntime because first appearance must also establish the worker score.
    This catalog does not expose indexed property values or score mutation.
    """

    MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT = 100
    MAX_WORKER_GROUP_LOOKUP_LIMIT = 100

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


class MappedWorkerPropertyIndexRuntime(WorkerPropertyIndexRuntime):
    """Route property-index owner calls to immutable per-field implementations."""

    def __init__(
        self,
        catalog: WorkerResourceCatalog,
        indexes: Mapping[str, WorkerPropertyIndex],
    ) -> None:
        if not isinstance(catalog, WorkerResourceCatalog):
            raise TypeError("catalog must be WorkerResourceCatalog")
        indexes_by_field: dict[str, WorkerPropertyIndex] = {}
        if not isinstance(indexes, Mapping):
            raise TypeError("indexes must be a mapping")
        for index_field, index in indexes.items():
            if not _valid_index_field(index_field):
                raise ValueError("property index fields must use index.*")
            if not isinstance(index, WorkerPropertyIndex):
                raise TypeError("indexes must contain WorkerPropertyIndex values")
            indexes_by_field[index_field] = index
        self.catalog = catalog
        self._indexes_by_field = MappingProxyType(indexes_by_field)

    def update_indexed_properties(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        updates: Mapping[AttributeName, IndexedPropertyPayload | None],
    ) -> Mapping[AttributeName, WorkerRuntimeResult]:
        if not isinstance(updates, Mapping):
            raise TypeError("updates must be a mapping")
        if not updates:
            return {}
        if not _valid_id(worker_group_id):
            return _uniform_results(
                updates,
                WorkerRuntimeStatus.INVALID,
                "invalid workerGroupId",
            )
        if not _valid_id(worker_id):
            return _uniform_results(
                updates,
                WorkerRuntimeStatus.INVALID,
                "invalid workerId",
            )
        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=[worker_id],
        ).get(worker_id)
        if descriptor is None:
            return _uniform_results(
                updates,
                WorkerRuntimeStatus.NOT_FOUND,
                "worker not found",
            )

        results: dict[AttributeName, WorkerRuntimeResult] = {}
        for index_field, value in updates.items():
            if not _valid_index_field(index_field):
                results[index_field] = WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "property index fields must use index.*",
                )
                continue
            index = self._indexes_by_field.get(index_field)
            if index is None:
                results[index_field] = WorkerRuntimeResult(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "property index is not configured",
                )
                continue
            try:
                result = index.update(
                    worker_group_id=worker_group_id,
                    worker_id=worker_id,
                    value=value,
                )
            except Exception:
                result = WorkerRuntimeResult(
                    WorkerRuntimeStatus.STALE,
                    "property index provider failed",
                )
            if not isinstance(result, WorkerRuntimeResult):
                result = WorkerRuntimeResult(
                    WorkerRuntimeStatus.STALE,
                    "property index provider returned an invalid result",
                )
            results[index_field] = result
        return results

    def load_indexed_property_values(
        self,
        *,
        worker_group_id: WorkerGroupId,
        index_field: str,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, IndexedPropertyPayload]:
        if not _valid_id(worker_group_id):
            raise ValueError("workerGroupId must be non-empty")
        if not _valid_index_field(index_field):
            raise ValueError("invalid indexed property field")
        if isinstance(worker_ids, (str, bytes)) or not isinstance(
            worker_ids,
            Sequence,
        ):
            raise TypeError("workerIds must be a sequence")
        bounded_worker_ids = tuple(dict.fromkeys(worker_ids))
        if not bounded_worker_ids or len(bounded_worker_ids) > (
            self.MAX_INDEXED_PROPERTY_READ_LIMIT
        ):
            raise ValueError("indexed property read must contain 1..100 Workers")
        if any(not _valid_id(worker_id) for worker_id in bounded_worker_ids):
            raise ValueError("Worker ids must be non-empty")

        index = self._indexes_by_field.get(index_field)
        if index is None:
            raise LookupError("property index is not configured")

        loaded = index.load(
            worker_group_id=worker_group_id,
            worker_ids=bounded_worker_ids,
        )
        if not isinstance(loaded, Mapping):
            raise RuntimeError("property index returned an invalid projection")
        values = dict(loaded)
        if any(
            not _valid_id(worker_id)
            or worker_id not in bounded_worker_ids
            or value is None
            for worker_id, value in values.items()
        ):
            raise RuntimeError("property index returned an invalid projection")
        return MappingProxyType(values)

def _valid_id(value: object) -> bool:
    return isinstance(value, str) and bool(value)


def _valid_index_field(value: object) -> bool:
    return isinstance(value, str) and value.startswith("index.") and len(value) > 6


def _uniform_results(
    updates: Mapping[AttributeName, object],
    status: WorkerRuntimeStatus,
    reason: str,
) -> dict[AttributeName, WorkerRuntimeResult]:
    return {
        property_name: WorkerRuntimeResult(status, reason)
        for property_name in updates
    }
