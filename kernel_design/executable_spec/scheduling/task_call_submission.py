from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum

from ..kernel.task_runtime import (
    MessageId,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskRuntime,
)
from ..kernel.task_score_band import (
    TaskId,
    TaskScoreBandCore,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
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
    """Kernel application command for scheduling-aware bounded Item append.

    The command composes the Task score and Task Runtime owners without making
    their writes transactional. The same idempotent idle-park release before
    and after append closes the ordinary Dispatch/append liveness window.
    """

    MAX_ITEMS = 100

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_runtime: TaskRuntime,
    ) -> None:
        self.task_score = task_score
        self.task_runtime = task_runtime

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
            before = self.task_score.try_release_idle_park(task_id=task_id)
        except Exception:
            return self._result(
                TaskCallSubmissionStatus.RETRYABLE,
                "Task Call submission owner is unavailable",
            )
        if before.status not in {
            TaskScoreTransitionStatus.TRANSITIONED,
            TaskScoreTransitionStatus.NOOP,
        }:
            return self._transition_failure(before)

        try:
            item_results = self._append(task_id=task_id, items=items)
        except Exception:
            return self._result(
                TaskCallSubmissionStatus.RETRYABLE,
                "Task Call submission owner is unavailable",
            )

        try:
            after = self.task_score.try_release_idle_park(task_id=task_id)
        except Exception:
            after = None
        if after is None or after.status not in {
            TaskScoreTransitionStatus.TRANSITIONED,
            TaskScoreTransitionStatus.NOOP,
        }:
            return TaskCallSubmissionResult(
                TaskCallSubmissionStatus.RETRYABLE,
                item_results,
                "TaskItems were stored but Task activation was not confirmed",
            )
        return TaskCallSubmissionResult(
            TaskCallSubmissionStatus.SUBMITTED,
            item_results,
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
