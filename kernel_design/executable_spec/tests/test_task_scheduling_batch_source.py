from __future__ import annotations

import unittest
from unittest.mock import Mock

from kernel_design.executable_spec import (
    DueTaskObservation,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskResourceCatalog,
    TaskSchedulingBatchSource,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    WorkerAllocationMechanism,
)


class TaskSchedulingBatchSourceTest(unittest.TestCase):
    NOW_MILLIS = 10_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.source = TaskSchedulingBatchSource(
            self.task_score,
            self.task_catalog,
            clock_millis=lambda: self.NOW_MILLIS,
        )

    def test_running_source_reads_once_and_preserves_score_order(self) -> None:
        task_ids = (
            "task-first",
            "future",
            "wrong-band",
            "nonzero-suffix",
            "missing-descriptor",
            "task-last",
        )
        self.task_score.acquire_dispatch_work_tasks.return_value = task_ids
        self.task_score.get_score_states.return_value = {
            "task-first": self._state("task-first"),
            "future": self._state("future", time_millis=self.NOW_MILLIS),
            "wrong-band": self._state(
                "wrong-band",
                band=TaskScoreBand.ADMISSION_VISIBLE,
            ),
            "nonzero-suffix": self._state("nonzero-suffix", suffix=1),
            "missing-descriptor": self._state("missing-descriptor"),
            "task-last": self._state("task-last"),
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._descriptor(task_id)
            for task_id in task_ids
            if task_id != "missing-descriptor"
        }

        observations = self.source.acquire_running_tasks(limit=100)

        self.assertEqual(
            ("task-first", "task-last"),
            tuple(observation.task_id for observation in observations),
        )
        self.task_score.acquire_dispatch_work_tasks.assert_called_once_with(
            limit=100,
        )
        self.task_score.get_score_states.assert_called_once_with(
            task_ids=task_ids,
        )
        self.task_catalog.load_task_allocation_descriptors.assert_called_once_with(
            task_ids=task_ids,
        )

    def test_admission_source_accepts_priority_suffix_and_is_bounded(self) -> None:
        self.task_score.acquire_band_task_candidates.return_value = ("task-1",)
        state = self._state(
            "task-1",
            band=TaskScoreBand.ADMISSION_VISIBLE,
            suffix=90,
        )
        descriptor = self._descriptor("task-1")
        self.task_score.get_score_states.return_value = {"task-1": state}
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": descriptor,
        }

        self.assertEqual(
            (DueTaskObservation("task-1", state, descriptor),),
            self.source.acquire_admission_tasks(limit=100),
        )
        self.task_score.acquire_band_task_candidates.assert_called_once_with(
            band=TaskScoreBand.ADMISSION_VISIBLE,
            before_time_millis=self.NOW_MILLIS,
            limit=100,
        )

    def test_empty_owner_page_does_not_read_states_or_descriptors(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = ()

        self.assertEqual((), self.source.acquire_running_tasks(limit=100))

        self.task_score.get_score_states.assert_not_called()
        self.task_catalog.load_task_allocation_descriptors.assert_not_called()

    def test_complete_running_page_returns_every_supported_task(self) -> None:
        task_ids = tuple(f"task-{index}" for index in range(100))
        self.task_score.acquire_dispatch_work_tasks.return_value = task_ids
        self.task_score.get_score_states.return_value = {
            task_id: self._state(task_id) for task_id in task_ids
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._descriptor(task_id) for task_id in task_ids
        }

        observations = self.source.acquire_running_tasks(limit=100)

        self.assertEqual(
            task_ids,
            tuple(observation.task_id for observation in observations),
        )
        self.assertEqual(100, len(observations))

    @classmethod
    def _state(
        cls,
        task_id: str,
        *,
        band: TaskScoreBand = TaskScoreBand.RUNNING_VISIBLE,
        time_millis: int | None = None,
        suffix: int | None = 0,
    ) -> TaskScoreState:
        return TaskScoreState(
            task_id=task_id,
            score=1,
            band=band,
            time_millis=(
                cls.NOW_MILLIS - TaskScoreBandCore.SLOT_MILLIS
                if time_millis is None
                else time_millis
            ),
            suffix=suffix,
        )

    @staticmethod
    def _descriptor(task_id: str) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id="group-1",
            worker_allocation_mechanism=(
                WorkerAllocationMechanism.DIRECT_ITEM_RULE
            ),
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
