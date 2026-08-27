from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Mapping, Sequence

from .task_score_band import TaskId, TimeMillis
from .worker_runtime import EndpointManagerId, WorkerGroupId
from .worker_score import Score as WorkerScore
from .worker_score import WorkerId


CandidateId = str


@dataclass(frozen=True)
class CandidateWorkerEntry:
    """One Task-local Worker candidate with lease and delivery-route evidence."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    worker_lease_score: WorkerScore


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
