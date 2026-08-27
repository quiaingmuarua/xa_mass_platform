from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from time import time_ns
from typing import cast

from ..kernel.assignment_dispatch_runtime import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
)
from ..kernel.task_runtime import TaskDescriptor, WorkerAllocationMechanism
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_runtime import WorkerGroupId
from .task_scheduling_batch_source import DueTaskObservation
from .worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisition,
    WorkerCandidateRequest,
)


@dataclass(frozen=True)
class TaskWorkerAllocationConfig:
    """Worker lease policy for one RUNNING Task batch."""

    worker_lease_duration_millis: int

    def __post_init__(self) -> None:
        if self.worker_lease_duration_millis <= 0:
            raise ValueError("Worker lease duration must be positive")


class TaskWorkerAllocationPolicy:
    """Fill PRECOMPUTED candidate caches for an observed Task batch."""

    def __init__(
        self,
        candidate_acquirer: WorkerCandidateAcquirer,
        candidate_cache: CandidateWorkerCache,
    ) -> None:
        self.candidate_acquirer = candidate_acquirer
        self.candidate_cache = candidate_cache

    def allocate_candidate_workers(
        self,
        tasks: Sequence[DueTaskObservation],
        *,
        config: TaskWorkerAllocationConfig,
    ) -> int:
        precomputed = tuple(
            task
            for task in tasks
            if task.descriptor.worker_allocation_mechanism
            is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        )
        if not precomputed:
            return 0
        candidate_worker_counts = self.candidate_cache.candidate_worker_counts(
            candidate_ids=tuple(task.task_id for task in precomputed),
        )
        candidate_requests_by_worker_group = self._build_candidate_requests(
            precomputed,
            candidate_worker_counts,
        )
        lease_until_millis = (
            self._current_time_millis()
            + config.worker_lease_duration_millis
        )
        acquired_candidates: dict[
            TaskId,
            tuple[CandidateWorkerEntry, ...],
        ] = {}
        for worker_group_id, candidate_requests in (
            candidate_requests_by_worker_group.items()
        ):
            acquired_candidates.update(
                self.candidate_acquirer.acquire_hot_pool_candidates(
                    worker_group_id=worker_group_id,
                    candidate_requests=candidate_requests,
                    lease_until_millis=lease_until_millis,
                )
            )
        return self._publish_candidate_workers(
            acquired_candidates=acquired_candidates,
            expires_at_millis=lease_until_millis,
        )

    def _build_candidate_requests(
        self,
        tasks: Sequence[DueTaskObservation],
        candidate_worker_counts: Mapping[TaskId, int],
    ) -> dict[WorkerGroupId, dict[TaskId, WorkerCandidateRequest]]:
        requests: dict[
            WorkerGroupId,
            dict[TaskId, WorkerCandidateRequest],
        ] = {}
        for task in tasks:
            descriptor: TaskDescriptor = task.descriptor
            maximum = int(descriptor.config["maximumCandidateWorkers"])
            requested = max(
                0,
                maximum - candidate_worker_counts.get(task.task_id, 0),
            )
            if requested == 0 or descriptor.allocation_rule is None:
                continue
            requests.setdefault(descriptor.worker_group_id, {})[
                task.task_id
            ] = WorkerCandidateRequest(
                priority=int(descriptor.config["priority"]),
                requested_count=requested,
                allocation_rule=cast(
                    Mapping[str, object],
                    descriptor.allocation_rule,
                ),
            )
        return requests

    def _publish_candidate_workers(
        self,
        *,
        acquired_candidates: WorkerCandidateAcquisition,
        expires_at_millis: TimeMillis,
    ) -> int:
        published = 0
        for candidate_id, candidate_workers in acquired_candidates.items():
            if not candidate_workers:
                continue
            self.candidate_cache.append_candidate_workers(
                candidate_id=candidate_id,
                candidate_workers=candidate_workers,
                expires_at_millis=expires_at_millis,
            )
            published += 1
        return published

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
