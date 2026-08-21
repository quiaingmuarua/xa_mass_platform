from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    DueTaskItemAdmissionPolicy,
    RunningSoftLimitSystemAdmissionPolicy,
    SystemAdmissionPolicy,
    TaskAdmissionPolicy,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskItemScoreBandCore,
    TaskResourceCatalog,
    TaskRunningActivationConfig,
    TaskRunningActivationPacer,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
)
from kernel_design.executable_spec.kernel.assignment_dispatch_runtime import (
    CandidateWarmupSchedule,
)


class TaskRunningAdmissionPolicyTest(unittest.TestCase):
    def test_policy_contracts_are_batch_only(self) -> None:
        self.assertEqual(
            {"self", "ordered_task_ids", "descriptors"},
            set(inspect.signature(TaskAdmissionPolicy.filter_tasks).parameters),
        )
        self.assertEqual(
            {"self", "ordered_task_ids", "descriptors"},
            set(inspect.signature(SystemAdmissionPolicy.select_tasks).parameters),
        )

    def test_due_item_policy_preserves_input_order(self) -> None:
        item_score = Mock(spec=TaskItemScoreBandCore)
        item_score.has_due_active_items.return_value = {
            "task-low": True,
            "task-high": False,
            "task-mid": True,
        }
        policy = DueTaskItemAdmissionPolicy(item_score)
        descriptors = {
            task_id: self.descriptor(task_id, priority=priority)
            for task_id, priority in (
                ("task-low", 90),
                ("task-high", 0),
                ("task-mid", 50),
            )
        }

        selected = policy.filter_tasks(
            ordered_task_ids=("task-low", "task-high", "task-mid"),
            descriptors=descriptors,
        )

        self.assertEqual(("task-low", "task-mid"), selected)
        item_score.has_due_active_items.assert_called_once_with(
            task_ids=("task-low", "task-high", "task-mid"),
        )

    def test_system_policy_applies_soft_limit_to_score_order(self) -> None:
        task_score = Mock(spec=TaskScoreBandCore)
        task_score.count_running_capacity_tasks.return_value = 98
        policy = RunningSoftLimitSystemAdmissionPolicy(
            task_score,
            running_task_soft_limit=100,
        )
        descriptors = {
            "task-score-first": self.descriptor("task-score-first", priority=90),
            "task-score-second": self.descriptor("task-score-second", priority=0),
            "task-score-third": self.descriptor("task-score-third", priority=50),
        }

        selected = policy.select_tasks(
            ordered_task_ids=("task-score-first", "task-score-second", "task-score-third"),
            descriptors=descriptors,
        )

        self.assertEqual(("task-score-first", "task-score-second"), selected)

    def test_system_policy_returns_empty_when_soft_limit_is_full(self) -> None:
        task_score = Mock(spec=TaskScoreBandCore)
        task_score.count_running_capacity_tasks.return_value = 100
        policy = RunningSoftLimitSystemAdmissionPolicy(
            task_score,
            running_task_soft_limit=100,
        )

        self.assertEqual(
            (),
            policy.select_tasks(
                ordered_task_ids=("task-1",),
                descriptors={"task-1": self.descriptor("task-1", priority=0)},
            ),
        )

    @staticmethod
    def descriptor(task_id: str, *, priority: int) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id="workers",
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


class TaskRunningActivationPacerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.task_policy = Mock(spec=TaskAdmissionPolicy)
        self.system_policy = Mock(spec=SystemAdmissionPolicy)
        self.warmup_schedule = Mock(spec=CandidateWarmupSchedule)
        self.pacer = TaskRunningActivationPacer(
            self.task_score,
            self.task_catalog,
            self.task_policy,
            self.system_policy,
            self.warmup_schedule,
        )
        self.config = TaskRunningActivationConfig(task_batch_limit=10)
        self.observed_priorities: dict[str, int] = {}
        self.task_score.get_score_states.side_effect = self._score_states

    def activate(self) -> int:
        with patch.object(
            TaskRunningActivationPacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            return self.pacer.activate_running_visible_tasks(config=self.config)

    def _score_states(self, *, task_ids: tuple[str, ...]) -> dict[str, TaskScoreState]:
        return {
            task_id: TaskScoreState(
                task_id=task_id,
                score=1,
                band=TaskScoreBand.ADMISSION_VISIBLE,
                time_millis=self.NOW_MILLIS - TaskScoreBandCore.SLOT_MILLIS,
                suffix=self.observed_priorities.get(task_id, 0),
            )
            for task_id in task_ids
        }

    def test_round_contract_only_emits_derived_candidate_warmup_hints(self) -> None:
        self.assertEqual(
            [
                "self",
                "task_score",
                "task_catalog",
                "task_admission_policy",
                "system_admission_policy",
                "candidate_warmup_schedule",
            ],
            list(inspect.signature(TaskRunningActivationPacer.__init__).parameters),
        )
        self.assertEqual(
            {"self", "config"},
            set(
                inspect.signature(
                    TaskRunningActivationPacer.activate_running_visible_tasks
                ).parameters
            ),
        )

    def test_task_then_system_policy_controls_kernel_transition(self) -> None:
        descriptors = {
            "task-1": self.descriptor("task-1", 10),
            "task-2": self.descriptor("task-2", 0),
        }
        self.task_score.acquire_band_task_candidates.return_value = (
            "task-1",
            "task-2",
        )
        self.observed_priorities = {"task-1": 10, "task-2": 0}
        self.task_catalog.load_task_allocation_descriptors.return_value = descriptors
        self.task_policy.filter_tasks.return_value = ("task-1", "task-2")
        self.system_policy.select_tasks.return_value = ("task-2",)
        self.task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED
        )

        self.assertEqual(1, self.activate())

        self.task_score.acquire_band_task_candidates.assert_called_once_with(
            band=TaskScoreBand.ADMISSION_VISIBLE,
            before_time_millis=self.NOW_MILLIS,
            limit=10,
        )
        self.task_policy.filter_tasks.assert_called_once_with(
            ordered_task_ids=("task-1", "task-2"),
            descriptors=descriptors,
        )
        self.system_policy.select_tasks.assert_called_once_with(
            ordered_task_ids=("task-1", "task-2"),
            descriptors=descriptors,
        )
        self.task_score.rewrite_score.assert_called_once_with(
            task_id="task-2",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
            target_suffix=TaskScoreBandCore.MIN_SUFFIX,
        )
        self.warmup_schedule.schedule_candidate_warmups.assert_called_once_with(
            task_ids=("task-2",),
            due_time_millis=self.NOW_MILLIS,
        )
        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_time_millis=(
                self.NOW_MILLIS + TaskScoreBandCore.SLOT_MILLIS + 1_000
            ),
        )

    def test_missing_descriptor_and_task_policy_rejection_do_not_transition(self) -> None:
        descriptor = self.descriptor("task-1", 10)
        self.task_score.acquire_band_task_candidates.return_value = (
            "task-1",
            "task-missing",
        )
        self.observed_priorities = {"task-1": 10, "task-missing": 99}
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": descriptor,
            "task-missing": None,
        }
        self.task_policy.filter_tasks.return_value = ()
        self.system_policy.select_tasks.return_value = ()

        self.assertEqual(0, self.activate())

        self.task_policy.filter_tasks.assert_called_once_with(
            ordered_task_ids=("task-1",),
            descriptors={"task-1": descriptor},
        )
        self.system_policy.select_tasks.assert_not_called()
        self.task_score.rewrite_score.assert_not_called()
        self.warmup_schedule.schedule_candidate_warmups.assert_not_called()
        self.assertEqual(
            [
                ("task-1", self.NOW_MILLIS + 1_100),
                ("task-missing", self.NOW_MILLIS + 9_100),
            ],
            [
                (
                    call.kwargs["task_id"],
                    call.kwargs["target_time_millis"],
                )
                for call in self.task_score.rewrite_same_band_time_millis.call_args_list
            ],
        )

    def test_policy_may_not_return_duplicate_or_unobserved_tasks(self) -> None:
        descriptor = self.descriptor("task-1", 10)
        self.task_score.acquire_band_task_candidates.return_value = ("task-1",)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": descriptor
        }

        for invalid_output, error in (
            (("task-1", "task-1"), "duplicate"),
            (("task-other",), "unobserved"),
        ):
            with self.subTest(invalid_output=invalid_output):
                self.task_policy.filter_tasks.return_value = invalid_output
                with self.assertRaisesRegex(ValueError, error):
                    self.activate()
                self.task_score.rewrite_score.assert_not_called()
                self.task_score.rewrite_same_band_time_millis.assert_not_called()

    def test_system_policy_may_not_return_duplicate_or_unobserved_tasks(self) -> None:
        descriptor = self.descriptor("task-1", 10)
        self.task_score.acquire_band_task_candidates.return_value = ("task-1",)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": descriptor
        }
        self.task_policy.filter_tasks.return_value = ("task-1",)

        for invalid_output, error in (
            (("task-1", "task-1"), "duplicate"),
            (("task-other",), "unobserved"),
        ):
            with self.subTest(invalid_output=invalid_output):
                self.system_policy.select_tasks.return_value = invalid_output
                with self.assertRaisesRegex(ValueError, error):
                    self.activate()
                self.task_score.rewrite_score.assert_not_called()
                self.task_score.rewrite_same_band_time_millis.assert_not_called()

    def test_only_transitioned_score_writes_are_counted(self) -> None:
        descriptors = {
            "task-1": self.descriptor("task-1", 10),
            "task-2": self.descriptor("task-2", 20),
        }
        self.task_score.acquire_band_task_candidates.return_value = tuple(descriptors)
        self.observed_priorities = {"task-1": 10, "task-2": 20}
        self.task_catalog.load_task_allocation_descriptors.return_value = descriptors
        self.task_policy.filter_tasks.return_value = tuple(descriptors)
        self.system_policy.select_tasks.return_value = tuple(descriptors)
        self.task_score.rewrite_score.side_effect = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.TRANSITIONED),
            TaskScoreTransitionResult(TaskScoreTransitionStatus.STALE),
        )

        self.assertEqual(1, self.activate())
        self.assertEqual(
            ["task-1", "task-2"],
            [entry.kwargs["task_id"] for entry in self.task_score.rewrite_score.call_args_list],
        )
        self.warmup_schedule.schedule_candidate_warmups.assert_called_once_with(
            task_ids=("task-1",),
            due_time_millis=self.NOW_MILLIS,
        )
        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-2",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_time_millis=self.NOW_MILLIS + 2_100,
        )

    def test_recheck_delay_uses_ten_monotonic_priority_buckets(self) -> None:
        task_ids = ("p0", "p9", "p10", "p19", "p99")
        self.task_score.acquire_band_task_candidates.return_value = task_ids
        self.observed_priorities = {
            "p0": 0,
            "p9": 9,
            "p10": 10,
            "p19": 19,
            "p99": 99,
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: None for task_id in task_ids
        }

        self.assertEqual(0, self.activate())

        self.assertEqual(
            [
                self.NOW_MILLIS + 100,
                self.NOW_MILLIS + 100,
                self.NOW_MILLIS + 1_100,
                self.NOW_MILLIS + 1_100,
                self.NOW_MILLIS + 9_100,
            ],
            [
                call.kwargs["target_time_millis"]
                for call in self.task_score.rewrite_same_band_time_millis.call_args_list
            ],
        )

    def test_config_rejects_non_positive_priority_recheck_step(self) -> None:
        for value in (0, -1):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "priority recheck step"):
                    TaskRunningActivationConfig(
                        task_batch_limit=1,
                        priority_recheck_step_millis=value,
                    )

    def test_direct_allocation_activation_does_not_schedule_candidate_warmup(self) -> None:
        descriptor = TaskDescriptor(
            task_id="item-task",
            worker_group_id="workers",
            worker_allocation_mechanism=(
                WorkerAllocationMechanism.DIRECT_ITEM_RULE
            ),
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
            allocation_rule=None,
            config={
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        )
        self.task_score.acquire_band_task_candidates.return_value = ("item-task",)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "item-task": descriptor
        }
        self.task_policy.filter_tasks.return_value = ("item-task",)
        self.system_policy.select_tasks.return_value = ("item-task",)
        self.task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED
        )

        self.assertEqual(1, self.activate())

        self.warmup_schedule.schedule_candidate_warmups.assert_not_called()

    @staticmethod
    def descriptor(task_id: str, priority: int) -> TaskDescriptor:
        return TaskRunningAdmissionPolicyTest.descriptor(
            task_id,
            priority=priority,
        )


if __name__ == "__main__":
    unittest.main()
