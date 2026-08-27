from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
    DueTaskObservation,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskScoreBand,
    TaskScoreState,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPolicy,
    WorkerAllocationMechanism,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
)


class TaskWorkerAllocationPolicyTest(unittest.TestCase):
    NOW_MILLIS = 10_000

    def setUp(self) -> None:
        self.candidate_acquirer = Mock(spec=WorkerCandidateAcquirer)
        self.candidate_cache = Mock(spec=CandidateWorkerCache)
        self.policy = TaskWorkerAllocationPolicy(
            self.candidate_acquirer,
            self.candidate_cache,
        )
        self.config = TaskWorkerAllocationConfig(
            worker_lease_duration_millis=5_000,
        )

    def test_fills_precomputed_candidate_deficit_from_running_batch(self) -> None:
        task = self._observation("task-1", maximum_candidates=3)
        self.candidate_cache.candidate_worker_counts.return_value = {
            "task-1": 1,
        }
        entry = self._entry("worker-1")
        self.candidate_acquirer.acquire_hot_pool_candidates.return_value = {
            "task-1": (entry,),
        }

        with patch.object(
            self.policy,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(
                1,
                self.policy.allocate_candidate_workers(
                    (task,),
                    config=self.config,
                ),
            )

        request = (
            self.candidate_acquirer.acquire_hot_pool_candidates
            .call_args.kwargs["candidate_requests"]["task-1"]
        )
        self.assertEqual(2, request.requested_count)
        self.assertEqual(
            self.NOW_MILLIS + 5_000,
            self.candidate_acquirer.acquire_hot_pool_candidates
            .call_args.kwargs["lease_until_millis"],
        )
        self.candidate_cache.append_candidate_workers.assert_called_once_with(
            candidate_id="task-1",
            candidate_workers=(entry,),
            expires_at_millis=self.NOW_MILLIS + 5_000,
        )

    def test_groups_requests_by_worker_group(self) -> None:
        tasks = (
            self._observation("task-1", worker_group_id="group-1"),
            self._observation("task-2", worker_group_id="group-2"),
        )
        self.candidate_cache.candidate_worker_counts.return_value = {
            "task-1": 0,
            "task-2": 0,
        }
        self.candidate_acquirer.acquire_hot_pool_candidates.side_effect = (
            {"task-1": (self._entry("worker-1", "group-1"),)},
            {"task-2": (self._entry("worker-2", "group-2"),)},
        )

        with patch.object(
            self.policy,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(
                2,
                self.policy.allocate_candidate_workers(
                    tasks,
                    config=self.config,
                ),
            )

        self.assertEqual(
            ["group-1", "group-2"],
            [
                call.kwargs["worker_group_id"]
                for call in self.candidate_acquirer
                .acquire_hot_pool_candidates.call_args_list
            ],
        )

    def test_direct_tasks_and_empty_batches_do_not_touch_candidate_owner(self) -> None:
        direct = self._observation(
            "task-direct",
            mechanism=WorkerAllocationMechanism.DIRECT_ITEM_RULE,
        )

        self.assertEqual(
            0,
            self.policy.allocate_candidate_workers(
                (direct,),
                config=self.config,
            ),
        )
        self.assertEqual(
            0,
            self.policy.allocate_candidate_workers((), config=self.config),
        )

        self.candidate_cache.candidate_worker_counts.assert_not_called()
        self.candidate_acquirer.acquire_hot_pool_candidates.assert_not_called()

    def test_non_positive_lease_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            TaskWorkerAllocationConfig(worker_lease_duration_millis=0)

    @classmethod
    def _observation(
        cls,
        task_id: str,
        *,
        maximum_candidates: int = 1,
        worker_group_id: str = "group-1",
        mechanism: WorkerAllocationMechanism = (
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ),
    ) -> DueTaskObservation:
        descriptor = TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            worker_allocation_mechanism=mechanism,
            idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
            allocation_rule=(
                {"worker.runtime": {"$eq": "python"}}
                if mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else None
            ),
            config={
                "priority": "80",
                "maximumCandidateWorkers": str(maximum_candidates),
                "maxRetryTimes": "3",
            },
        )
        return DueTaskObservation(
            task_id=task_id,
            score_state=TaskScoreState(
                task_id=task_id,
                score=1,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=cls.NOW_MILLIS - 100,
                suffix=0,
            ),
            descriptor=descriptor,
        )

    @staticmethod
    def _entry(
        worker_id: str,
        worker_group_id: str = "group-1",
    ) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id=worker_group_id,
            endpoint_manager_id="adapter-1",
            worker_lease_score=100,
        )


if __name__ == "__main__":
    unittest.main()
