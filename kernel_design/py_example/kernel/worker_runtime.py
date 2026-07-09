from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping, Sequence

from .worker_score import Score, TimeMillis, WorkerId


WorkerGroupId = str
EventCode = str
AttributeName = str
AttributeValue = object
DynamicAttributePayload = object
ReservationId = str


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
class WorkerDemand:
    """Narrow worker demand compiled from task/project policy.

    This is assignment-dispatch input to worker-runtime validation. It narrows
    candidates inside an already selected worker group and must not select a
    worker group by itself.
    """

    worker_group_id: WorkerGroupId
    event_code: EventCode
    target_worker_id: WorkerId | None = None
    required_system_attributes: Mapping[str, AttributeValue] = field(default_factory=dict)
    required_static_attributes: Mapping[str, AttributeValue] = field(default_factory=dict)
    required_dynamic_attributes: Mapping[str, AttributeValue] = field(default_factory=dict)


@dataclass(frozen=True)
class WorkerReservationHandle:
    """Opaque reservation handle returned by worker-runtime admission."""

    reservation_id: ReservationId


@dataclass(frozen=True)
class WorkerAdmission:
    """Binary reservation of one scheduler-visible worker resource."""

    handle: WorkerReservationHandle
    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    observed_worker_score: Score
    lease_expires_at_millis: TimeMillis


@dataclass(frozen=True)
class WorkerRuntimeResult:
    status: WorkerRuntimeStatus
    reason: str | None = None


@dataclass(frozen=True)
class WorkerMatchResult:
    status: WorkerRuntimeStatus
    worker_id: WorkerId | None = None
    reason: str | None = None


@dataclass(frozen=True)
class WorkerAdmissionResult:
    status: WorkerRuntimeStatus
    admission: WorkerAdmission | None = None
    reason: str | None = None


@dataclass(frozen=True)
class DynamicAttributeReadResult:
    status: WorkerRuntimeStatus
    value: AttributeValue | None = None
    observed_at_millis: TimeMillis | None = None
    reason: str | None = None


class DynamicAttributeHandler(ABC):
    """Owner-local handler for one dynamic attribute.

    A dynamic attribute handler owns validation and its query/index storage.
    WorkerDescriptor.dynamic_attributes only says whether a worker may send an
    update for this attribute.
    """

    @property
    @abstractmethod
    def attribute_name(self) -> AttributeName:
        pass

    @abstractmethod
    def update(
        self,
        *,
        worker_id: WorkerId,
        payload: DynamicAttributePayload,
        observed_at_millis: TimeMillis,
    ) -> WorkerRuntimeResult:
        pass

    @abstractmethod
    def read(
        self,
        *,
        worker_id: WorkerId,
    ) -> DynamicAttributeReadResult:
        pass


class WorkerResourceCatalog(ABC):
    """Worker-runtime metadata/query projection surface.

    This is the registration/connect/bootstrap-facing surface. It owns worker
    group descriptors, worker descriptors, and low-frequency metadata updates.
    It does not expose dynamic attribute values or admission reservations.
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


class WorkerDynamicAttributeRuntime(ABC):
    """Worker-runtime dynamic attribute surface.

    This is the high-frequency attribute update/read surface. Each dynamic
    attribute value lives behind its handler-owned storage/index. Descriptor
    dynamic_attributes only gates whether an update may be accepted.
    """

    @abstractmethod
    def register_dynamic_attribute_handler(
        self,
        *,
        handler: DynamicAttributeHandler,
    ) -> WorkerRuntimeResult:
        pass

    @abstractmethod
    def update_worker_dynamic_attribute(
        self,
        *,
        worker_id: WorkerId,
        attribute_name: AttributeName,
        payload: DynamicAttributePayload,
        observed_at_millis: TimeMillis,
    ) -> WorkerRuntimeResult:
        """Route a dynamic attribute update to its owner-local handler.

        Implementations must require attribute_name to be present in
        WorkerDescriptor.dynamic_attributes before calling the handler.
        """
        pass

    @abstractmethod
    def read_worker_dynamic_attribute(
        self,
        *,
        worker_id: WorkerId,
        attribute_name: AttributeName,
    ) -> DynamicAttributeReadResult:
        """Point-read a dynamic attribute owner value for validation/query use."""
        pass


class WorkerAdmissionRuntime(ABC):
    """Worker-runtime admission/reservation surface.

    Assignment-dispatch should depend on this narrow surface. It validates
    descriptor/dynamic evidence through worker-runtime and creates binary
    reservations for scheduler-visible worker resources.
    """

    @abstractmethod
    def validate_worker_match(
        self,
        *,
        worker_id: WorkerId,
        demand: WorkerDemand,
    ) -> WorkerMatchResult:
        """Validate descriptor-level matching inside the selected worker group."""
        pass

    @abstractmethod
    def admit_worker(
        self,
        *,
        worker_id: WorkerId,
        demand: WorkerDemand,
        observed_worker_score: Score,
        lease_expires_at_millis: TimeMillis,
    ) -> WorkerAdmissionResult:
        """Reserve one scheduler-visible worker resource.

        observed_worker_score is an opaque score fence captured from worker
        score acquire. Worker-runtime may store and compare it, but callers must
        not decode it through this interface.
        """
        pass

    @abstractmethod
    def revalidate_admission(
        self,
        *,
        handle: WorkerReservationHandle,
        observed_worker_score: Score,
    ) -> WorkerAdmissionResult:
        """Revalidate an existing reservation before work claim / dispatch."""
        pass

    @abstractmethod
    def release_admission(
        self,
        *,
        handle: WorkerReservationHandle,
        reason: str | None = None,
    ) -> WorkerRuntimeResult:
        """Release or compensate a binary worker reservation."""
        pass
