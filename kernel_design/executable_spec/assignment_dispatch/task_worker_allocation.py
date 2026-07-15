from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from time import time_ns

from ..kernel.task_runtime import TaskDescriptor, TaskResourceCatalog
from ..kernel.task_score_band import (
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreTransitionStatus,
    TaskId,
    TimeMillis,
)
from .worker_candidate_matcher import (
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
)
from ..kernel.worker_runtime import WorkerGroupId
from ..kernel.worker_score import (
    Score,
    WorkerId,
    WorkerScoreCore,
    WorkerScoreTransitionStatus,
)
from .runtime import AssignmentDispatchRuntime, CandidateWorkerEntry


@dataclass(frozen=True)
class TaskWorkerAllocationConfig:
    """Policy bounds supplied to one allocation round."""

    task_batch_limit: int
    worker_scan_limit: int
    worker_lease_duration_millis: int

    def __post_init__(self) -> None:
        if any(
            value <= 0
            for value in (
                self.task_batch_limit,
                self.worker_scan_limit,
                self.worker_lease_duration_millis,
            )
        ):
            raise ValueError("allocation config values must be positive")


@dataclass(frozen=True)
class TaskRunningActivationConfig:
    """Bounds for one PRE_DISPATCH_VISIBLE activation round."""

    task_batch_limit: int
    running_visible_initial_suffix: int

    def __post_init__(self) -> None:
        if self.task_batch_limit <= 0:
            raise ValueError("task batch limit must be positive")
        if not (
            TaskScoreBandCore.MIN_SUFFIX
            < self.running_visible_initial_suffix
            <= TaskScoreBandCore.MAX_SUFFIX
        ):
            raise ValueError("running visible initial suffix must be in 1..99")


TaskRunningActivationPolicy = Callable[[TaskDescriptor, int], bool]


def minimum_candidate_workers_satisfied(
    descriptor: TaskDescriptor,
    candidate_worker_count: int,
) -> bool:
    """Built-in RUNNING activation policy."""
    minimum_candidate_workers = int(
        descriptor.config["runningVisibleMinimumCandidateWorkers"]
    )
    return candidate_worker_count >= minimum_candidate_workers


