from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    DueTaskObservation,
    TaskDescriptor,
    TaskDispatchConfig,
    TaskDispatchPolicy,
    TaskIdleDisposition,
    TaskItemScoreBandCore,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
    WorkerCommandAppendStatus,
    WorkerCommandRuntime,
)
from kernel_design.executable_spec.scheduling import TaskItemDispatcher


class TaskDispatchPolicyTest(unittest.TestCase):
    NOW_MILLIS = 10_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.worker_commands = Mock(spec=WorkerCommandRuntime)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.dispatcher = Mock(spec=TaskItemDispatcher)
        self.policy = TaskDispatchPolicy(
            self.task_score,
            self.worker_commands,
            self.item_score,
            self.dispatcher,
        )
        self.config = TaskDispatchConfig(
            per_task_dispatch_limit=100,
            item_claim_lease_duration_millis=5_000,
        )

    def test_claimable_task_publishes_commands_and_paces_task(self) -> None:
        task = observation("task-1")
        claimable = ((Mock(), 123),)
        command = Mock()
        self.dispatcher.observe_claimable_task_items.return_value = claimable
        self.dispatcher.dispatch_task_items.return_value = {
            "adapter-1": {"worker-1": command},
        }
        self.worker_commands.append_worker_commands.return_value = {
            "worker-1": WorkerCommandAppendStatus.APPENDED,
        }

        with patch.object(
            self.policy,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(
                1,
                self.policy.dispatch_tasks((task,), config=self.config),
            )

        self.dispatcher.dispatch_task_items.assert_called_once_with(
            task_id="task-1",
            descriptor=task.descriptor,
            claimable_items=claimable,
            claim_until_millis=self.NOW_MILLIS + 5_000,
        )
        self.worker_commands.append_worker_commands.assert_called_once_with(
            endpoint_manager_id="adapter-1",
            worker_commands_by_worker_id={"worker-1": command},
        )
        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
        )

    def test_empty_reusable_task_is_parked_and_concurrent_append_releases_it(self) -> None:
        task = observation(
            "task-1",
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
        )
        self.dispatcher.observe_claimable_task_items.return_value = ()
        self.item_score.has_active_items.side_effect = (
            {"task-1": False},
            {"task-1": True},
        )
        self.task_score.park_observed_idle_task.return_value = (
            TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
            )
        )

        with patch.object(
            self.policy,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(
                0,
                self.policy.dispatch_tasks((task,), config=self.config),
            )

        self.task_score.park_observed_idle_task.assert_called_once_with(
            task_id="task-1",
            observed_score=task.score_state.score,
        )
        self.task_score.try_release_idle_park.assert_called_once_with(
            task_id="task-1",
        )
        self.worker_commands.append_worker_commands.assert_not_called()

    def test_empty_close_when_idle_task_is_exactly_closed(self) -> None:
        task = observation(
            "task-close",
            idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
        )
        self.dispatcher.observe_claimable_task_items.return_value = ()
        self.item_score.has_active_items.return_value = {"task-close": False}

        self.policy.dispatch_tasks((task,), config=self.config)

        self.task_score.close_observed_score.assert_called_once_with(
            task_id="task-close",
            observed_score=task.score_state.score,
            terminal_score=TaskScoreBandCore.TERMINAL_SCORE_MAX,
        )
        self.task_score.park_observed_idle_task.assert_not_called()

    def test_empty_batch_does_not_touch_owners(self) -> None:
        self.assertEqual(
            0,
            self.policy.dispatch_tasks((), config=self.config),
        )
        self.dispatcher.observe_claimable_task_items.assert_not_called()
        self.worker_commands.append_worker_commands.assert_not_called()


def observation(
    task_id: str,
    *,
    idle_disposition: TaskIdleDisposition = TaskIdleDisposition.PARK_WHEN_IDLE,
) -> DueTaskObservation:
    descriptor = TaskDescriptor(
        task_id=task_id,
        worker_group_id="group-1",
        worker_allocation_mechanism=WorkerAllocationMechanism.DIRECT_ITEM_RULE,
        idle_disposition=idle_disposition,
        allocation_rule=None,
        config={
            "priority": "0",
            "maximumCandidateWorkers": "1",
            "maxRetryTimes": "1",
        },
    )
    return DueTaskObservation(
        task_id=task_id,
        score_state=TaskScoreState(
            task_id=task_id,
            score=123,
            band=TaskScoreBand.RUNNING_VISIBLE,
            time_millis=9_900,
            suffix=0,
        ),
        descriptor=descriptor,
    )


if __name__ == "__main__":
    unittest.main()
