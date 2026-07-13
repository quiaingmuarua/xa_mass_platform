from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from contextlib import suppress
from dataclasses import dataclass
from time import time_ns

from .task_dispatch_runtime import CandidateWorkerEntry, TaskDispatchRuntime
from .task_runtime import TaskDescriptor, TaskResourceCatalog
from .task_score_band import (
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreTransitionStatus,
    TaskId,
    TimeMillis,
)
from .worker_candidate_matcher import WorkerCandidateMatcher
from .worker_runtime import (
    WorkerCandidateConstraint,
    WorkerGroupId,
)
from .worker_score import WorkerScoreCore


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
        dispatch_runtime: TaskDispatchRuntime,
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
        allocation_millis = self._current_time_millis()
        task_band_by_id: dict[TaskId, TaskScoreBand] = {}
        remaining_task_limit = config.task_batch_limit
        for band in self.ALLOCATION_BANDS:
            if remaining_task_limit <= 0:
                break
            task_ids = self.task_score.acquire_band_task_candidates(
                band=band,
                before_time_millis=allocation_millis,
                limit=remaining_task_limit,
            )
            for task_id in task_ids:
                task_band_by_id[task_id] = band
            remaining_task_limit -= len(task_ids)

        task_ids = tuple(task_band_by_id)
        candidate_counts = self.dispatch_runtime.candidate_worker_counts(
            task_ids=task_ids,
        )
        grouped_constraints = self._prepare_allocation_groups(
            task_ids,
            candidate_counts,
        )
        published_tasks = 0
        for worker_group_id, candidate_constraints in grouped_constraints.items():
            worker_ids = self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id=worker_group_id,
                limit=config.worker_scan_limit,
            )
            lease_until_millis = (
                self._current_time_millis()
                + config.worker_lease_duration_millis
            )
            entries_by_task = self.worker_matcher.match_worker_candidates(
                worker_group_id=worker_group_id,
                worker_ids=worker_ids,
                candidate_constraints=candidate_constraints,
                lease_until_millis=lease_until_millis,
            )
            published_tasks += self._publish_group_candidates(
                entries_by_task=entries_by_task,
                expires_at_millis=lease_until_millis,
            )

        for task_id, expected_band in task_band_by_id.items():
            self.task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=expected_band,
                target_time_millis=self._current_time_millis(),
            )
        return published_tasks

    def _prepare_allocation_groups(
        self,
        task_ids: tuple[TaskId, ...],
        candidate_counts: Mapping[TaskId, int],
    ) -> dict[WorkerGroupId, dict[TaskId, WorkerCandidateConstraint]]:
        if not task_ids:
            return {}

        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )

        grouped_tasks: dict[
            WorkerGroupId,
            dict[TaskId, WorkerCandidateConstraint],
        ] = {}
        for task_id in task_ids:
            descriptor = descriptors.get(task_id)
            if descriptor is None:
                continue

            maximum_candidates = int(
                descriptor.config["maximumCandidateWorkers"]
            )
            remaining_candidates = max(
                0,
                maximum_candidates - candidate_counts.get(task_id, 0),
            )
            if remaining_candidates == 0:
                continue

            constraint = WorkerCandidateConstraint(
                priority=int(descriptor.config["priority"]),
                limit=remaining_candidates,
                match_rules=descriptor.allocation_rule,
            )
            group = grouped_tasks.setdefault(descriptor.worker_group_id, {})
            group[task_id] = constraint
        return grouped_tasks

    def _publish_group_candidates(
        self,
        *,
        entries_by_task: Mapping[TaskId, Sequence[CandidateWorkerEntry]],
        expires_at_millis: TimeMillis,
    ) -> int:
        task_entries = tuple(entries_by_task.items())
        published_tasks = 0
        for index, (task_id, entries) in enumerate(task_entries):
            if not entries:
                continue
            try:
                self.dispatch_runtime.append_candidate_workers(
                    task_id=task_id,
                    candidate_workers=entries,
                    expires_at_millis=expires_at_millis,
                )
            except Exception:
                self._release_unpublished_entries(task_entries[index:])
                raise
            published_tasks += 1
        return published_tasks

    def _release_unpublished_entries(
        self,
        task_entries: tuple[
            tuple[TaskId, Sequence[CandidateWorkerEntry]],
            ...,
        ],
    ) -> None:
        release_time_millis = self._current_time_millis()
        for _, entries in task_entries:
            for entry in entries:
                with suppress(Exception):
                    self.worker_score.release_score_hold(
                        home_bucket_id=entry.worker_group_id,
                        worker_id=entry.worker_id,
                        observed_score=entry.worker_lease_score,
                        release_time_millis=release_time_millis,
                    )

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000


class TaskRunningActivationPacer:
    """Activate PRE_DISPATCH_VISIBLE Tasks from candidate evidence."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        dispatch_runtime: TaskDispatchRuntime,
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
        transition_millis = self._current_time_millis()
        task_ids = tuple(
            self.task_score.acquire_band_task_candidates(
                band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
                before_time_millis=transition_millis,
                limit=config.task_batch_limit,
            )
        )
        if not task_ids:
            return 0

        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        candidate_counts = self.dispatch_runtime.candidate_worker_counts(
            task_ids=task_ids,
        )
        transitioned = 0
        for task_id in task_ids:
            descriptor = descriptors.get(task_id)
            if descriptor is None:
                continue
            candidate_worker_count = candidate_counts.get(task_id, 0)
            if not self.activation_policy(descriptor, candidate_worker_count):
                continue

            result = self.task_score.rewrite_score(
                task_id=task_id,
                expected_band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
                target_band=TaskScoreBand.RUNNING_VISIBLE,
                target_time_millis=transition_millis,
                target_suffix=config.running_visible_initial_suffix,
            )
            if result.status is TaskScoreTransitionStatus.TRANSITIONED:
                transitioned += 1
        return transitioned

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
