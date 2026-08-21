from __future__ import annotations

import unittest
from unittest.mock import Mock, call

from kernel_design.executable_spec import (
    TaskCallItemSubmission,
    TaskCallSubmissionStatus,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskRuntime,
    TaskScoreBandCore,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
)


class TaskCallItemSubmissionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.submission = TaskCallItemSubmission(
            self.task_score,
            self.task_runtime,
        )
        self.task_score.try_release_idle_park.return_value = self._transition(
            TaskScoreTransitionStatus.NOOP,
            100,
        )
        self.task_runtime.append_items.return_value = {
            "message-1": TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
        }

    def test_normal_score_uses_noop_append_noop(self) -> None:
        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.assertEqual(
            [call(task_id="task-1"), call(task_id="task-1")],
            self.task_score.try_release_idle_park.call_args_list,
        )
        self.task_runtime.append_items.assert_called_once_with(
            task_id="task-1",
            items=(self._item(),),
        )

    def test_existing_idle_park_is_released_before_append(self) -> None:
        self.task_score.try_release_idle_park.side_effect = (
            self._transition(TaskScoreTransitionStatus.TRANSITIONED, 100),
            self._transition(TaskScoreTransitionStatus.NOOP, 100),
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.assertEqual(2, self.task_score.try_release_idle_park.call_count)
        self.task_runtime.append_items.assert_called_once()

    def test_second_release_repairs_park_created_during_append(self) -> None:
        self.task_score.try_release_idle_park.side_effect = (
            self._transition(TaskScoreTransitionStatus.NOOP, 100),
            self._transition(TaskScoreTransitionStatus.TRANSITIONED, 101),
        )

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.assertEqual(2, self.task_score.try_release_idle_park.call_count)

    def test_first_release_failure_does_not_append(self) -> None:
        expected = {
            TaskScoreTransitionStatus.STALE: TaskCallSubmissionStatus.STALE,
            TaskScoreTransitionStatus.INVALID: TaskCallSubmissionStatus.INVALID,
        }
        for transition_status, submission_status in expected.items():
            with self.subTest(status=transition_status):
                self.task_score.reset_mock()
                self.task_runtime.reset_mock()
                self.task_score.try_release_idle_park.return_value = (
                    self._transition(transition_status)
                )

                result = self._submit()

                self.assertEqual(submission_status, result.status)
                self.task_runtime.append_items.assert_not_called()

    def test_first_release_provider_failure_is_retryable_without_append(self) -> None:
        self.task_score.try_release_idle_park.side_effect = RuntimeError("down")

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.RETRYABLE, result.status)
        self.assertEqual({}, result.item_results)
        self.task_runtime.append_items.assert_not_called()

    def test_second_release_failure_preserves_item_results(self) -> None:
        for failure in (
            self._transition(TaskScoreTransitionStatus.STALE),
            self._transition(TaskScoreTransitionStatus.INVALID),
            RuntimeError("down"),
        ):
            with self.subTest(failure=failure):
                self.task_score.reset_mock()
                self.task_runtime.reset_mock()
                self.task_runtime.append_items.return_value = {
                    "message-1": TaskItemAppendResult(
                        TaskItemAppendStatus.APPENDED
                    )
                }
                self.task_score.try_release_idle_park.side_effect = (
                    self._transition(TaskScoreTransitionStatus.NOOP, 100),
                    failure,
                )

                result = self._submit()

                self.assertEqual(TaskCallSubmissionStatus.RETRYABLE, result.status)
                self.assertEqual(
                    TaskItemAppendStatus.APPENDED,
                    result.item_results["message-1"].status,
                )

    def test_append_provider_failure_is_retryable(self) -> None:
        self.task_runtime.append_items.side_effect = RuntimeError("down")

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.RETRYABLE, result.status)
        self.assertEqual({}, result.item_results)
        self.assertEqual(1, self.task_score.try_release_idle_park.call_count)

    def test_omitted_item_result_is_preserved_as_retryable_item_result(self) -> None:
        self.task_runtime.append_items.return_value = {}

        result = self._submit()

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.assertEqual(
            TaskItemAppendStatus.RETRYABLE,
            result.item_results["message-1"].status,
        )

    def test_submission_does_not_interpret_item_allocation_mechanism(self) -> None:
        result = self.submission.submit(
            task_id="task-1",
            items=(self._item(allocation_rule=None),),
        )

        self.assertEqual(TaskCallSubmissionStatus.SUBMITTED, result.status)
        self.task_runtime.append_items.assert_called_once()

    def test_invalid_input_does_not_touch_score_or_runtime(self) -> None:
        invalid_results = (
            self.submission.submit(task_id="", items=(self._item(),)),
            self.submission.submit(task_id="task-1", items=()),
            self.submission.submit(
                task_id="task-1",
                items=(self._item(), self._item()),
            ),
            self.submission.submit(
                task_id="task-1",
                items=tuple(
                    self._item(message_id=f"message-{index}")
                    for index in range(101)
                ),
            ),
        )

        self.assertTrue(
            all(
                result.status is TaskCallSubmissionStatus.INVALID
                for result in invalid_results
            )
        )
        self.task_score.try_release_idle_park.assert_not_called()
        self.task_runtime.append_items.assert_not_called()

    def _submit(self):
        return self.submission.submit(
            task_id="task-1",
            items=(self._item(),),
        )

    @staticmethod
    def _item(
        *,
        message_id: str = "message-1",
        allocation_rule: dict[str, object] | None = None,
    ) -> TaskItem:
        return TaskItem(
            message_id=message_id,
            event_code="image.resize",
            created_at_millis=1,
            payload={},
            allocation_rule=allocation_rule,
        )

    @staticmethod
    def _transition(
        status: TaskScoreTransitionStatus,
        score: int | None = None,
    ) -> TaskScoreTransitionResult:
        return TaskScoreTransitionResult(status, score)


if __name__ == "__main__":
    unittest.main()
