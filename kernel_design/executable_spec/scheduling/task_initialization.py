from __future__ import annotations

from collections.abc import Sequence

from ..kernel.task_item_score_band import TaskItemScoreBandCore
from ..kernel.task_score_band import (
    TaskScoreBandCore,
    TaskScoreTransitionStatus,
)
from .task_scheduling_batch_source import DueTaskObservation


class TaskInitializationPolicy:
    """Promote exact INITIAL Tasks only after a due ACTIVE Item exists."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        item_score: TaskItemScoreBandCore,
    ) -> None:
        self.task_score = task_score
        self.item_score = item_score

    def initialize_tasks(
        self,
        tasks: Sequence[DueTaskObservation],
    ) -> int:
        task_ids = tuple(task.task_id for task in tasks)
        if not task_ids:
            return 0
        due = self.item_score.has_due_active_items(task_ids=task_ids)
        initialized = 0
        for task in tasks:
            if not due.get(task.task_id, False):
                continue
            result = self.task_score.promote_observed_initial_task(
                task_id=task.task_id,
                observed_initial_score=task.score_state.score,
            )
            if result.status is TaskScoreTransitionStatus.TRANSITIONED:
                initialized += 1
        return initialized
