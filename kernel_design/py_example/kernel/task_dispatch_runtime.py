from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Sequence

from .task_score_band import TaskId, TimeMillis
from .worker_runtime import WorkerGroupId
from .worker_score import Score as WorkerScore
from .worker_score import WorkerId


@dataclass(frozen=True)
class CandidateWorkerEntry:
    """One expiring Worker candidate queued for one Task."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    observed_worker_score: WorkerScore
    expires_at_millis: TimeMillis


class TaskDispatchRuntime(ABC):
    """Owner surface for transient allocation-to-dispatch evidence."""

    @abstractmethod
    def append_candidate_workers(
        self,
        *,
        task_id: TaskId,
        candidate_workers: Sequence[CandidateWorkerEntry],
    ) -> None:
        """Append every supplied candidate to one Task queue."""
        pass

    @abstractmethod
    def candidate_worker_count(
        self,
        *,
        task_id: TaskId,
    ) -> int:
        """Return the current stored entry count for one Task queue."""
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
