from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Mapping, Sequence

from ..kernel.task_runtime import TaskItem
from ..kernel.task_score_band import Score, TaskId, TimeMillis
from ..kernel.worker_runtime import EndpointManagerId, WorkerGroupId
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId


@dataclass(frozen=True)
class CandidateWorkerEntry:
    """One Task-local Worker candidate with opaque Worker-score evidence."""

    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    worker_lease_score: WorkerScore


@dataclass(frozen=True)
class DeliverSeed:
    """Already-assigned TaskItem handoff for one endpoint manager."""

    task_id: TaskId
    selected_worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    task_item: TaskItem
    claim_score: Score
    worker_lease_score: WorkerScore


class AssignmentDispatchRuntime(ABC):
    """Runtime owner for assignment-to-dispatch intermediate artifacts."""

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

    @abstractmethod
    def append_deliver_seeds(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        deliver_seeds: Sequence[DeliverSeed],
    ) -> None:
        """Append one bounded batch to exactly one endpoint-manager queue."""
        pass
