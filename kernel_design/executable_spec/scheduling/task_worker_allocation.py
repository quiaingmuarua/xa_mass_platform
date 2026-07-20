from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from time import time_ns

from ..kernel.assignment_dispatch_runtime import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
)
from ..kernel.task_runtime import TaskResourceCatalog
from ..kernel.task_score_band import (
    TaskScoreBand,
    TaskScoreBandCore,
    TaskId,
    TimeMillis,
)
from ..kernel.worker_runtime import WorkerGroupId
from .worker_candidate import (
    RealtimeWorkerCandidateAcquirer,
    WorkerCandidateAcquisition,
    WorkerCandidateRequest,
)


@dataclass(frozen=True)
class TaskWorkerAllocationConfig:
    """Policy bounds supplied to one allocation round."""

    task_batch_limit: int
    worker_lease_duration_millis: int

    def __post_init__(self) -> None:
        if any(
            value <= 0
            for value in (
                self.task_batch_limit,
                self.worker_lease_duration_millis,
            )
        ):
            raise ValueError("allocation config values must be positive")


class TaskWorkerAllocationPacer:
    """Run one bounded Task-to-Worker candidate allocation round."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        realtime_candidate_acquirer: RealtimeWorkerCandidateAcquirer,
        candidate_cache: CandidateWorkerCache,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.realtime_candidate_acquirer = realtime_candidate_acquirer
        self.candidate_cache = candidate_cache

    def allocate_candidate_workers(
        self,
        *,
        config: TaskWorkerAllocationConfig,
    ) -> int:
        """Publish candidate Workers and return the number of published Tasks."""
        task_scan_time_millis = self._current_time_millis()
        task_ids = tuple(
            self.task_score.acquire_band_task_candidates(
                band=TaskScoreBand.RUNNING_VISIBLE,
                before_time_millis=task_scan_time_millis,
                limit=config.task_batch_limit,
            )
        )
        candidate_worker_counts = self.candidate_cache.candidate_worker_counts(
            candidate_ids=task_ids,
        )
        candidate_requests_by_worker_group = self._build_candidate_requests(
            task_ids,
            candidate_worker_counts,
        )
        worker_lease_until_millis = (
            self._current_time_millis() + config.worker_lease_duration_millis
        )
        acquired_candidates: dict[
            TaskId,
            tuple[CandidateWorkerEntry, ...],
        ] = {}
        for worker_group_id, candidate_requests in (
            candidate_requests_by_worker_group.items()
        ):
            acquired_candidates.update(
                self.realtime_candidate_acquirer.acquire_worker_candidates(
                    worker_group_id=worker_group_id,
                    candidate_requests=candidate_requests,
                    lease_until_millis=worker_lease_until_millis,
                )
            )
        published_task_count = self._publish_candidate_workers(
            acquired_candidates=acquired_candidates,
            expires_at_millis=worker_lease_until_millis,
        )

        for task_id in task_ids:
            self.task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=TaskScoreBand.RUNNING_VISIBLE,
                target_time_millis=self._current_time_millis(),
            )
        return published_task_count

    def _build_candidate_requests(
        self,
        task_ids: tuple[TaskId, ...],
        candidate_worker_counts: Mapping[TaskId, int],
    ) -> dict[WorkerGroupId, dict[TaskId, WorkerCandidateRequest]]:
        if not task_ids:
            return {}

        task_descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )

        candidate_requests: dict[
            WorkerGroupId,
            dict[TaskId, WorkerCandidateRequest],
        ] = {}
        for task_id in task_ids:
            descriptor = task_descriptors.get(task_id)
            if descriptor is None:
                continue

            maximum_candidate_workers = int(
                descriptor.config["maximumCandidateWorkers"]
            )
            candidate_limit = max(
                0,
                maximum_candidate_workers
                - candidate_worker_counts.get(task_id, 0),
            )
            if candidate_limit == 0:
                continue

            candidate_requests.setdefault(descriptor.worker_group_id, {})[
                task_id
            ] = WorkerCandidateRequest(
                priority=int(descriptor.config["priority"]),
                requested_count=candidate_limit,
                match_rules=descriptor.allocation_rule,
            )
        return candidate_requests

    def _publish_candidate_workers(
        self,
        *,
        acquired_candidates: WorkerCandidateAcquisition,
        expires_at_millis: TimeMillis,
    ) -> int:
        published_task_count = 0
        for candidate_id, candidate_workers in acquired_candidates.items():
            if not candidate_workers:
                continue
            self.candidate_cache.append_candidate_workers(
                candidate_id=candidate_id,
                candidate_workers=candidate_workers,
                expires_at_millis=expires_at_millis,
            )
            published_task_count += 1
        return published_task_count

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
