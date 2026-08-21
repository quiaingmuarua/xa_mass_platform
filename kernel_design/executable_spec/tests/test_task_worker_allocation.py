from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskResourceCatalog,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    WorkerAllocationMechanism,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
)
from kernel_design.executable_spec.kernel.assignment_dispatch_runtime import (
    CandidateWarmupSchedule,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
)


class TaskWorkerAllocationPacerTest(unittest.TestCase):
    NOW_MILLIS = 10_000

    def setUp(self) -> None:
        self.warmup_schedule = Mock(spec=CandidateWarmupSchedule)
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_score.get_score_states.side_effect = self._running_states
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.candidate_acquirer = Mock(spec=WorkerCandidateAcquirer)
        self.candidate_cache = Mock(spec=CandidateWorkerCache)
        self.pacer = TaskWorkerAllocationPacer(
            self.warmup_schedule,
            self.task_score,
            self.task_catalog,
            self.candidate_acquirer,
            self.candidate_cache,
        )

    def test_contract_uses_derived_schedule_without_task_score_writes(self) -> None:
        self.assertEqual(
            {
                "candidate_warmup_schedule",
                "task_score",
                "task_catalog",
                "candidate_acquirer",
                "candidate_cache",
            },
            set(inspect.signature(TaskWorkerAllocationPacer).parameters),
        )
        self.assertEqual(
            {
                "schedule_candidate_warmups",
                "consume_due_candidate_warmups",
            },
            CandidateWarmupSchedule.__abstractmethods__,
        )

    def test_warms_deficit_and_requeues_only_incomplete_task(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = (
            "task-1",
            "task-2",
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", maximum_candidates=3),
            "task-2": self._descriptor("task-2", maximum_candidates=5),
        }
        self.candidate_cache.candidate_worker_counts.return_value = {
            "task-1": 1,
            "task-2": 5,
        }
        entry = self._entry("worker-1")
        self.candidate_acquirer.acquire_hot_pool_candidates.return_value = {
            "task-1": (entry,),
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            published = self.pacer.allocate_candidate_workers(config=self._config())

        self.assertEqual(1, published)
        self.warmup_schedule.consume_due_candidate_warmups.assert_called_once_with(
            before_time_millis=self.NOW_MILLIS,
            limit=10,
        )
        self.task_score.get_score_states.assert_called_once_with(
            task_ids=("task-1", "task-2"),
        )
        self.candidate_cache.candidate_worker_counts.assert_called_once_with(
            candidate_ids=("task-1", "task-2"),
        )
        acquisition = self.candidate_acquirer.acquire_hot_pool_candidates.call_args
        request = acquisition.kwargs["candidate_requests"]["task-1"]
        self.assertEqual(2, request.requested_count)
        self.assertEqual(self.NOW_MILLIS + 5_000, acquisition.kwargs["lease_until_millis"])
        self.candidate_cache.append_candidate_workers.assert_called_once_with(
            candidate_id="task-1",
            candidate_workers=(entry,),
            expires_at_millis=self.NOW_MILLIS + 5_000,
        )
        self.warmup_schedule.schedule_candidate_warmups.assert_called_once_with(
            task_ids=("task-1",),
            due_time_millis=self.NOW_MILLIS,
        )

    def test_complete_acquisition_does_not_requeue_hint(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = ("task-1",)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", maximum_candidates=1),
        }
        self.candidate_cache.candidate_worker_counts.return_value = {"task-1": 0}
        self.candidate_acquirer.acquire_hot_pool_candidates.return_value = {
            "task-1": (self._entry("worker-1"),),
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(1, self.pacer.allocate_candidate_workers(config=self._config()))

        self.warmup_schedule.schedule_candidate_warmups.assert_not_called()

    def test_calls_hot_pool_once_per_worker_group(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = (
            "task-1",
            "task-2",
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor(
                "task-1", maximum_candidates=1, worker_group_id="group-1"
            ),
            "task-2": self._descriptor(
                "task-2", maximum_candidates=1, worker_group_id="group-2"
            ),
        }
        self.candidate_cache.candidate_worker_counts.return_value = {
            "task-1": 0,
            "task-2": 0,
        }
        self.candidate_acquirer.acquire_hot_pool_candidates.side_effect = (
            {"task-1": (self._entry("worker-1", "group-1"),)},
            {"task-2": (self._entry("worker-2", "group-2"),)},
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(2, self.pacer.allocate_candidate_workers(config=self._config()))

        self.assertEqual(
            ["group-1", "group-2"],
            [
                candidate_call.kwargs["worker_group_id"]
                for candidate_call in (
                    self.candidate_acquirer.acquire_hot_pool_candidates.call_args_list
                )
            ],
        )

    def test_missing_and_direct_allocation_hints_are_discarded(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = (
            "missing",
            "item-task",
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "missing": None,
            "item-task": self._descriptor(
                "item-task",
                maximum_candidates=1,
                allocation_mechanism=(
                    WorkerAllocationMechanism.DIRECT_ITEM_RULE
                ),
            ),
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(0, self.pacer.allocate_candidate_workers(config=self._config()))

        self.candidate_cache.candidate_worker_counts.assert_not_called()
        self.candidate_acquirer.acquire_hot_pool_candidates.assert_not_called()
        self.warmup_schedule.schedule_candidate_warmups.assert_not_called()

    def test_non_running_and_hard_paused_hints_do_not_acquire_workers(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = (
            "terminal",
            "paused",
        )
        self.task_score.get_score_states.return_value = {
            "terminal": None,
            "paused": TaskScoreState(
                task_id="paused",
                score=1,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=TaskScoreBandCore.PAUSE_TIME_MILLIS,
                suffix=5,
            ),
        }
        self.task_score.get_score_states.side_effect = None

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(0, self.pacer.allocate_candidate_workers(config=self._config()))

        self.task_catalog.load_task_allocation_descriptors.assert_not_called()
        self.candidate_acquirer.acquire_hot_pool_candidates.assert_not_called()

    def test_empty_recheck_hint_does_not_read_or_lease_candidates(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = (
            "empty-task",
        )
        self.task_score.get_score_states.side_effect = None
        self.task_score.get_score_states.return_value = {
            "empty-task": TaskScoreState(
                task_id="empty-task",
                score=5,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=self.NOW_MILLIS - TaskScoreBandCore.SLOT_MILLIS,
                suffix=1,
            )
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(
                0,
                self.pacer.allocate_candidate_workers(config=self._config()),
            )

        self.task_catalog.load_task_allocation_descriptors.assert_not_called()
        self.candidate_cache.candidate_worker_counts.assert_not_called()
        self.candidate_acquirer.acquire_hot_pool_candidates.assert_not_called()
        self.candidate_cache.append_candidate_workers.assert_not_called()
        self.warmup_schedule.schedule_candidate_warmups.assert_not_called()

    def test_empty_schedule_is_bounded_noop(self) -> None:
        self.warmup_schedule.consume_due_candidate_warmups.return_value = ()

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(0, self.pacer.allocate_candidate_workers(config=self._config()))

        self.task_catalog.load_task_allocation_descriptors.assert_not_called()
        self.candidate_acquirer.acquire_hot_pool_candidates.assert_not_called()

    def test_config_rejects_non_positive_values(self) -> None:
        for values in ((0, 1), (1, 0), (-1, 1)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                TaskWorkerAllocationConfig(
                    task_batch_limit=values[0],
                    worker_lease_duration_millis=values[1],
                )

    @staticmethod
    def _config() -> TaskWorkerAllocationConfig:
        return TaskWorkerAllocationConfig(
            task_batch_limit=10,
            worker_lease_duration_millis=5_000,
        )

    @staticmethod
    def _descriptor(
        task_id: str,
        *,
        maximum_candidates: int,
        worker_group_id: str = "group-1",
        allocation_mechanism: WorkerAllocationMechanism = (
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ),
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            worker_allocation_mechanism=allocation_mechanism,
            idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
            allocation_rule=(
                {"worker.runtime": {"$eq": "python"}}
                if allocation_mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else None
            ),
            config={
                "priority": "80",
                "maximumCandidateWorkers": str(maximum_candidates),
                "maxRetryTimes": "3",
            },
        )

    @staticmethod
    def _entry(
        worker_id: str,
        worker_group_id: str = "group-1",
    ) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id=worker_group_id,
            endpoint_manager_id="endpoint-manager-1",
            worker_lease_score=100,
        )

    @classmethod
    def _running_states(cls, *, task_ids: tuple[str, ...]) -> dict[str, TaskScoreState]:
        return {
            task_id: TaskScoreState(
                task_id=task_id,
                score=1,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=cls.NOW_MILLIS - TaskScoreBandCore.SLOT_MILLIS,
                suffix=TaskScoreBandCore.MIN_SUFFIX,
            )
            for task_id in task_ids
        }


if __name__ == "__main__":
    unittest.main()
