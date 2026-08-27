from __future__ import annotations

import unittest
from unittest.mock import Mock

from kernel_design.executable_spec import (
    DueTaskObservation,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskInitializationPolicy,
    TaskItemScoreBandCore,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
)


class TaskInitializationPolicyTest(unittest.TestCase):
    def test_only_due_initial_tasks_are_promoted_by_exact_observation(self) -> None:
        task_score = Mock(spec=TaskScoreBandCore)
        item_score = Mock(spec=TaskItemScoreBandCore)
        tasks = (observation("task-1", 10_000), observation("task-2", 9_900))
        item_score.has_due_active_items.return_value = {
            "task-1": True,
            "task-2": False,
        }
        task_score.promote_observed_initial_task.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.TRANSITIONED)
        )
        policy = TaskInitializationPolicy(task_score, item_score)

        self.assertEqual(1, policy.initialize_tasks(tasks))

        item_score.has_due_active_items.assert_called_once_with(
            task_ids=("task-1", "task-2"),
        )
        task_score.promote_observed_initial_task.assert_called_once_with(
            task_id="task-1",
            observed_initial_score=1,
        )

    def test_empty_batch_does_not_read_item_owner(self) -> None:
        task_score = Mock(spec=TaskScoreBandCore)
        item_score = Mock(spec=TaskItemScoreBandCore)

        self.assertEqual(
            0,
            TaskInitializationPolicy(task_score, item_score).initialize_tasks(()),
        )
        item_score.has_due_active_items.assert_not_called()


def observation(task_id: str, time_millis: int) -> DueTaskObservation:
    return DueTaskObservation(
        task_id=task_id,
        score_state=TaskScoreState(
            task_id=task_id,
            score=1,
            band=TaskScoreBand.RUNNING_VISIBLE,
            time_millis=time_millis,
            suffix=0,
        ),
        descriptor=TaskDescriptor(
            task_id=task_id,
            worker_group_id="group-1",
            worker_allocation_mechanism=WorkerAllocationMechanism.DIRECT_ITEM_RULE,
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
            allocation_rule=None,
            config={
                "priority": "0",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "1",
            },
        ),
    )


if __name__ == "__main__":
    unittest.main()
