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
    TaskScoreTransitionConfig,
    TaskScoreTransitionPacer,
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

    def test_score_transition_pacer_exposes_independent_rewrite_operation(
        self,
    ) -> None:
        self.assertFalse(inspect.isabstract(TaskScoreTransitionPacer))
        self.assertEqual(
            set(
                inspect.signature(
                    TaskScoreTransitionPacer.rewrite_score
                ).parameters
            ),
            {"self", "config"},
        )

    def test_allocation_stops_when_no_active_tasks_are_acquired(self) -> None:
        pacer, task_score, task_catalog, worker_score, _, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_active_task_candidates.return_value = ()

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 0)
        task_catalog.load_task_allocation_descriptors.assert_not_called()
        worker_score.acquire_hot_acquire_candidates.assert_not_called()
        runtime.append_candidate_workers.assert_not_called()

    def test_allocation_matches_one_worker_group_and_publishes_all_evidence(
        self,
    ) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_active_task_candidates.return_value = (
            "running-task",
            "pre-dispatch-task",
        )
        task_catalog.load_task_allocation_descriptors.return_value = {
            "running-task": self._descriptor("running-task", minimum_workers=1),
            "pre-dispatch-task": self._descriptor(
                "pre-dispatch-task",
                minimum_workers=10,
            ),
        }
        worker_score.acquire_hot_acquire_candidates.return_value = (
            ("worker-1", 1_001),
            ("worker-2", 1_002),
        )
        worker_matcher.match_worker_candidates.return_value = {
            "running-task": ["worker-1"],
            "pre-dispatch-task": ["worker-2"],
        }

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 2)
        worker_score.acquire_hot_acquire_candidates.assert_called_once_with(
            home_bucket_id="image-workers",
            limit=20,
        )
        constraints = worker_matcher.match_worker_candidates.call_args.kwargs[
            "candidate_constraints"
        ]
        self.assertEqual(constraints["running-task"].limit, 10)
        self.assertEqual(constraints["pre-dispatch-task"].limit, 10)
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
            [
                entry.worker_id
                for entry in published_by_task["pre-dispatch-task"]
            ],
            ["worker-2"],
        )

    def test_allocation_does_not_read_or_write_task_transition_state(self) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_active_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=5)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = (
            ("worker-1", 1_001),
        )
        worker_matcher.match_worker_candidates.return_value = {
            "task-1": ["worker-1"]
        }
        runtime.candidate_worker_count.return_value = 9

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 1)
        task_score.get_score_states.assert_not_called()
        task_score.rewrite_score.assert_not_called()
        task_score.rewrite_same_band_time_millis.assert_not_called()
        task_score.rewrite_observed_same_band_suffix.assert_not_called()
        runtime.candidate_worker_count.assert_not_called()
        constraints = worker_matcher.match_worker_candidates.call_args.kwargs[
            "candidate_constraints"
        ]
        self.assertEqual(constraints["task-1"].limit, 10)
        runtime.append_candidate_workers.assert_called_once()

    def test_allocation_with_no_hot_workers_is_a_bounded_noop(self) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_active_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ()

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 0)
        worker_matcher.match_worker_candidates.assert_not_called()
        runtime.append_candidate_workers.assert_not_called()
        task_score.rewrite_observed_same_band_suffix.assert_not_called()

    def test_score_transition_promotes_eligible_task_and_resets_suffix(self) -> None:
        pacer, task_score, task_catalog, runtime = self._score_transition_pacer()
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=3)
        }
        runtime.candidate_worker_count.return_value = 3
        task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED
        )

        transitioned = pacer.rewrite_score(
            config=TaskScoreTransitionConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )

        self.assertEqual(transitioned, 1)
        acquire = task_score.acquire_band_task_candidates.call_args.kwargs
        self.assertEqual(acquire["band"], TaskScoreBand.PRE_DISPATCH_VISIBLE)
        self.assertEqual(acquire["limit"], 10)
        rewrite = task_score.rewrite_score.call_args.kwargs
        self.assertEqual(rewrite["expected_band"], TaskScoreBand.PRE_DISPATCH_VISIBLE)
        self.assertEqual(rewrite["target_band"], TaskScoreBand.RUNNING_VISIBLE)
        self.assertEqual(rewrite["target_suffix"], 8)
        task_score.get_score_states.assert_not_called()

    def test_score_transition_leaves_ineligible_task_unchanged(self) -> None:
        pacer, task_score, task_catalog, runtime = self._score_transition_pacer()
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=3)
        }
        runtime.candidate_worker_count.return_value = 2

        transitioned = pacer.rewrite_score(
            config=TaskScoreTransitionConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )

        self.assertEqual(transitioned, 0)
        task_score.rewrite_score.assert_not_called()

    def test_score_transition_counts_only_core_accepted_writes(self) -> None:
        pacer, task_score, task_catalog, runtime = self._score_transition_pacer()
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        runtime.candidate_worker_count.return_value = 1
        task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.STALE
        )

        transitioned = pacer.rewrite_score(
            config=TaskScoreTransitionConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )

        self.assertEqual(transitioned, 0)

    def test_allocation_config_rejects_non_positive_round_bounds(self) -> None:
        with self.assertRaises(ValueError):
            TaskWorkerAllocationConfig(
                task_batch_limit=0,
                worker_scan_limit=20,
                candidate_ttl_millis=1_000,
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
    def _score_transition_pacer() -> tuple[
        TaskScoreTransitionPacer,
        Mock,
        Mock,
        Mock,
    ]:
        task_score = Mock(spec=TaskScoreBandCore)
        task_catalog = Mock(spec=TaskResourceCatalog)
        runtime = Mock(spec=TaskDispatchRuntime)
        return (
            TaskScoreTransitionPacer(task_score, task_catalog, runtime),
            task_score,
            task_catalog,
            runtime,
        )

    @staticmethod
    def _allocation_config() -> TaskWorkerAllocationConfig:
        return TaskWorkerAllocationConfig(
            task_batch_limit=10,
            worker_scan_limit=20,
            candidate_ttl_millis=60_000,
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
