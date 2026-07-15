from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Mapping, Sequence

from .task_score_band import TaskId, TimeMillis
from .worker_runtime import EndpointManagerId, WorkerGroupId
from .worker_score import Score as WorkerScore
from .worker_score import WorkerId


@dataclass(frozen=True)
class CandidateWorkerEntry:
    """One Task-local Worker candidate with opaque Worker-score evidence."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    worker_lease_score: WorkerScore


@dataclass(frozen=True)
class DeliverSeed:
    """Opaque already-assigned handoff consumed by one endpoint manager."""

    worker_id: WorkerId
    opaque_delivery_item: str
    opaque_result_context: str
    task_item_claim_until_millis: TimeMillis

    def __post_init__(self) -> None:
        if not isinstance(self.worker_id, str) or not self.worker_id:
            raise ValueError("worker id must be non-empty")
        if (
            not isinstance(self.opaque_delivery_item, str)
            or not self.opaque_delivery_item
        ):
            raise ValueError("opaque delivery item must be non-empty")
        if (
            not isinstance(self.opaque_result_context, str)
            or not self.opaque_result_context
        ):
            raise ValueError("opaque result context must be non-empty")
        if (
            isinstance(self.task_item_claim_until_millis, bool)
            or not isinstance(self.task_item_claim_until_millis, int)
            or self.task_item_claim_until_millis <= 0
        ):
            raise ValueError("TaskItem claim deadline must be positive")


class AssignmentDispatchRuntime(ABC):
    """Runtime owner for Task-local candidate Worker handoff."""

    @abstractmethod
    def append_candidate_workers(
        self,
        *,
        task_id: TaskId,
        candidate_workers: Sequence[CandidateWorkerEntry],
        expires_at_millis: TimeMillis,
    ) -> None:
        """Append one expiring candidate batch to a Task-local collection."""
        pass

    @abstractmethod
    def candidate_worker_counts(
        self,
        *,
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, int]:
        """Return current non-expired candidate counts for a bounded Task batch."""
        pass

    @abstractmethod
    def consume_candidate_workers(
        self,
        *,
        task_id: TaskId,
        limit: int,
    ) -> tuple[CandidateWorkerEntry, ...]:
        """Atomically consume up to limit entries from one Task collection."""
        pass


class DeliverSeedRuntime(ABC):
    """Runtime owner for endpoint-manager-partitioned DeliverSeed queues."""

    @abstractmethod
    def append_deliver_seeds(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        deliver_seeds: Sequence[DeliverSeed],
    ) -> None:
        """Append one bounded batch to exactly one endpoint-manager queue."""
        pass

    @abstractmethod
    def consume_deliver_seeds(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        limit: int,
    ) -> tuple[DeliverSeed, ...]:
        """Atomically consume a bounded batch from one endpoint-manager queue."""
        pass
