from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock, call, patch

from kernel_design.executable_spec import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
    RealtimeWorkerCandidateAcquirer,
    TaskDescriptor,
    TaskResourceCatalog,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
)


class TaskWorkerAllocationPacerTest(unittest.TestCase):
    NOW_MILLIS = 10_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.realtime_acquirer = Mock(spec=RealtimeWorkerCandidateAcquirer)
        self.candidate_cache = Mock(spec=CandidateWorkerCache)
        self.pacer = TaskWorkerAllocationPacer(
            self.task_score,
            self.task_catalog,
            self.realtime_acquirer,
            self.candidate_cache,
        )

    def test_contract_uses_candidate_cache_and_realtime_acquirer(self) -> None:
        self.assertEqual(
            {
                "task_score",
                "task_catalog",
                "realtime_candidate_acquirer",
                "candidate_cache",
            },
            set(inspect.signature(TaskWorkerAllocationPacer).parameters),
        )
        self.assertEqual(
            {
                "append_candidate_workers",
                "candidate_worker_counts",
                "consume_candidate_workers",
            },
            CandidateWorkerCache.__abstractmethods__,
        )

    def test_warms_task_id_candidate_cache_and_rotates_running_tasks(self) -> None:
        self.task_score.acquire_band_task_candidates.return_value = (
            "task-1",
            "task-2",
        )
        self.candidate_cache.candidate_worker_counts.return_value = {
            "task-1": 1,
            "task-2": 5,
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", maximum_candidates=3),
            "task-2": self._descriptor("task-2", maximum_candidates=5),
        }
        entry = self._entry("worker-1")
        self.realtime_acquirer.acquire_worker_candidates.return_value = {
            "task-1": (entry,),
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            side_effect=(self.NOW_MILLIS, self.NOW_MILLIS + 1, 10_002, 10_003),
        ):
            published = self.pacer.allocate_candidate_workers(
                config=self._config(),
            )

        self.assertEqual(1, published)
        self.task_score.acquire_band_task_candidates.assert_called_once_with(
            band=TaskScoreBand.RUNNING_VISIBLE,
            before_time_millis=self.NOW_MILLIS,
            limit=10,
        )
        self.candidate_cache.candidate_worker_counts.assert_called_once_with(
            candidate_ids=("task-1", "task-2"),
        )
        acquisition_call = (
            self.realtime_acquirer.acquire_worker_candidates.call_args
        )
        request = acquisition_call.kwargs["candidate_requests"]["task-1"]
        self.assertEqual(
            "group-1",
            acquisition_call.kwargs["worker_group_id"],
        )
        self.assertFalse(hasattr(request, "worker_group_id"))
        self.assertEqual(80, request.priority)
        self.assertEqual(2, request.requested_count)
        self.assertNotIn(
            "task-2",
            acquisition_call.kwargs["candidate_requests"],
        )
        self.assertEqual(
            self.NOW_MILLIS + 1 + 5_000,
            acquisition_call.kwargs["lease_until_millis"],
        )
        self.candidate_cache.append_candidate_workers.assert_called_once_with(
            candidate_id="task-1",
            candidate_workers=(entry,),
            expires_at_millis=self.NOW_MILLIS + 1 + 5_000,
        )
        self.assertEqual(
            [
                call(
                    task_id="task-1",
                    expected_band=TaskScoreBand.RUNNING_VISIBLE,
                    target_time_millis=10_002,
                ),
                call(
                    task_id="task-2",
                    expected_band=TaskScoreBand.RUNNING_VISIBLE,
                    target_time_millis=10_003,
                ),
            ],
            self.task_score.rewrite_same_band_time_millis.call_args_list,
        )

    def test_missing_descriptor_is_not_acquired_but_is_rotated(self) -> None:
        self.task_score.acquire_band_task_candidates.return_value = ("task-1",)
        self.candidate_cache.candidate_worker_counts.return_value = {"task-1": 0}
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": None
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            side_effect=(10_000, 10_001, 10_002),
        ):
            published = self.pacer.allocate_candidate_workers(
                config=self._config(),
            )

        self.assertEqual(0, published)
        self.realtime_acquirer.acquire_worker_candidates.assert_not_called()
        self.candidate_cache.append_candidate_workers.assert_not_called()
        self.task_score.rewrite_same_band_time_millis.assert_called_once()

    def test_calls_realtime_acquirer_once_per_worker_group(self) -> None:
        self.task_score.acquire_band_task_candidates.return_value = (
            "task-1",
            "task-2",
        )
        self.candidate_cache.candidate_worker_counts.return_value = {
            "task-1": 0,
            "task-2": 0,
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor(
                "task-1",
                maximum_candidates=1,
                worker_group_id="group-1",
            ),
            "task-2": self._descriptor(
                "task-2",
                maximum_candidates=1,
                worker_group_id="group-2",
            ),
        }
        self.realtime_acquirer.acquire_worker_candidates.side_effect = (
            {"task-1": (self._entry("worker-1", "group-1"),)},
            {"task-2": (self._entry("worker-2", "group-2"),)},
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            side_effect=(10_000, 10_001, 10_002, 10_003),
        ):
            published = self.pacer.allocate_candidate_workers(
                config=self._config(),
            )

        self.assertEqual(2, published)
        acquisition_calls = (
            self.realtime_acquirer.acquire_worker_candidates.call_args_list
        )
        self.assertEqual(2, len(acquisition_calls))
        self.assertEqual(
            "group-1",
            acquisition_calls[0].kwargs["worker_group_id"],
        )
        self.assertEqual(
            {"task-1"},
            set(acquisition_calls[0].kwargs["candidate_requests"]),
        )
        self.assertEqual(
            "group-2",
            acquisition_calls[1].kwargs["worker_group_id"],
        )
        self.assertEqual(
            {"task-2"},
            set(acquisition_calls[1].kwargs["candidate_requests"]),
        )

    def test_empty_running_scan_is_bounded_noop(self) -> None:
        self.task_score.acquire_band_task_candidates.return_value = ()
        self.candidate_cache.candidate_worker_counts.return_value = {}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            side_effect=(10_000, 10_001),
        ):
            published = self.pacer.allocate_candidate_workers(
                config=self._config(),
            )

        self.assertEqual(0, published)
        self.task_catalog.load_task_allocation_descriptors.assert_not_called()
        self.realtime_acquirer.acquire_worker_candidates.assert_not_called()

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
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            allocation_rule={"attributes.runtime": {"$eq": "python"}},
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
            endpoint_manager_id="endpoint-1",
            worker_lease_score=100,
        )


if __name__ == "__main__":
    unittest.main()
