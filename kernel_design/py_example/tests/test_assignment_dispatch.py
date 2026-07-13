from __future__ import annotations

import inspect
import unittest
from dataclasses import fields
from unittest.mock import Mock

from kernel_design.py_example import (
    CandidateWorkerEntry,
    TaskDescriptor,
    TaskDispatchRuntime,
    TaskResourceCatalog,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
    WorkerScoreCore,
)


class AssignmentDispatchTest(unittest.TestCase):
    def test_task_dispatch_runtime_is_an_owner_interface(self) -> None:
        self.assertEqual(
            TaskDispatchRuntime.__abstractmethods__,
            {
                "append_candidate_workers",
                "candidate_worker_count",
                "consume_candidate_workers",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskDispatchRuntime.append_candidate_workers
                ).parameters
            ),
            {"self", "task_id", "candidate_workers"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskDispatchRuntime.candidate_worker_count
                ).parameters
            ),
            {"self", "task_id"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskDispatchRuntime.consume_candidate_workers
                ).parameters
            ),
            {"self", "task_id", "limit"},
        )

    def test_candidate_worker_entry_contains_only_dispatch_evidence(self) -> None:
        self.assertEqual(
            {field.name for field in fields(CandidateWorkerEntry)},
            {
                "worker_id",
                "worker_group_id",
                "observed_worker_score",
                "expires_at_millis",
            },
        )

    def test_allocation_pacer_exposes_named_round_operation(self) -> None:
        self.assertFalse(inspect.isabstract(TaskWorkerAllocationPacer))
        self.assertEqual(
            set(
                inspect.signature(
                    TaskWorkerAllocationPacer.allocate_candidate_workers
                ).parameters
            ),
            {"self", "config"},
        )

    def test_allocation_pacer_matches_one_worker_group_and_publishes_tasks(
        self,
    ) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_active_task_candidates.return_value = (
            "running-task",
            "ready-task",
        )
        task_score.get_score_states.return_value = {
            "running-task": self._task_state(
                "running-task",
                TaskScoreBand.RUNNING_VISIBLE,
                score=101,
            ),
            "ready-task": self._task_state(
                "ready-task",
                TaskScoreBand.PRE_DISPATCH_VISIBLE,
                score=202,
            ),
        }
        task_catalog.load_task_allocation_descriptors.return_value = {
            "running-task": self._descriptor("running-task", minimum_workers=1),
            "ready-task": self._descriptor("ready-task", minimum_workers=2),
        }
        worker_score.acquire_hot_acquire_candidates.return_value = (
            ("worker-1", 1_001),
            ("worker-2", 1_002),
            ("worker-3", 1_003),
        )
        worker_matcher.match_worker_candidates.return_value = {
            "running-task": ["worker-1"],
            "ready-task": ["worker-2", "worker-3"],
        }
        task_score.rewrite_same_band_time_millis.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.TRANSITIONED)
        )
        task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED
        )

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 2)
        worker_score.acquire_hot_acquire_candidates.assert_called_once_with(
            home_bucket_id="image-workers",
            limit=20,
        )
        worker_matcher.match_worker_candidates.assert_called_once()
        self.assertEqual(runtime.append_candidate_workers.call_count, 2)
        published_by_task = {
            call.kwargs["task_id"]: call.kwargs["candidate_workers"]
            for call in runtime.append_candidate_workers.call_args_list
        }
        self.assertEqual(
            [entry.worker_id for entry in published_by_task["running-task"]],
            ["worker-1"],
        )
        self.assertEqual(
            [entry.worker_id for entry in published_by_task["ready-task"]],
            ["worker-2", "worker-3"],
        )
        self.assertEqual(
            task_score.rewrite_score.call_args.kwargs["target_band"],
            TaskScoreBand.RUNNING_VISIBLE,
        )

    def test_allocation_pacer_defers_no_candidate_with_observed_score(self) -> None:
        pacer, task_score, task_catalog, worker_score, _, runtime = (
            self._allocation_pacer()
        )
        state = self._task_state(
            "running-task",
            TaskScoreBand.RUNNING_VISIBLE,
            score=321,
            suffix=4,
        )
        task_score.acquire_active_task_candidates.return_value = ("running-task",)
        task_score.get_score_states.return_value = {"running-task": state}
        task_catalog.load_task_allocation_descriptors.return_value = {
            "running-task": self._descriptor("running-task", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ()

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 0)
        rewrite = task_score.rewrite_observed_same_band_suffix
        self.assertEqual(rewrite.call_args.kwargs["observed_score"], 321)
        self.assertEqual(rewrite.call_args.kwargs["suffix_delta"], -1)
        runtime.append_candidate_workers.assert_not_called()

    def test_allocation_pacer_holds_exhausted_no_candidate(self) -> None:
        pacer, task_score, task_catalog, worker_score, _, _ = (
            self._allocation_pacer()
        )
        state = self._task_state(
            "running-task",
            TaskScoreBand.RUNNING_VISIBLE,
            score=321,
            suffix=0,
        )
        task_score.acquire_active_task_candidates.return_value = ("running-task",)
        task_score.get_score_states.return_value = {"running-task": state}
        task_catalog.load_task_allocation_descriptors.return_value = {
            "running-task": self._descriptor("running-task", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ()

        pacer.allocate_candidate_workers(config=self._allocation_config())

        task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="running-task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=TaskScoreBandCore.PAUSE_TIME_MILLIS,
        )

    def test_allocation_pacer_does_not_publish_after_stale_score_rewrite(self) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_active_task_candidates.return_value = ("running-task",)
        task_score.get_score_states.return_value = {
            "running-task": self._task_state(
                "running-task",
                TaskScoreBand.RUNNING_VISIBLE,
                score=101,
            )
        }
        task_catalog.load_task_allocation_descriptors.return_value = {
            "running-task": self._descriptor("running-task", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = (
            ("worker-1", 1_001),
        )
        worker_matcher.match_worker_candidates.return_value = {
            "running-task": ["worker-1"]
        }
        task_score.rewrite_same_band_time_millis.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.STALE)
        )

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 0)
        runtime.append_candidate_workers.assert_not_called()

    def test_allocation_config_rejects_non_positive_round_bounds(self) -> None:
        with self.assertRaises(ValueError):
            TaskWorkerAllocationConfig(
                task_batch_limit=0,
                worker_scan_limit=20,
                candidate_ttl_millis=1_000,
                no_candidate_recheck_delay_millis=500,
            )

    def _allocation_pacer(
        self,
    ) -> tuple[TaskWorkerAllocationPacer, Mock, Mock, Mock, Mock, Mock]:
        task_score = Mock(spec=TaskScoreBandCore)
        task_catalog = Mock(spec=TaskResourceCatalog)
        worker_score = Mock(spec=WorkerScoreCore)
        worker_matcher = Mock(spec=WorkerCandidateMatcher)
        runtime = Mock(spec=TaskDispatchRuntime)
        return (
            TaskWorkerAllocationPacer(
                task_score,
                task_catalog,
                worker_score,
                worker_matcher,
                runtime,
            ),
            task_score,
            task_catalog,
            worker_score,
            worker_matcher,
            runtime,
        )

    @staticmethod
    def _allocation_config() -> TaskWorkerAllocationConfig:
        return TaskWorkerAllocationConfig(
            task_batch_limit=10,
            worker_scan_limit=20,
            candidate_ttl_millis=60_000,
            no_candidate_recheck_delay_millis=500,
        )

    @staticmethod
    def _task_state(
        task_id: str,
        band: TaskScoreBand,
        *,
        score: int,
        suffix: int = 5,
    ) -> TaskScoreState:
        return TaskScoreState(
            task_id=task_id,
            score=score,
            band=band,
            time_millis=1,
            suffix=suffix,
        )

    @staticmethod
    def _descriptor(task_id: str, *, minimum_workers: int) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id="image-workers",
            allocation_rule={"static.runtime": {"$eq": "python"}},
            config={
                "priority": "80",
                "runningVisibleMinimumCandidateWorkers": str(minimum_workers),
                "maximumCandidateWorkers": "10",
            },
        )


if __name__ == "__main__":
    unittest.main()
