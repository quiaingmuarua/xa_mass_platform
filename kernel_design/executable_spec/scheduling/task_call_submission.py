from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from time import time_ns

from ..kernel.task_item_score_band import TaskItemScoreBandCore
from ..kernel.task_runtime import (
    MessageId,
    TaskIdleDisposition,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskResourceCatalog,
    TaskRuntime,
    WorkerAllocationMechanism,
)
from ..kernel.task_score_band import (
    TaskId,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    TimeMillis,
)


class TaskCallSubmissionStatus(Enum):
    SUBMITTED = "submitted"
    NOT_FOUND = "not_found"
    CLOSED = "closed"
    STALE = "stale"
    INVALID = "invalid"
    RETRYABLE = "retryable"


@dataclass(frozen=True)
class TaskCallSubmissionResult:
    status: TaskCallSubmissionStatus
    item_results: Mapping[MessageId, TaskItemAppendResult]
    reason: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "item_results", dict(self.item_results))


class TaskCallItemSubmission:
    """Kernel application command for reusable direct-allocation Task calls.

    The command composes Task resource, Task score and TaskItem owners. It does
    not make their writes transactional; exact score fences and bounded
    post-append repair keep an accepted Item visible to ordinary dispatch.
    """

    MAX_ITEMS = 100

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        item_score: TaskItemScoreBandCore,
        task_runtime: TaskRuntime,
        task_catalog: TaskResourceCatalog,
    ) -> None:
        self.task_score = task_score
        self.item_score = item_score
        self.task_runtime = task_runtime
        self.task_catalog = task_catalog

    def submit(
        self,
        *,
        task_id: TaskId,
        items: Sequence[TaskItem],
    ) -> TaskCallSubmissionResult:
        invalid = self._validate(task_id=task_id, items=items)
        if invalid is not None:
            return invalid

        try:
            descriptor = self.task_catalog.load_task_allocation_descriptors(
                task_ids=(task_id,),
            ).get(task_id)
            if descriptor is None:
                return self._result(TaskCallSubmissionStatus.NOT_FOUND)
            if (
                descriptor.worker_allocation_mechanism
                is not WorkerAllocationMechanism.DIRECT_ITEM_RULE
                or descriptor.idle_disposition
                is not TaskIdleDisposition.PARK_WHEN_IDLE
            ):
                return self._result(
                    TaskCallSubmissionStatus.INVALID,
                    "Task Call submission requires REUSABLE_DIRECT mechanisms",
                )
            if any(item.allocation_rule is None for item in items):
                return self._result(
                    TaskCallSubmissionStatus.INVALID,
                    "DIRECT_ITEM_RULE TaskItems require allocation_rule",
                )

            state = self.task_score.get_score_states(task_ids=(task_id,)).get(
                task_id
            )
            state_failure = self._validate_running_state(state)
            if state_failure is not None:
                return state_failure
            assert state is not None

            now_millis = self._current_time_millis()
            has_active = self.item_score.has_active_items(
                task_ids=(task_id,),
            ).get(task_id, False)
            if not has_active:
                released = self.task_score.release_observed_idle_task(
                    task_id=task_id,
                    observed_park_score=state.score,
                    release_time_millis=now_millis,
                )
                if released.status is not TaskScoreTransitionStatus.TRANSITIONED:
                    return self._transition_failure(released)

            item_results = self._append(task_id=task_id, items=items)
            try:
                repaired = self._repair_activation(
                    task_id=task_id,
                    release_time_millis=now_millis,
                )
            except Exception:
                repaired = False
            if not repaired:
                return TaskCallSubmissionResult(
                    TaskCallSubmissionStatus.RETRYABLE,
                    item_results,
                    "TaskItems were stored but Task activation was not confirmed",
                )
            return TaskCallSubmissionResult(
                TaskCallSubmissionStatus.SUBMITTED,
                item_results,
            )
        except Exception:
            return self._result(
                TaskCallSubmissionStatus.RETRYABLE,
                "Task Call submission owner is unavailable",
            )

    def _append(
        self,
        *,
        task_id: TaskId,
        items: Sequence[TaskItem],
    ) -> Mapping[MessageId, TaskItemAppendResult]:
        appended = self.task_runtime.append_items(task_id=task_id, items=items)
        return {
            item.message_id: appended.get(
                item.message_id,
                TaskItemAppendResult(
                    TaskItemAppendStatus.RETRYABLE,
                    "Task Runtime omitted the Item result",
                ),
            )
            for item in items
        }

    def _repair_activation(
        self,
        *,
        task_id: TaskId,
        release_time_millis: TimeMillis,
    ) -> bool:
        if not self.item_score.has_active_items(task_ids=(task_id,)).get(
            task_id,
            False,
        ):
            return True
        state = self.task_score.get_score_states(task_ids=(task_id,)).get(task_id)
        if (
            state is None
            or state.band is not TaskScoreBand.RUNNING_VISIBLE
            or state.suffix != TaskScoreBandCore.MIN_SUFFIX
            or state.time_millis is None
        ):
            return False
        if state.time_millis <= release_time_millis:
            return True
        released = self.task_score.release_observed_idle_task(
            task_id=task_id,
            observed_park_score=state.score,
            release_time_millis=release_time_millis,
        )
        return released.status is TaskScoreTransitionStatus.TRANSITIONED

    @classmethod
    def _validate(
        cls,
        *,
        task_id: TaskId,
        items: Sequence[TaskItem],
    ) -> TaskCallSubmissionResult | None:
        if not task_id:
            return cls._result(
                TaskCallSubmissionStatus.INVALID,
                "task_id must be non-empty",
            )
        if not 1 <= len(items) <= cls.MAX_ITEMS:
            return cls._result(
                TaskCallSubmissionStatus.INVALID,
                "Task Call submission requires 1..100 Items",
            )
        message_ids = tuple(item.message_id for item in items)
        if len(set(message_ids)) != len(message_ids):
            return cls._result(
                TaskCallSubmissionStatus.INVALID,
                "Task Call message ids must be unique",
            )
        return None

    @classmethod
    def _validate_running_state(
        cls,
        state: TaskScoreState | None,
    ) -> TaskCallSubmissionResult | None:
        if state is None:
            return cls._result(TaskCallSubmissionStatus.STALE)
        if state.band is TaskScoreBand.TERMINAL:
            return cls._result(TaskCallSubmissionStatus.CLOSED)
        if (
            state.band is not TaskScoreBand.RUNNING_VISIBLE
            or state.suffix != TaskScoreBandCore.MIN_SUFFIX
        ):
            return cls._result(
                TaskCallSubmissionStatus.INVALID,
                "Task is not RUNNING_VISIBLE with suffix 0",
            )
        return None

    @classmethod
    def _transition_failure(
        cls,
        transition: TaskScoreTransitionResult,
    ) -> TaskCallSubmissionResult:
        status = {
            TaskScoreTransitionStatus.STALE: TaskCallSubmissionStatus.STALE,
            TaskScoreTransitionStatus.INVALID: TaskCallSubmissionStatus.INVALID,
            TaskScoreTransitionStatus.NOOP: TaskCallSubmissionStatus.STALE,
        }.get(transition.status, TaskCallSubmissionStatus.RETRYABLE)
        return cls._result(status)

    @staticmethod
    def _result(
        status: TaskCallSubmissionStatus,
        reason: str | None = None,
    ) -> TaskCallSubmissionResult:
        return TaskCallSubmissionResult(status, {}, reason)

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
