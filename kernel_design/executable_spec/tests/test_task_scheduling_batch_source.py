from __future__ import annotations

import unittest
from unittest.mock import Mock

from kernel_design.executable_spec import (
    TaskDescriptor,
    TaskIdleDisposition,
    TaskSchedulingBatchSource,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    WorkerAllocationMechanism,
)
from kernel_design.executable_spec.kernel import TaskResourceCatalog


class TaskSchedulingBatchSourceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.source = TaskSchedulingBatchSource(
            self.task_score,
            self.task_catalog,
            clock_millis=lambda: 20_000,
        )

    def test_normal_and_initial_share_one_reload_and_remain_separated(self) -> None:
        normal_ids = ("normal", "future", "missing")
        initial_ids = ("initial", "wrong-initial")
        all_ids = normal_ids + initial_ids
        self.task_score.acquire_dispatch_work_tasks.return_value = normal_ids
        self.task_score.acquire_initial_running_tasks.return_value = initial_ids
        self.task_score.get_score_states.return_value = {
            "normal": state("normal", 19_900),
            "future": state("future", 20_000),
            "missing": state("missing", 19_800),
            "initial": state("initial", 10_000),
            "wrong-initial": state("wrong-initial", 10_100),
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: descriptor(task_id)
            for task_id in ("normal", "future", "initial", "wrong-initial")
        }

        batch = self.source.acquire_tasks(
            limit=100,
            include_normal=True,
            include_initial=True,
        )

        self.assertEqual(("normal",), ids(batch.normal_tasks))
        self.assertEqual(("initial",), ids(batch.initial_tasks))
        self.task_score.acquire_dispatch_work_tasks.assert_called_once_with(
            limit=100,
        )
        self.task_score.acquire_initial_running_tasks.assert_called_once_with(
            limit=97,
        )
        self.task_score.get_score_states.assert_called_once_with(
            task_ids=all_ids,
        )
        self.task_catalog.load_task_allocation_descriptors.assert_called_once_with(
            task_ids=all_ids,
        )

    def test_normal_page_uses_the_entire_batch_before_initial(self) -> None:
        task_ids = tuple(f"task-{index}" for index in range(100))
        self.task_score.acquire_dispatch_work_tasks.return_value = task_ids
        self.task_score.get_score_states.return_value = {
            task_id: state(task_id, 19_900)
            for task_id in task_ids
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: descriptor(task_id)
            for task_id in task_ids
        }

        batch = self.source.acquire_tasks(
            limit=100,
            include_normal=True,
            include_initial=True,
        )

        self.assertEqual(task_ids, ids(batch.normal_tasks))
        self.assertEqual((), batch.initial_tasks)
        self.task_score.acquire_initial_running_tasks.assert_not_called()

    def test_empty_pages_skip_state_and_descriptor_owners(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = ()
        self.task_score.acquire_initial_running_tasks.return_value = ()

        batch = self.source.acquire_tasks(
            limit=100,
            include_normal=True,
            include_initial=True,
        )

        self.assertEqual((), batch.normal_tasks)
        self.assertEqual((), batch.initial_tasks)
        self.task_score.get_score_states.assert_not_called()
        self.task_catalog.load_task_allocation_descriptors.assert_not_called()


def ids(observations) -> tuple[str, ...]:
    return tuple(observation.task_id for observation in observations)


def state(task_id: str, time_millis: int) -> TaskScoreState:
    return TaskScoreState(
        task_id=task_id,
        score=1,
        band=TaskScoreBand.RUNNING_VISIBLE,
        time_millis=time_millis,
        suffix=0,
    )


def descriptor(task_id: str) -> TaskDescriptor:
    return TaskDescriptor(
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
    )


if __name__ == "__main__":
    unittest.main()
