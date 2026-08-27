from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    DueTaskItemAdmissionPolicy,
    DueTaskObservation,
    RunningSoftLimitSystemAdmissionPolicy,
    SystemAdmissionPolicy,
    TaskAdmissionPolicy,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskItemScoreBandCore,
    TaskRunningActivationConfig,
    TaskRunningActivationPolicy,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
)


class TaskAdmissionPoliciesTest(unittest.TestCase):
    def test_due_item_policy_preserves_input_order(self) -> None:
        item_score = Mock(spec=TaskItemScoreBandCore)
        item_score.has_due_active_items.return_value = {
            "first": True,
            "second": False,
            "third": True,
        }
        policy = DueTaskItemAdmissionPolicy(item_score)

        self.assertEqual(
            ("first", "third"),
            policy.filter_tasks(
                ordered_task_ids=("first", "second", "third"),
                descriptors={
                    task_id: descriptor(task_id)
                    for task_id in ("first", "second", "third")
                },
            ),
        )

    def test_running_soft_limit_applies_to_score_order(self) -> None:
        task_score = Mock(spec=TaskScoreBandCore)
        task_score.count_running_capacity_tasks.return_value = 98
        policy = RunningSoftLimitSystemAdmissionPolicy(
            task_score,
            running_task_soft_limit=100,
        )

        self.assertEqual(
            ("first", "second"),
            policy.select_tasks(
                ordered_task_ids=("first", "second", "third"),
                descriptors={
                    task_id: descriptor(task_id)
                    for task_id in ("first", "second", "third")
                },
            ),
        )


class TaskRunningActivationPolicyTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_policy = Mock(spec=TaskAdmissionPolicy)
        self.system_policy = Mock(spec=SystemAdmissionPolicy)
        self.policy = TaskRunningActivationPolicy(
            self.task_score,
            self.task_policy,
            self.system_policy,
        )
        self.config = TaskRunningActivationConfig(
            priority_recheck_step_millis=1_000,
        )

    def test_policies_activate_selected_task_and_reschedule_other_observation(self) -> None:
        tasks = (
            observation("task-1", priority=10),
            observation("task-2", priority=20),
        )
        self.task_policy.filter_tasks.return_value = ("task-1", "task-2")
        self.system_policy.select_tasks.return_value = ("task-2",)
        self.task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED,
        )

        with patch.object(
            self.policy,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(
                1,
                self.policy.activate_running_visible_tasks(
                    tasks,
                    config=self.config,
                ),
            )

        self.task_policy.filter_tasks.assert_called_once_with(
            ordered_task_ids=("task-1", "task-2"),
            descriptors={task.task_id: task.descriptor for task in tasks},
        )
        self.task_score.rewrite_score.assert_called_once_with(
            task_id="task-2",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
            target_suffix=TaskScoreBandCore.MIN_SUFFIX,
        )
        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_time_millis=self.NOW_MILLIS + 1_100,
        )

    def test_policy_may_not_return_duplicate_or_unobserved_tasks(self) -> None:
        task = observation("task-1", priority=10)
        for invalid in (("task-1", "task-1"), ("other",)):
            with self.subTest(invalid=invalid):
                self.task_policy.reset_mock()
                self.system_policy.reset_mock()
                self.task_score.reset_mock()
                self.task_policy.filter_tasks.return_value = invalid
                with self.assertRaises(ValueError):
                    self.policy.activate_running_visible_tasks(
                        (task,),
                        config=self.config,
                    )
                self.task_score.rewrite_score.assert_not_called()

    def test_empty_batch_does_not_invoke_admission_policies(self) -> None:
        self.assertEqual(
            0,
            self.policy.activate_running_visible_tasks((), config=self.config),
        )
        self.task_policy.filter_tasks.assert_not_called()
        self.system_policy.select_tasks.assert_not_called()

    def test_config_rejects_non_positive_recheck_step(self) -> None:
        with self.assertRaises(ValueError):
            TaskRunningActivationConfig(priority_recheck_step_millis=0)


def observation(task_id: str, *, priority: int) -> DueTaskObservation:
    return DueTaskObservation(
        task_id=task_id,
        score_state=TaskScoreState(
            task_id=task_id,
            score=1,
            band=TaskScoreBand.ADMISSION_VISIBLE,
            time_millis=99_900,
            suffix=priority,
        ),
        descriptor=descriptor(task_id, priority=priority),
    )


def descriptor(task_id: str, *, priority: int = 0) -> TaskDescriptor:
    return TaskDescriptor(
        task_id=task_id,
        worker_group_id="group-1",
        worker_allocation_mechanism=(
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ),
        idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
        allocation_rule={},
        config={
            "priority": str(priority),
            "maximumCandidateWorkers": "10",
            "maxRetryTimes": "3",
        },
    )


if __name__ == "__main__":
    unittest.main()
