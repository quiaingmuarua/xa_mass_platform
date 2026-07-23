from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Mapping, Sequence

from .task_score_band import TaskId, TimeMillis
from .worker_runtime import WorkerGroupId
from .worker_score import Score as WorkerScore
from .worker_score import WorkerId


CandidateId = str


@dataclass(frozen=True)
class CandidateWorkerEntry:
    """One Task-local Worker candidate with opaque Worker-score evidence."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    worker_lease_score: WorkerScore


@dataclass(frozen=True)
class DeliverSeed:
    """Opaque already-assigned handoff stored in one Worker mailbox."""

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


class DeliverSeedAppendStatus(Enum):
    """Per-Worker outcome of one mailbox append."""

    APPENDED = "APPENDED"
    OCCUPIED = "OCCUPIED"


class CandidateWorkerCache(ABC):
    """Best-effort cache for expiring Worker candidate evidence."""

    @abstractmethod
    def append_candidate_workers(
        self,
        *,
        candidate_id: CandidateId,
        candidate_workers: Sequence[CandidateWorkerEntry],
        expires_at_millis: TimeMillis,
    ) -> None:
        """Append one expiring batch to a candidate-local collection."""
        pass

    @abstractmethod
    def candidate_worker_counts(
        self,
        *,
        candidate_ids: Sequence[CandidateId],
    ) -> Mapping[CandidateId, int]:
        """Return non-expired counts for a bounded candidate-id batch."""
        pass

    @abstractmethod
    def consume_candidate_workers(
        self,
        *,
        candidate_id: CandidateId,
        limit: int,
    ) -> tuple[CandidateWorkerEntry, ...]:
        """Atomically consume up to limit entries from one candidate collection."""
        pass


class CandidateWarmupSchedule(ABC):
    """Best-effort schedule for rebuilding derived candidate evidence."""

    @abstractmethod
    def schedule_candidate_warmups(
        self,
        *,
        task_ids: Sequence[TaskId],
        due_time_millis: TimeMillis,
    ) -> None:
        """Schedule bounded Task candidate warmups at an absolute time."""
        pass

    @abstractmethod
    def consume_due_candidate_warmups(
        self,
        *,
        before_time_millis: TimeMillis,
        limit: int,
    ) -> tuple[TaskId, ...]:
        """Consume a bounded due Task-id batch in due-time order."""
        pass


class DeliverSeedRuntime(ABC):
    """Runtime owner for Worker-addressed DeliverSeed mailboxes."""

    @abstractmethod
    def append_deliver_seeds(
        self,
        *,
        deliver_seeds_by_worker_id: Mapping[WorkerId, DeliverSeed],
    ) -> Mapping[WorkerId, DeliverSeedAppendStatus]:
        """Store a DeliverSeed only when the Worker mailbox is empty."""
        pass

    @abstractmethod
    def consume_deliver_seeds(
        self,
        *,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, DeliverSeed]:
        """Atomically consume mailboxes for a bounded Worker-id batch."""
        pass
