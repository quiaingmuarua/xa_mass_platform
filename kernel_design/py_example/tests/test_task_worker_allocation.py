from __future__ import annotations

import inspect
import unittest
from dataclasses import fields
from unittest.mock import Mock, call

from kernel_design.py_example import (
    CandidateWorkerEntry,
    TaskDescriptor,
    TaskDispatchRuntime,
    TaskResourceCatalog,
    TaskRunningActivationConfig,
    TaskRunningActivationPolicy,
    TaskRunningActivationPacer,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
    WorkerScoreCore,
    minimum_candidate_workers_satisfied,
)


class AssignmentDispatchTest(unittest.TestCase):
    def test_task_dispatch_runtime_is_an_owner_interface(self) -> None:
        self.assertEqual(
            TaskDispatchRuntime.__abstractmethods__,
            {
                "append_candidate_workers",
                "candidate_worker_counts",
                "consume_candidate_workers",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskDispatchRuntime.append_candidate_workers
                ).parameters
            ),
            {"self", "task_id", "candidate_workers", "expires_at_millis"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskDispatchRuntime.candidate_worker_counts
                ).parameters
            ),
            {"self", "task_ids"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskDispatchRuntime.consume_candidate_workers
                ).parameters
            ),
            {"self", "task_id", "limit"},
        )

    def test_candidate_worker_entry_contains_only_score_evidence(self) -> None:
        self.assertEqual(
            {field.name for field in fields(CandidateWorkerEntry)},
            {
                "worker_id",
                "worker_group_id",
                "worker_lease_score",
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

    def test_running_activation_pacer_exposes_named_operation(
        self,
    ) -> None:
        self.assertFalse(inspect.isabstract(TaskRunningActivationPacer))
        self.assertEqual(
            set(
                inspect.signature(
                    TaskRunningActivationPacer.activate_running_visible_tasks
                ).parameters
            ),
            {"self", "config"},
        )

    def test_allocation_stops_when_no_active_tasks_are_acquired(self) -> None:
        pacer, task_score, task_catalog, worker_score, _, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = ((), ())

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
        task_score.acquire_band_task_candidates.side_effect = (
            ("running-task",),
            ("pre-dispatch-task",),
        )
        task_catalog.load_task_allocation_descriptors.return_value = {
            "running-task": self._descriptor("running-task", minimum_workers=1),
            "pre-dispatch-task": self._descriptor(
                "pre-dispatch-task",
                minimum_workers=10,
            ),
        }
        worker_score.acquire_hot_acquire_candidates.return_value = (
            "worker-1",
            "worker-2",
        )
        worker_matcher.match_worker_candidates.return_value = {
            "running-task": [self._reservation("worker-1", score=2_001)],
            "pre-dispatch-task": [
                self._reservation("worker-2", score=2_002)
            ],
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
        self.assertEqual(
            worker_matcher.match_worker_candidates.call_args.kwargs["worker_ids"],
            ("worker-1", "worker-2"),
        )
        self.assertGreater(
            worker_matcher.match_worker_candidates.call_args.kwargs[
                "lease_until_millis"
            ],
            0,
        )
        lease_until_millis = worker_matcher.match_worker_candidates.call_args.kwargs[
            "lease_until_millis"
        ]
        self.assertEqual(constraints["running-task"].limit, 10)
        self.assertEqual(constraints["pre-dispatch-task"].limit, 10)
        self.assertEqual(runtime.append_candidate_workers.call_count, 2)
        published_by_task = {
            call.kwargs["task_id"]: call.kwargs["candidate_workers"]
            for call in runtime.append_candidate_workers.call_args_list
        }
        self.assertEqual(
            {
                call.kwargs["expires_at_millis"]
                for call in runtime.append_candidate_workers.call_args_list
            },
            {lease_until_millis},
        )
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

    def test_allocation_rewrites_time_without_reading_or_transitioning_band(
        self,
    ) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (("task-1",), ())
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=5)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ("worker-1",)
        worker_matcher.match_worker_candidates.return_value = {
            "task-1": [self._reservation("worker-1", score=2_001)]
        }
        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 1)
        task_score.get_score_states.assert_not_called()
        task_score.rewrite_score.assert_not_called()
        task_score.rewrite_observed_same_band_suffix.assert_not_called()
        runtime.candidate_worker_counts.assert_called_once_with(
            task_ids=("task-1",)
        )
        constraints = worker_matcher.match_worker_candidates.call_args.kwargs[
            "candidate_constraints"
        ]
        self.assertEqual(constraints["task-1"].limit, 10)
        runtime.append_candidate_workers.assert_called_once()
        rewrite = task_score.rewrite_same_band_time_millis.call_args.kwargs
        self.assertEqual(rewrite["task_id"], "task-1")
        self.assertEqual(rewrite["expected_band"], TaskScoreBand.RUNNING_VISIBLE)

    def test_allocation_matches_only_remaining_task_candidate_capacity(
        self,
    ) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, _ = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (("task-1",), ())
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ("worker-1",)
        worker_matcher.match_worker_candidates.return_value = {"task-1": []}
        pacer.dispatch_runtime.candidate_worker_counts.return_value = {
            "task-1": 7
        }

        pacer.allocate_candidate_workers(config=self._allocation_config())

        constraints = worker_matcher.match_worker_candidates.call_args.kwargs[
            "candidate_constraints"
        ]
        self.assertEqual(constraints["task-1"].limit, 3)

    def test_allocation_at_candidate_capacity_skips_worker_scan_and_rotates(
        self,
    ) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (("task-1",), ())
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        runtime.candidate_worker_counts.return_value = {"task-1": 10}

        published = pacer.allocate_candidate_workers(
            config=self._allocation_config()
        )

        self.assertEqual(published, 0)
        worker_score.acquire_hot_acquire_candidates.assert_not_called()
        worker_matcher.match_worker_candidates.assert_not_called()
        task_score.rewrite_same_band_time_millis.assert_called_once()

    def test_missing_descriptor_is_rotated_out_of_the_score_head(self) -> None:
        pacer, task_score, task_catalog, worker_score, _, _ = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (("task-1",), ())
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": None
        }

        published = pacer.allocate_candidate_workers(
            config=self._allocation_config()
        )

        self.assertEqual(published, 0)
        worker_score.acquire_hot_acquire_candidates.assert_not_called()
        task_score.rewrite_same_band_time_millis.assert_called_once()

    def test_append_failure_releases_all_unpublished_worker_leases(self) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (
            ("task-1", "task-2"),
            (),
        )
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1),
            "task-2": self._descriptor("task-2", minimum_workers=1),
        }
        worker_score.acquire_hot_acquire_candidates.return_value = (
            "worker-1",
            "worker-2",
        )
        worker_matcher.match_worker_candidates.return_value = {
            "task-1": [self._reservation("worker-1", score=2_001)],
            "task-2": [self._reservation("worker-2", score=2_002)],
        }
        runtime.append_candidate_workers.side_effect = RuntimeError("write failed")

        with self.assertRaisesRegex(RuntimeError, "write failed"):
            pacer.allocate_candidate_workers(config=self._allocation_config())

        release_calls = worker_score.release_score_hold.call_args_list
        self.assertEqual(len(release_calls), 2)
        self.assertEqual(
            [
                call(
                    home_bucket_id="image-workers",
                    worker_id="worker-1",
                    observed_score=2_001,
                    release_time_millis=release_calls[0].kwargs[
                        "release_time_millis"
                    ],
                ),
                call(
                    home_bucket_id="image-workers",
                    worker_id="worker-2",
                    observed_score=2_002,
                    release_time_millis=release_calls[1].kwargs[
                        "release_time_millis"
                    ],
                ),
            ],
            release_calls,
        )
        task_score.rewrite_same_band_time_millis.assert_not_called()

    def test_candidate_publication_is_not_gated_by_time_rewrite_result(self) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (("task-1",), ())
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ("worker-1",)
        worker_matcher.match_worker_candidates.return_value = {
            "task-1": [self._reservation("worker-1", score=2_001)]
        }
        task_score.rewrite_same_band_time_millis.return_value = (
            TaskScoreTransitionResult(TaskScoreTransitionStatus.STALE)
        )

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 1)
        runtime.append_candidate_workers.assert_called_once()
        task_score.rewrite_same_band_time_millis.assert_called_once()

    def test_allocation_with_no_hot_workers_is_a_bounded_noop(self) -> None:
        pacer, task_score, task_catalog, worker_score, worker_matcher, runtime = (
            self._allocation_pacer()
        )
        task_score.acquire_band_task_candidates.side_effect = (("task-1",), ())
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        worker_score.acquire_hot_acquire_candidates.return_value = ()
        worker_matcher.match_worker_candidates.return_value = {"task-1": []}

        published = pacer.allocate_candidate_workers(config=self._allocation_config())

        self.assertEqual(published, 0)
        worker_matcher.match_worker_candidates.assert_called_once()
        runtime.append_candidate_workers.assert_not_called()
        task_score.rewrite_observed_same_band_suffix.assert_not_called()
        task_score.rewrite_same_band_time_millis.assert_called_once()

    def test_running_activation_promotes_eligible_task_and_resets_suffix(self) -> None:
        pacer, task_score, task_catalog, runtime = self._running_activation_pacer()
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=3)
        }
        runtime.candidate_worker_counts.return_value = {"task-1": 3}
        task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED
        )

        transitioned = pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
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

    def test_running_activation_leaves_ineligible_task_unchanged(self) -> None:
        pacer, task_score, task_catalog, runtime = self._running_activation_pacer()
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=3)
        }
        runtime.candidate_worker_counts.return_value = {"task-1": 2}

        transitioned = pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )

        self.assertEqual(transitioned, 0)
        task_score.rewrite_score.assert_not_called()

    def test_running_activation_delegates_transition_decision_to_policy(
        self,
    ) -> None:
        activation_policy = Mock(return_value=False)
        pacer, task_score, task_catalog, runtime = self._running_activation_pacer(
            activation_policy
        )
        descriptor = self._descriptor("task-1", minimum_workers=1)
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": descriptor
        }
        runtime.candidate_worker_counts.return_value = {"task-1": 100}

        transitioned = pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
                task_batch_limit=10,
                running_visible_initial_suffix=8,
            )
        )

        self.assertEqual(transitioned, 0)
        activation_policy.assert_called_once_with(descriptor, 100)
        task_score.rewrite_score.assert_not_called()

    def test_running_activation_counts_only_core_accepted_writes(self) -> None:
        pacer, task_score, task_catalog, runtime = self._running_activation_pacer()
        task_score.acquire_band_task_candidates.return_value = ("task-1",)
        task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1", minimum_workers=1)
        }
        runtime.candidate_worker_counts.return_value = {"task-1": 1}
        task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.STALE
        )

        transitioned = pacer.activate_running_visible_tasks(
            config=TaskRunningActivationConfig(
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
                worker_lease_duration_millis=1_000,
            )

    def _allocation_pacer(
        self,
    ) -> tuple[TaskWorkerAllocationPacer, Mock, Mock, Mock, Mock, Mock]:
        task_score = Mock(spec=TaskScoreBandCore)
        task_catalog = Mock(spec=TaskResourceCatalog)
        worker_score = Mock(spec=WorkerScoreCore)
        worker_matcher = Mock(spec=WorkerCandidateMatcher)
        runtime = Mock(spec=TaskDispatchRuntime)
        runtime.candidate_worker_counts.return_value = {}
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
    def _running_activation_pacer(
        activation_policy: TaskRunningActivationPolicy = (
            minimum_candidate_workers_satisfied
        ),
    ) -> tuple[
        TaskRunningActivationPacer,
        Mock,
        Mock,
        Mock,
    ]:
        task_score = Mock(spec=TaskScoreBandCore)
        task_catalog = Mock(spec=TaskResourceCatalog)
        runtime = Mock(spec=TaskDispatchRuntime)
        runtime.candidate_worker_counts.return_value = {}
        return (
            TaskRunningActivationPacer(
                task_score,
                task_catalog,
                runtime,
                activation_policy,
            ),
            task_score,
            task_catalog,
            runtime,
        )

    @staticmethod
    def _allocation_config() -> TaskWorkerAllocationConfig:
        return TaskWorkerAllocationConfig(
            task_batch_limit=10,
            worker_scan_limit=20,
            worker_lease_duration_millis=60_000,
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

    @staticmethod
    def _reservation(
        worker_id: str,
        *,
        score: int,
    ) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="image-workers",
            worker_lease_score=score,
        )


if __name__ == "__main__":
    unittest.main()