class TaskWorkerAllocationPacer:
    """Run one bounded Task-to-Worker candidate allocation round."""

    ALLOCATION_BANDS = (
        TaskScoreBand.RUNNING_VISIBLE,
        TaskScoreBand.PRE_DISPATCH_VISIBLE,
    )

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        worker_score: WorkerScoreCore,
        worker_matcher: WorkerCandidateMatcher,
        dispatch_runtime: AssignmentDispatchRuntime,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.worker_score = worker_score
        self.worker_matcher = worker_matcher
        self.dispatch_runtime = dispatch_runtime

    def allocate_candidate_workers(
        self,
        *,
        config: TaskWorkerAllocationConfig,
    ) -> int:
        """Publish candidate Workers and return the number of published Tasks."""
        task_scan_time_millis = self._current_time_millis()
        task_candidate_bands: dict[TaskId, TaskScoreBand] = {}
        task_limit_remaining = config.task_batch_limit
        for band in self.ALLOCATION_BANDS:
            if task_limit_remaining <= 0:
                break
            band_task_ids = self.task_score.acquire_band_task_candidates(
                band=band,
                before_time_millis=task_scan_time_millis,
                limit=task_limit_remaining,
            )
            for task_id in band_task_ids:
                task_candidate_bands[task_id] = band
            task_limit_remaining -= len(band_task_ids)

        task_ids = tuple(task_candidate_bands)
        candidate_worker_counts = self.dispatch_runtime.candidate_worker_counts(
            task_ids=task_ids,
        )
        grouped_task_constraints = self._group_task_constraints(
            task_ids,
            candidate_worker_counts,
        )
        published_task_count = 0
        for worker_group_id, task_constraints in grouped_task_constraints.items():
            worker_candidates = (
                self.worker_score.acquire_hot_acquire_candidates(
                    home_bucket_id=worker_group_id,
                    limit=config.worker_scan_limit,
                )
            )
            if not worker_candidates:
                continue
            worker_lease_until_millis = (
                self._current_time_millis()
                + config.worker_lease_duration_millis
            )
            lease_results = self.worker_score.acquire_observed_hot_score_leases(
                home_bucket_id=worker_group_id,
                observed_scores=worker_candidates,
                target_time_millis=worker_lease_until_millis,
            )
            leased_worker_scores: dict[WorkerId, Score] = {}
            for worker_id, lease_result in lease_results.items():
                if (
                    lease_result.status
                    is not WorkerScoreTransitionStatus.TRANSITIONED
                ):
                    continue
                if lease_result.score is None:
                    continue
                leased_worker_scores[worker_id] = lease_result.score

            if not leased_worker_scores:
                continue

            match_result = self.worker_matcher.match_worker_candidates(
                worker_group_id=worker_group_id,
                worker_ids=tuple(leased_worker_scores),
                candidate_constraints=task_constraints,
            )
            endpoint_manager_ids = (
                match_result.endpoint_manager_id_by_worker_id
            )
            matched_candidates = {
                task_id: tuple(
                    CandidateWorkerEntry(
                        worker_id=worker_id,
                        worker_group_id=worker_group_id,
                        endpoint_manager_id=endpoint_manager_ids[worker_id],
                        worker_lease_score=leased_worker_scores[worker_id],
                    )
                    for worker_id in worker_ids
                )
                for task_id, worker_ids in match_result.matches.items()
            }
            published_task_count += self._publish_candidate_workers(
                matched_candidates=matched_candidates,
                expires_at_millis=worker_lease_until_millis,
            )

        for task_id, expected_band in task_candidate_bands.items():
            self.task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=expected_band,
                target_time_millis=self._current_time_millis(),
            )
        return published_task_count

    def _group_task_constraints(
        self,
        task_ids: tuple[TaskId, ...],
        candidate_worker_counts: Mapping[TaskId, int],
    ) -> dict[WorkerGroupId, dict[TaskId, WorkerCandidateConstraint]]:
        if not task_ids:
            return {}

        task_descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )

        grouped_task_constraints: dict[
            WorkerGroupId,
            dict[TaskId, WorkerCandidateConstraint],
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

            task_constraint = WorkerCandidateConstraint(
                priority=int(descriptor.config["priority"]),
                limit=candidate_limit,
                match_rules=descriptor.allocation_rule,
            )
            worker_group_constraints = grouped_task_constraints.setdefault(
                descriptor.worker_group_id,
                {},
            )
            worker_group_constraints[task_id] = task_constraint
        return grouped_task_constraints

    def _publish_candidate_workers(
        self,
        *,
        matched_candidates: Mapping[TaskId, Sequence[CandidateWorkerEntry]],
        expires_at_millis: TimeMillis,
    ) -> int:
        published_task_count = 0
        for task_id, candidate_workers in matched_candidates.items():
            if not candidate_workers:
                continue
            self.dispatch_runtime.append_candidate_workers(
                task_id=task_id,
                candidate_workers=candidate_workers,
                expires_at_millis=expires_at_millis,
            )
            published_task_count += 1
        return published_task_count

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000


class TaskRunningActivationPacer:
    """Activate PRE_DISPATCH_VISIBLE Tasks from candidate evidence."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        dispatch_runtime: AssignmentDispatchRuntime,
        activation_policy: TaskRunningActivationPolicy,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.dispatch_runtime = dispatch_runtime
        self.activation_policy = activation_policy

    def activate_running_visible_tasks(
        self,
        *,
        config: TaskRunningActivationConfig,
    ) -> int:
        """Promote eligible PRE_DISPATCH_VISIBLE Tasks to RUNNING_VISIBLE."""
        activation_time_millis = self._current_time_millis()
        pre_dispatch_task_ids = tuple(
            self.task_score.acquire_band_task_candidates(
                band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
                before_time_millis=activation_time_millis,
                limit=config.task_batch_limit,
            )
        )
        if not pre_dispatch_task_ids:
            return 0

        task_descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=pre_dispatch_task_ids,
        )
        candidate_worker_counts = self.dispatch_runtime.candidate_worker_counts(
            task_ids=pre_dispatch_task_ids,
        )
        activated_task_count = 0
        for task_id in pre_dispatch_task_ids:
            descriptor = task_descriptors.get(task_id)
            if descriptor is None:
                continue
            candidate_worker_count = candidate_worker_counts.get(task_id, 0)
            if not self.activation_policy(descriptor, candidate_worker_count):
                continue

            activation_result = self.task_score.rewrite_score(
                task_id=task_id,
                expected_band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
                target_band=TaskScoreBand.RUNNING_VISIBLE,
                target_time_millis=activation_time_millis,
                target_suffix=config.running_visible_initial_suffix,
            )
            if activation_result.status is TaskScoreTransitionStatus.TRANSITIONED:
                activated_task_count += 1
        return activated_task_count

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
