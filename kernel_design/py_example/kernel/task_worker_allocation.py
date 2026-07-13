from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from time import time_ns

from .task_dispatch_runtime import CandidateWorkerEntry, TaskDispatchRuntime
from .task_runtime import TaskResourceCatalog
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
    candidate_ttl_millis: int

    def __post_init__(self) -> None:
        if any(
            value <= 0
            for value in (
                self.task_batch_limit,
                self.worker_scan_limit,
                self.candidate_ttl_millis,
            )
        ):
            raise ValueError("allocation config values must be positive")


@dataclass(frozen=True)
class TaskScoreTransitionConfig:
    """Bounds for one PRE_DISPATCH_VISIBLE transition round."""

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


class TaskWorkerAllocationPacer:
    """Run one bounded Task-to-Worker candidate allocation round."""

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
        grouped_constraints = self._prepare_allocation_groups(config)
        published_tasks = 0
        for worker_group_id, candidate_constraints in grouped_constraints.items():
            entries_by_task = self._match_group_candidates(
                worker_group_id=worker_group_id,
                candidate_constraints=candidate_constraints,
                config=config,
            )
            for task_id, entries in entries_by_task.items():
                if not entries:
                    continue
                self.dispatch_runtime.append_candidate_workers(
                    task_id=task_id,
                    candidate_workers=entries,
                )
                published_tasks += 1
        return published_tasks

    def _prepare_allocation_groups(
        self,
        config: TaskWorkerAllocationConfig,
    ) -> dict[WorkerGroupId, dict[TaskId, WorkerCandidateConstraint]]:
        task_ids = tuple(
            self.task_score.acquire_active_task_candidates(
                limit=config.task_batch_limit,
            )
        )
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

            constraint = WorkerCandidateConstraint(
                priority=int(descriptor.config["priority"]),
                limit=int(descriptor.config["maximumCandidateWorkers"]),
                match_rules=descriptor.allocation_rule,
            )
            group = grouped_tasks.setdefault(descriptor.worker_group_id, {})
            group[task_id] = constraint
        return grouped_tasks

    def _match_group_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_constraints: Mapping[TaskId, WorkerCandidateConstraint],
        config: TaskWorkerAllocationConfig,
    ) -> dict[TaskId, tuple[CandidateWorkerEntry, ...]]:
        observed_scores = dict(
            self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id=worker_group_id,
                limit=config.worker_scan_limit,
            )
        )
        if not observed_scores:
            return {task_id: () for task_id in candidate_constraints}

        matches = self.worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=tuple(observed_scores),
            candidate_constraints=candidate_constraints,
        )
        expires_at_millis = self._current_time_millis() + config.candidate_ttl_millis
        return {
            task_id: tuple(
                CandidateWorkerEntry(
                    worker_id=worker_id,
                    worker_group_id=worker_group_id,
                    observed_worker_score=observed_scores[worker_id],
                    expires_at_millis=expires_at_millis,
                )
                for worker_id in matches[task_id]
            )
            for task_id in candidate_constraints
        }

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000


class TaskScoreTransitionPacer:
    """Run owner-validated Task score transitions independently of allocation."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        dispatch_runtime: TaskDispatchRuntime,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.dispatch_runtime = dispatch_runtime

    def rewrite_score(
        self,
        *,
        config: TaskScoreTransitionConfig,
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
        transitioned = 0
        for task_id in task_ids:
            descriptor = descriptors.get(task_id)
            if descriptor is None:
                continue
            minimum_candidate_workers = int(
                descriptor.config["runningVisibleMinimumCandidateWorkers"]
            )
            if (
                self.dispatch_runtime.candidate_worker_count(task_id=task_id)
                < minimum_candidate_workers
            ):
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
