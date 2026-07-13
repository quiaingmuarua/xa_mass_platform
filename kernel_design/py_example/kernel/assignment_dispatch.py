from __future__ import annotations

from dataclasses import dataclass
from time import time_ns

from .task_dispatch_runtime import CandidateWorkerEntry, TaskDispatchRuntime
from .task_runtime import TaskDescriptor, TaskResourceCatalog
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


class TaskWorkerAllocationPacer:
    """Run one bounded Task-to-Worker candidate allocation round."""

    PRIORITY_MIN = 1
    PRIORITY_MAX = 100

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
        task_ids = tuple(
            self.task_score.acquire_active_task_candidates(
                limit=config.task_batch_limit,
            )
        )
        if not task_ids:
            return 0

        score_states = self.task_score.get_score_states(task_ids=task_ids)
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )

        tasks_by_group: dict[WorkerGroupId, list[TaskId]] = {}
        constraints: dict[TaskId, WorkerCandidateConstraint] = {}
        minimum_workers: dict[TaskId, int] = {}
        accepted_states: dict[TaskId, TaskScoreState] = {}

        for task_id in task_ids:
            state = score_states.get(task_id)
            if state is None or state.task_id != task_id or state.band not in {
                TaskScoreBand.RUNNING_VISIBLE,
                TaskScoreBand.PRE_DISPATCH_VISIBLE,
            }:
                continue

            prepared = self._prepare_constraint(
                task_id,
                descriptors.get(task_id),
            )
            if prepared is None:
                self._defer_without_candidates(
                    state,
                    config,
                    self._current_time_millis(),
                )
                continue

            descriptor, constraint, minimum = prepared
            tasks_by_group.setdefault(descriptor.worker_group_id, []).append(task_id)
            constraints[task_id] = constraint
            minimum_workers[task_id] = minimum
            accepted_states[task_id] = state

        published_tasks = 0
        for worker_group_id, group_task_ids in tasks_by_group.items():
            worker_candidates = self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id=worker_group_id,
                limit=config.worker_scan_limit,
            )
            observed_scores = dict(worker_candidates)
            matches = (
                self.worker_matcher.match_worker_candidates(
                    worker_group_id=worker_group_id,
                    worker_ids=tuple(observed_scores),
                    candidate_constraints={
                        task_id: constraints[task_id] for task_id in group_task_ids
                    },
                )
                if observed_scores
                else {task_id: [] for task_id in group_task_ids}
            )

            for task_id in group_task_ids:
                state = accepted_states[task_id]
                classification_millis = self._current_time_millis()
                entries = tuple(
                    CandidateWorkerEntry(
                        worker_id=worker_id,
                        worker_group_id=worker_group_id,
                        observed_worker_score=observed_scores[worker_id],
                        expires_at_millis=(
                            classification_millis + config.candidate_ttl_millis
                        ),
                    )
                    for worker_id in matches.get(task_id, ())
                    if worker_id in observed_scores
                )
                if not entries or (
                    state.band is TaskScoreBand.PRE_DISPATCH_VISIBLE
                    and len(entries) < minimum_workers[task_id]
                ):
                    self._defer_without_candidates(
                        state,
                        config,
                        classification_millis,
                    )
                    continue

                transition = (
                    self.task_score.rewrite_score(
                        task_id=task_id,
                        expected_band=TaskScoreBand.PRE_DISPATCH_VISIBLE,
                        target_time_millis=classification_millis,
                        target_band=TaskScoreBand.RUNNING_VISIBLE,
                    )
                    if state.band is TaskScoreBand.PRE_DISPATCH_VISIBLE
                    else self.task_score.rewrite_same_band_time_millis(
                        task_id=task_id,
                        expected_band=TaskScoreBand.RUNNING_VISIBLE,
                        target_time_millis=classification_millis,
                    )
                )
                if transition.status is not TaskScoreTransitionStatus.TRANSITIONED:
                    continue

                self.dispatch_runtime.append_candidate_workers(
                    task_id=task_id,
                    candidate_workers=entries,
                )
                published_tasks += 1

        return published_tasks

    def _prepare_constraint(
        self,
        task_id: TaskId,
        descriptor: TaskDescriptor | None,
    ) -> tuple[TaskDescriptor, WorkerCandidateConstraint, int] | None:
        if descriptor is None or descriptor.task_id != task_id:
            return None
        try:
            priority_text = descriptor.config["priority"]
            minimum_workers_text = descriptor.config[
                "runningVisibleMinimumCandidateWorkers"
            ]
            maximum_workers_text = descriptor.config["maximumCandidateWorkers"]
        except (KeyError, TypeError):
            return None
        if not (
            isinstance(priority_text, str)
            and priority_text.isdecimal()
            and isinstance(minimum_workers_text, str)
            and minimum_workers_text.isdecimal()
            and isinstance(maximum_workers_text, str)
            and maximum_workers_text.isdecimal()
        ):
            return None
        priority = int(priority_text)
        minimum_workers = int(minimum_workers_text)
        maximum_workers = int(maximum_workers_text)
        if not self.PRIORITY_MIN <= priority <= self.PRIORITY_MAX:
            return None
        if not 1 <= minimum_workers <= maximum_workers:
            return None
        if not descriptor.task_id or not descriptor.worker_group_id:
            return None

        return (
            descriptor,
            WorkerCandidateConstraint(
                priority=priority,
                limit=maximum_workers,
                match_rules=descriptor.allocation_rule,
            ),
            minimum_workers,
        )

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
