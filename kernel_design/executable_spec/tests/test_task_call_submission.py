from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    TaskCallItemSubmission,
    TaskCallSubmissionStatus,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskItemScoreBandCore,
    TaskResourceCatalog,
    TaskRuntime,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
)


class TaskCallItemSubmissionTest(unittest.TestCase):
    NOW = 10_000
    PARK_TIME = 20_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.submission = TaskCallItemSubmission(
            self.task_score,
            self.item_score,
            self.task_runtime,
            self.task_catalog,
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor()
        }
        self.task_runtime.append_items.return_value = {
            "message-1": TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
        }

    def test_active_queue_appends_and_keeps_due_task(self) -> None:
        self.task_score.get_score_states.return_value = {
            "task-1": self._state(score=100, time_millis=9_000)
        }
        self.item_score.has_active_items.return_value = {"task-1": True}

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.task_score.release_observed_idle_task.assert_not_called()
        self.task_score.close_observed_score.assert_not_called()

    def test_empty_idle_park_is_released_before_append(self) -> None:
        self.task_score.get_score_states.side_effect = (
            {"task-1": self._state(score=200, time_millis=self.PARK_TIME)},
            {"task-1": self._state(score=100, time_millis=self.NOW)},
        )
        self.item_score.has_active_items.side_effect = (
            {"task-1": False},
            {"task-1": True},
        )
        self.task_score.release_observed_idle_task.return_value = (
            TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                100,
            )
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.task_score.release_observed_idle_task.assert_called_once_with(
            task_id="task-1",
            observed_park_score=200,
            release_time_millis=self.NOW,
        )
        self.task_runtime.append_items.assert_called_once()

    def test_empty_task_must_be_in_exact_idle_park(self) -> None:
        self.task_score.get_score_states.return_value = {
            "task-1": self._state(score=100, time_millis=9_000)
        }
        self.item_score.has_active_items.return_value = {"task-1": False}
        self.task_score.release_observed_idle_task.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.INVALID, result.status)
        self.task_runtime.append_items.assert_not_called()

    def test_stale_idle_park_release_does_not_append(self) -> None:
        self.task_score.get_score_states.return_value = {
            "task-1": self._state(score=200, time_millis=self.PARK_TIME)
        }
        self.item_score.has_active_items.return_value = {"task-1": False}
        self.task_score.release_observed_idle_task.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.STALE)
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.STALE, result.status)
        self.task_runtime.append_items.assert_not_called()

    def test_post_append_repair_releases_concurrent_idle_park(self) -> None:
        self.task_score.get_score_states.side_effect = (
            {"task-1": self._state(score=100, time_millis=9_000)},
            {"task-1": self._state(score=200, time_millis=self.PARK_TIME)},
        )
        self.item_score.has_active_items.return_value = {"task-1": True}
        self.task_score.release_observed_idle_task.return_value = (
            TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                100,
            )
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.task_score.release_observed_idle_task.assert_called_once_with(
            task_id="task-1",
            observed_park_score=200,
            release_time_millis=self.NOW,
        )

    def test_failed_post_append_repair_reports_retryable_with_item_results(
        self,
    ) -> None:
        self.task_score.get_score_states.side_effect = (
            {"task-1": self._state(score=100, time_millis=9_000)},
            {"task-1": self._state(score=200, time_millis=self.PARK_TIME)},
        )
        self.item_score.has_active_items.return_value = {"task-1": True}
        self.task_score.release_observed_idle_task.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.STALE)
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.RETRYABLE, result.status)
        self.assertEqual(
            TaskItemAppendStatus.APPENDED,
            result.item_results["message-1"].status,
        )

    def test_non_item_driven_task_and_duplicate_message_ids_are_invalid(
        self,
    ) -> None:
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor(
                allocation_mechanism=(
                    WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                ),
                idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
            )
        }

        task_type_result = self._submit()
        duplicate_result = self.submission.submit(
            task_id="task-1",
            items=(self._item(), self._item()),
        )

        self.assertEqual(TaskCallSubmissionStatus.INVALID, task_type_result.status)
        self.assertEqual(TaskCallSubmissionStatus.INVALID, duplicate_result.status)
        self.task_runtime.append_items.assert_not_called()

    def _submit(self):
        with patch.object(
            self.submission,
            "_current_time_millis",
            return_value=self.NOW,
        ):
            return self.submission.submit(
                task_id="task-1",
                items=(self._item(),),
            )

    @staticmethod
    def _item() -> TaskItem:
        return TaskItem(
            message_id="message-1",
            event_code="image.resize",
            created_at_millis=1,
            payload={},
            allocation_rule={},
        )

    @classmethod
    def _descriptor(
        cls,
        *,
        allocation_mechanism: WorkerAllocationMechanism = (
            WorkerAllocationMechanism.DIRECT_ITEM_RULE
        ),
        idle_disposition: TaskIdleDisposition = (
            TaskIdleDisposition.PARK_WHEN_IDLE
        ),
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id="task-1",
            worker_group_id="group-1",
            worker_allocation_mechanism=allocation_mechanism,
            idle_disposition=idle_disposition,
            allocation_rule=(
                {}
                if allocation_mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else None
            ),
            config={
                "priority": "0",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "3",
            },
        )

    @staticmethod
    def _state(*, score: int, time_millis: int) -> TaskScoreState:
        return TaskScoreState(
            task_id="task-1",
            score=score,
            band=TaskScoreBand.RUNNING_VISIBLE,
            time_millis=time_millis,
            suffix=0,
        )


if __name__ == "__main__":
    unittest.main()
