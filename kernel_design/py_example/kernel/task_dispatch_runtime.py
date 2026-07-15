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


class TaskDispatchRuntime(ABC):
    """Owner surface for transient allocation-to-dispatch candidates."""

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
        """Atomically consume up to limit entries from one Task queue."""
        pass
