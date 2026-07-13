from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from time import time_ns

from .task_dispatch_runtime import CandidateWorkerEntry, TaskDispatchRuntime
from .task_runtime import TaskResourceCatalog
from .task_score_band import (
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
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
    no_candidate_recheck_delay_millis: int

    def __post_init__(self) -> None:
        if any(
            value <= 0
            for value in (
                self.task_batch_limit,
                self.worker_scan_limit,
                self.candidate_ttl_millis,
                self.no_candidate_recheck_delay_millis,
            )
        ):
            raise ValueError("allocation config values must be positive")


@dataclass(frozen=True)
class _PreparedTaskAllocation:
    state: TaskScoreState
    priority: int
    match_rules: Mapping[str, object]
    minimum_candidate_workers: int
    queued_candidate_workers: int
    remaining_candidate_workers: int


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
        grouped_tasks = self._prepare_allocation_groups(config)
        published_tasks = 0
        for worker_group_id, task_allocations in grouped_tasks.items():
            allocation_millis = self._current_time_millis()
            entries_by_task = self._match_group_candidates(
                worker_group_id=worker_group_id,
                task_allocations=task_allocations,
                config=config,
                allocation_millis=allocation_millis,
            )
            published_tasks += self._commit_group_allocations(
                task_allocations=task_allocations,
                entries_by_task=entries_by_task,
                config=config,
                allocation_millis=allocation_millis,
            )
        return published_tasks

    def _prepare_allocation_groups(
        self,
        config: TaskWorkerAllocationConfig,
    ) -> dict[WorkerGroupId, dict[TaskId, _PreparedTaskAllocation]]:
        task_ids = tuple(
            self.task_score.acquire_active_task_candidates(
                limit=config.task_batch_limit,
            )
        )
        if not task_ids:
            return {}

        score_states = self.task_score.get_score_states(task_ids=task_ids)
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )

        grouped_tasks: dict[
            WorkerGroupId,
            dict[TaskId, _PreparedTaskAllocation],
        ] = {}
        for task_id in task_ids:
            state = score_states.get(task_id)
            descriptor = descriptors.get(task_id)
            if state is None or descriptor is None:
                continue

            priority = int(descriptor.config["priority"])
            minimum_candidate_workers = int(
                descriptor.config["runningVisibleMinimumCandidateWorkers"]
            )
            maximum_candidate_workers = int(
                descriptor.config["maximumCandidateWorkers"]
            )
            queued_candidate_workers = (
                self.dispatch_runtime.candidate_worker_count(task_id=task_id)
            )
            remaining_candidate_workers = max(
                0,
                maximum_candidate_workers - queued_candidate_workers,
            )
            allocation = _PreparedTaskAllocation(
                state=state,
                priority=priority,
                match_rules=descriptor.allocation_rule,
                minimum_candidate_workers=minimum_candidate_workers,
                queued_candidate_workers=queued_candidate_workers,
                remaining_candidate_workers=remaining_candidate_workers,
            )
            group = grouped_tasks.setdefault(descriptor.worker_group_id, {})
            group[task_id] = allocation
        return grouped_tasks

    def _match_group_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        task_allocations: Mapping[TaskId, _PreparedTaskAllocation],
        config: TaskWorkerAllocationConfig,
        allocation_millis: TimeMillis,
    ) -> dict[TaskId, tuple[CandidateWorkerEntry, ...]]:
        constraints = {
            task_id: WorkerCandidateConstraint(
                priority=allocation.priority,
                limit=allocation.remaining_candidate_workers,
                match_rules=allocation.match_rules,
            )
            for task_id, allocation in task_allocations.items()
            if allocation.remaining_candidate_workers > 0
        }
        if not constraints:
            return {task_id: () for task_id in task_allocations}

        observed_scores = dict(
            self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id=worker_group_id,
                limit=config.worker_scan_limit,
            )
        )
        if not observed_scores:
            return {task_id: () for task_id in task_allocations}

        matches = self.worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=tuple(observed_scores),
            candidate_constraints=constraints,
        )
        expires_at_millis = allocation_millis + config.candidate_ttl_millis
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
            if task_id in constraints
            else ()
            for task_id in task_allocations
        }

    def _commit_group_allocations(
        self,
        *,
        task_allocations: Mapping[TaskId, _PreparedTaskAllocation],
        entries_by_task: Mapping[TaskId, tuple[CandidateWorkerEntry, ...]],
        config: TaskWorkerAllocationConfig,
        allocation_millis: TimeMillis,
    ) -> int:
        published_tasks = 0
        for task_id, allocation in task_allocations.items():
            entries = entries_by_task[task_id]
            available_candidates = allocation.queued_candidate_workers + len(entries)
            if available_candidates == 0 or (
                allocation.state.band is TaskScoreBand.PRE_DISPATCH_VISIBLE
                and available_candidates < allocation.minimum_candidate_workers
            ):
                self._defer_without_candidates(
                    allocation.state,
                    config,
                    allocation_millis,
                )
                continue

            transition = (
                self.task_score.rewrite_score(
                    task_id=task_id,
                    expected_band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
                    target_time_millis=allocation_millis,
                    target_band=TaskScoreBand.RUNNING_VISIBLE,
                )
                if allocation.state.band is TaskScoreBand.PRE_DISPATCH_VISIBLE
                else self.task_score.rewrite_same_band_time_millis(
                    task_id=task_id,
                    expected_band=TaskScoreBand.RUNNING_VISIBLE,
                    target_time_millis=allocation_millis,
                )
            )
            if transition.status is not TaskScoreTransitionStatus.TRANSITIONED:
                continue
            if entries:
                self.dispatch_runtime.append_candidate_workers(
                    task_id=task_id,
                    candidate_workers=entries,
                )
                published_tasks += 1
        return published_tasks

    def _defer_without_candidates(
        self,
        state: TaskScoreState,
        config: TaskWorkerAllocationConfig,
        now_millis: TimeMillis,
    ) -> None:
        if state.suffix is None:
            return
        if state.suffix > 0:
            self.task_score.rewrite_observed_same_band_suffix(
                task_id=state.task_id,
                observed_score=state.score,
                target_time_millis=(
                    now_millis + config.no_candidate_recheck_delay_millis
                ),
                suffix_delta=-1,
            )
            return
        self.task_score.rewrite_same_band_time_millis(
            task_id=state.task_id,
            expected_band=state.band,
            target_time_millis=TaskScoreBandCore.PAUSE_TIME_MILLIS,
        )

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
