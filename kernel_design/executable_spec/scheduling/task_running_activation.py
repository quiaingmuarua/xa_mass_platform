from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from time import time_ns
from typing import Protocol

from ..kernel.task_item_score_band import TaskItemScoreBandCore
from ..kernel.task_runtime import TaskDescriptor
from ..kernel.task_score_band import (
    TaskId,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionStatus,
    TimeMillis,
)
from .task_scheduling_batch_source import DueTaskObservation


@dataclass(frozen=True)
class TaskRunningActivationConfig:
    priority_recheck_step_millis: int = 1_000

    def __post_init__(self) -> None:
        if self.priority_recheck_step_millis <= 0:
            raise ValueError("priority recheck step must be positive")


class TaskAdmissionPolicy(Protocol):
    def filter_tasks(
        self,
        *,
        ordered_task_ids: Sequence[TaskId],
        descriptors: Mapping[TaskId, TaskDescriptor],
    ) -> tuple[TaskId, ...]: ...


class SystemAdmissionPolicy(Protocol):
    def select_tasks(
        self,
        *,
        ordered_task_ids: Sequence[TaskId],
        descriptors: Mapping[TaskId, TaskDescriptor],
    ) -> tuple[TaskId, ...]: ...


class DueTaskItemAdmissionPolicy:
    """Require at least one currently due ACTIVE TaskItem score."""

    def __init__(self, item_score: TaskItemScoreBandCore) -> None:
        self.item_score = item_score

    def filter_tasks(
        self,
        *,
        ordered_task_ids: Sequence[TaskId],
        descriptors: Mapping[TaskId, TaskDescriptor],
    ) -> tuple[TaskId, ...]:
        due_by_task_id = self.item_score.has_due_active_items(
            task_ids=ordered_task_ids,
        )
        return tuple(
            task_id
            for task_id in ordered_task_ids
            if due_by_task_id.get(task_id, False)
        )


class RunningSoftLimitSystemAdmissionPolicy:
    """Apply the built-in RUNNING soft limit to score-ordered Tasks."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        *,
        running_task_soft_limit: int,
    ) -> None:
        if running_task_soft_limit <= 0:
            raise ValueError("running Task soft limit must be positive")
        self.task_score = task_score
        self.running_task_soft_limit = running_task_soft_limit

    def select_tasks(
        self,
        *,
        ordered_task_ids: Sequence[TaskId],
        descriptors: Mapping[TaskId, TaskDescriptor],
    ) -> tuple[TaskId, ...]:
        available_slots = max(
            0,
            self.running_task_soft_limit
            - self.task_score.count_running_capacity_tasks(),
        )
        return tuple(ordered_task_ids[:available_slots])


class TaskRunningActivationPolicy:
    """Apply admission policies to an observed ADMISSION Task batch."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_admission_policy: TaskAdmissionPolicy,
        system_admission_policy: SystemAdmissionPolicy,
    ) -> None:
        self.task_score = task_score
        self.task_admission_policy = task_admission_policy
        self.system_admission_policy = system_admission_policy

    def activate_running_visible_tasks(
        self,
        tasks: Sequence[DueTaskObservation],
        *,
        config: TaskRunningActivationConfig,
    ) -> int:
        activation_time_millis = self._current_time_millis()
        observed_task_ids = tuple(task.task_id for task in tasks)
        if not observed_task_ids:
            return 0
        descriptors = {task.task_id: task.descriptor for task in tasks}
        observed_states = {
            task.task_id: task.score_state
            for task in tasks
        }
        task_allowed_ids = self._validated_policy_output(
            input_task_ids=observed_task_ids,
            output_task_ids=self.task_admission_policy.filter_tasks(
                ordered_task_ids=observed_task_ids,
                descriptors=descriptors,
            ),
            policy_name="Task admission policy",
        )
        system_allowed_ids = self._validated_policy_output(
            input_task_ids=task_allowed_ids,
            output_task_ids=self.system_admission_policy.select_tasks(
                ordered_task_ids=task_allowed_ids,
                descriptors=descriptors,
            ),
            policy_name="System admission policy",
        )

        activated_task_ids: list[TaskId] = []
        for task_id in system_allowed_ids:
            result = self.task_score.rewrite_score(
                task_id=task_id,
                expected_band=TaskScoreBand.ADMISSION_VISIBLE,
                target_band=TaskScoreBand.RUNNING_VISIBLE,
                target_time_millis=activation_time_millis,
                target_suffix=TaskScoreBandCore.MIN_SUFFIX,
            )
            if result.status is TaskScoreTransitionStatus.TRANSITIONED:
                activated_task_ids.append(task_id)

        self._reschedule_observed_admission_tasks(
            observed_task_ids=observed_task_ids,
            observed_states=observed_states,
            activated_task_ids=activated_task_ids,
            activation_time_millis=activation_time_millis,
            priority_recheck_step_millis=(
                config.priority_recheck_step_millis
            ),
        )
        return len(activated_task_ids)

    def _reschedule_observed_admission_tasks(
        self,
        *,
        observed_task_ids: Sequence[TaskId],
        observed_states: Mapping[TaskId, TaskScoreState | None],
        activated_task_ids: Sequence[TaskId],
        activation_time_millis: TimeMillis,
        priority_recheck_step_millis: int,
    ) -> None:
        activated = set(activated_task_ids)
        for task_id in observed_task_ids:
            if task_id in activated:
                continue
            state = observed_states.get(task_id)
            if (
                state is None
                or state.band is not TaskScoreBand.ADMISSION_VISIBLE
                or state.suffix is None
            ):
                continue
            priority_bucket = state.suffix // 10
            self.task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=TaskScoreBand.ADMISSION_VISIBLE,
                target_time_millis=(
                    activation_time_millis
                    + TaskScoreBandCore.SLOT_MILLIS
                    + priority_bucket * priority_recheck_step_millis
                ),
            )

    @staticmethod
    def _validated_policy_output(
        *,
        input_task_ids: Sequence[TaskId],
        output_task_ids: Sequence[TaskId],
        policy_name: str,
    ) -> tuple[TaskId, ...]:
        normalized = tuple(output_task_ids)
        if len(normalized) != len(set(normalized)):
            raise ValueError(f"{policy_name} returned duplicate Task ids")
        input_set = set(input_task_ids)
        if any(task_id not in input_set for task_id in normalized):
            raise ValueError(f"{policy_name} returned an unobserved Task id")
        return normalized

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
