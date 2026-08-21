from __future__ import annotations

import inspect
import json
import unittest
from unittest.mock import Mock, call, patch

from kernel_design.executable_spec import (
    CandidateWorkerEntry,
    WorkerCommandAppendStatus,
    DeliveryCommand,
    WorkerCommandRuntime,
    DeliveryEndpoint,
    TaskDispatchConfig,
    TaskDispatchPacer,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskItem,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
    TaskResourceCatalog,
    TaskRuntime,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
    WorkerAllocationMechanism,
)
from kernel_design.executable_spec.kernel.assignment_dispatch_runtime import (
    CandidateWarmupSchedule,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
)
from kernel_design.executable_spec.scheduling import TaskItemDispatcher


class TaskDispatchPacerTest(unittest.TestCase):
    NOW_MILLIS = 10_000
    CLAIM_UNTIL_MILLIS = 15_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.worker_command_runtime = Mock(spec=WorkerCommandRuntime)
        self.worker_command_runtime.append_worker_commands.side_effect = (
            lambda *, endpoint_manager_id, worker_commands_by_worker_id: {
                worker_id: WorkerCommandAppendStatus.APPENDED
                for worker_id in worker_commands_by_worker_id
            }
        )
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.candidate_acquirer = Mock(spec=WorkerCandidateAcquirer)
        self.warmup_schedule = Mock(spec=CandidateWarmupSchedule)
        self.task_item_dispatcher = TaskItemDispatcher(
            self.item_score,
            self.task_runtime,
            self.candidate_acquirer,
            self.warmup_schedule,
        )
        self.pacer = TaskDispatchPacer(
            self.task_score,
            self.task_catalog,
            self.worker_command_runtime,
            self.item_score,
            self.task_item_dispatcher,
        )
        self.config = TaskDispatchConfig(
            task_batch_limit=10,
            per_task_dispatch_limit=3,
            item_claim_lease_duration_millis=5_000,
        )

    def test_pacer_contract_has_only_round_level_dependencies(self) -> None:
        self.assertEqual(
            {
                "task_score",
                "task_catalog",
                "worker_command_runtime",
                "item_score",
                "task_item_dispatcher",
            },
            set(inspect.signature(TaskDispatchPacer).parameters),
        )

    def test_item_dispatcher_owns_item_and_candidate_dependencies(self) -> None:
        self.assertEqual(
            {
                "item_score",
                "task_runtime",
                "candidate_acquirer",
                "candidate_warmup_schedule",
                "payload_encoder",
            },
            set(inspect.signature(TaskItemDispatcher).parameters),
        )

    def test_observes_items_then_acquires_workers_then_exact_claims(self) -> None:
        events: list[str] = []
        self._prepare_task("task-1")
        items = (self._item("message-1"), self._item("message-2"))
        self.item_score.acquire_item_score_candidates.side_effect = lambda **_: (
            events.append("observe")
            or {"message-1": (101, 3), "message-2": (102, 3)}
        )
        self.task_runtime.load_task_items.return_value = {
            item.message_id: item for item in items
        }
        candidates = (
            self._candidate("worker-1", 201),
            self._candidate("worker-2", 202),
        )
        self.candidate_acquirer.acquire_worker_candidates.side_effect = (
            lambda **_: events.append("acquire") or {"task-1": candidates}
        )
        self.item_score.rewrite_observed_item_scores.side_effect = lambda **_: (
            events.append("claim")
            or {
                "message-1": TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.TRANSITIONED,
                    301,
                ),
                "message-2": TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.TRANSITIONED,
                    302,
                ),
            }
        )
        self.worker_command_runtime.append_worker_commands.side_effect = (
            lambda *, endpoint_manager_id, worker_commands_by_worker_id: (
                events.append("publish")
                or {
                    worker_id: WorkerCommandAppendStatus.APPENDED
                    for worker_id in worker_commands_by_worker_id
                }
            )
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(2, appended)
        self.assertEqual(
            ["observe", "acquire", "claim", "publish"],
            events,
        )
        acquisition_call = (
            self.candidate_acquirer.acquire_worker_candidates.call_args
        )
        request = acquisition_call.kwargs["candidate_requests"]["task-1"]
        self.assertIs(
            WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
            acquisition_call.kwargs["strategy"],
        )
        self.assertEqual(
            "group-1",
            acquisition_call.kwargs["worker_group_id"],
        )
        self.assertFalse(hasattr(request, "worker_group_id"))
        self.assertEqual(80, request.priority)
        self.assertEqual(2, request.requested_count)
        self.assertEqual(
            self.CLAIM_UNTIL_MILLIS,
            acquisition_call.kwargs["lease_until_millis"],
        )
        self.item_score.rewrite_observed_item_scores.assert_called_once_with(
            task_id="task-1",
            observed_scores={"message-1": 101, "message-2": 102},
            target_time_millis=self.CLAIM_UNTIL_MILLIS,
            remaining_budget_delta=-1,
        )
        self.worker_command_runtime.append_worker_commands.assert_called_once()
        self.assertEqual(
            "endpoint-manager-1",
            self.worker_command_runtime.append_worker_commands.call_args.kwargs[
                "endpoint_manager_id"
            ],
        )
        self.warmup_schedule.schedule_candidate_warmups.assert_called_once_with(
            task_ids=("task-1",),
            due_time_millis=self.NOW_MILLIS,
        )
        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
        )
        first_command = self.worker_command_runtime.append_worker_commands.call_args.kwargs[
            "worker_commands_by_worker_id"
        ]["worker-1"]
        self.assertEqual(self.CLAIM_UNTIL_MILLIS, first_command.execute_before_millis)
        self.assertIs(first_command.src, DeliveryEndpoint.TASK)
        self.assertIs(first_command.dst, DeliveryEndpoint.WORKER)
        self.assertEqual("event-1", first_command.message_type)
        self.assertEqual(
            {"value": "message-1"},
            json.loads(first_command.payload),
        )

    def test_partial_candidate_result_claims_only_matching_item_count(self) -> None:
        self._prepare_task("task-1")
        items = (self._item("message-1"), self._item("message-2"))
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 3),
            "message-2": (102, 3),
        }
        self.task_runtime.load_task_items.return_value = {
            item.message_id: item for item in items
        }
        self.candidate_acquirer.acquire_worker_candidates.return_value = {
            "task-1": (self._candidate("worker-1", 201),)
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                301,
            )
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(1, appended)
        self.item_score.rewrite_observed_item_scores.assert_called_once_with(
            task_id="task-1",
            observed_scores={"message-1": 101},
            target_time_millis=self.CLAIM_UNTIL_MILLIS,
            remaining_budget_delta=-1,
        )

    def test_one_round_publishes_all_task_seeds_in_one_runtime_call(self) -> None:
        task_ids = ("task-1", "task-2")
        self.task_score.acquire_dispatch_work_tasks.return_value = task_ids
        self.task_score.get_score_states.return_value = {
            task_id: TaskScoreState(
                task_id=task_id,
                score=100 + index,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=9_000,
                suffix=0,
            )
            for index, task_id in enumerate(task_ids)
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._descriptor(task_id) for task_id in task_ids
        }
        items_by_task = {
            task_id: self._item(f"message-{index}")
            for index, task_id in enumerate(task_ids, start=1)
        }
        self.item_score.acquire_item_score_candidates.side_effect = (
            lambda *, task_id, limit: {
                items_by_task[task_id].message_id: (100, 3)
            }
        )
        self.task_runtime.load_task_items.side_effect = (
            lambda *, task_id, message_ids: {
                items_by_task[task_id].message_id: items_by_task[task_id]
            }
        )
        self.candidate_acquirer.acquire_worker_candidates.side_effect = (
            lambda *, candidate_requests, **_: {
                candidate_id: (self._candidate(f"worker-{candidate_id[-1]}", 200),)
                for candidate_id in candidate_requests
            }
        )
        self.item_score.rewrite_observed_item_scores.side_effect = (
            lambda *, task_id, **_: {
                items_by_task[task_id].message_id: TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.TRANSITIONED,
                    300,
                )
            }
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(2, appended)
        self.worker_command_runtime.append_worker_commands.assert_called_once()
        commands = self.worker_command_runtime.append_worker_commands.call_args.kwargs[
            "worker_commands_by_worker_id"
        ]
        self.assertEqual(
            ("worker-1", "worker-2"),
            tuple(commands),
        )
        self.assertEqual(
            {"message-1", "message-2"},
            {
                json.loads(command.forward)["messageId"]
                for command in commands.values()
            },
        )

    def test_publish_partitions_commands_by_endpoint_manager(self) -> None:
        first = self._command("worker-1")
        second = self._command("worker-2")

        published = self.pacer._publish_worker_commands(
            worker_commands_by_endpoint_manager={
                "endpoint-manager-1": {"worker-1": first},
                "endpoint-manager-2": {"worker-2": second},
            }
        )

        self.assertEqual(2, published)
        self.assertEqual(
            [
                call(
                    endpoint_manager_id="endpoint-manager-1",
                    worker_commands_by_worker_id={"worker-1": first},
                ),
                call(
                    endpoint_manager_id="endpoint-manager-2",
                    worker_commands_by_worker_id={"worker-2": second},
                ),
            ],
            self.worker_command_runtime.append_worker_commands.call_args_list,
        )

    def test_publish_rejects_duplicate_worker_across_endpoint_managers(self) -> None:
        first = self._command("worker-1")
        second = self._command("worker-1")

        with self.assertRaisesRegex(RuntimeError, "multiple commands"):
            self.pacer._publish_worker_commands(
                worker_commands_by_endpoint_manager={
                    "endpoint-manager-1": {"worker-1": first},
                    "endpoint-manager-2": {"worker-1": second},
                }
            )

    def test_no_acquired_worker_does_not_claim_items(self) -> None:
        self._prepare_task("task-1")
        item = self._item("message-1")
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 3)
        }
        self.task_runtime.load_task_items.return_value = {"message-1": item}
        self.candidate_acquirer.acquire_worker_candidates.return_value = {
            "task-1": ()
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(0, appended)
        self.item_score.rewrite_observed_item_scores.assert_not_called()
        self.worker_command_runtime.append_worker_commands.assert_not_called()
        self.warmup_schedule.schedule_candidate_warmups.assert_called_once_with(
            task_ids=("task-1",),
            due_time_millis=self.NOW_MILLIS,
        )

    def test_direct_allocation_uses_item_rules_without_flattening(self) -> None:
        self._prepare_task(
            "task-1",
            allocation_mechanism=WorkerAllocationMechanism.DIRECT_ITEM_RULE,
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
        )
        first_item = self._item(
            "message-1",
            allocation_rule={"workerId": {"$eq": "worker-1"}},
        )
        second_item = self._item(
            "message-2",
            allocation_rule={"workerId": {"$eq": "worker-2"}},
        )
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 3),
            "message-2": (102, 3),
        }
        self.task_runtime.load_task_items.return_value = {
            first_item.message_id: first_item,
            second_item.message_id: second_item,
        }
        self.candidate_acquirer.acquire_worker_candidates.return_value = {
            "message-1": (self._candidate("worker-1", 201),),
            "message-2": (self._candidate("worker-2", 202),),
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                301,
            ),
            "message-2": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                302,
            ),
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(2, appended)
        acquisition_call = self.candidate_acquirer.acquire_worker_candidates.call_args
        self.assertIs(
            WorkerCandidateAcquisitionStrategy.DIRECT,
            acquisition_call.kwargs["strategy"],
        )
        direct_request = acquisition_call.kwargs["candidate_requests"]["message-2"]
        self.assertEqual(
            {"workerId": {"$eq": "worker-2"}},
            direct_request.allocation_rule,
        )
        published = {
            json.loads(command.payload)["value"]: worker_id
            for worker_id, command in (
                self.worker_command_runtime.append_worker_commands.call_args.kwargs[
                    "worker_commands_by_worker_id"
                ].items()
            )
        }
        self.assertEqual(
            {
                "message-1": "worker-1",
                "message-2": "worker-2",
            },
            published,
        )
        self.warmup_schedule.schedule_candidate_warmups.assert_not_called()

    def test_exhausted_and_missing_records_do_not_request_workers(self) -> None:
        self._prepare_task("task-1")
        self.item_score.acquire_item_score_candidates.return_value = {
            "exhausted": (101, 0),
            "missing": (102, 2),
        }
        self.task_runtime.load_task_items.return_value = {"missing": None}
        self.item_score.has_active_items.return_value = {"task-1": True}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(0, appended)
        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("exhausted",),
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.NOW_MILLIS,
        )
        self.candidate_acquirer.acquire_worker_candidates.assert_not_called()
        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
        )

    def test_expired_item_is_final_failed_before_worker_acquisition(self) -> None:
        self._prepare_task("task-1")
        expired = self._item(
            "expired",
            expire_at_millis=self.NOW_MILLIS,
        )
        self.item_score.acquire_item_score_candidates.return_value = {
            "expired": (101, 3)
        }
        self.task_runtime.load_task_items.return_value = {"expired": expired}
        self.item_score.has_active_items.return_value = {"task-1": False}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(0, appended)
        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("expired",),
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.NOW_MILLIS,
        )
        self.candidate_acquirer.acquire_worker_candidates.assert_not_called()
        self.item_score.rewrite_observed_item_scores.assert_not_called()
        self.worker_command_runtime.append_worker_commands.assert_not_called()

    def test_missing_descriptor_skips_item_observation(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = ("task-1",)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": None
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(0, appended)
        self.item_score.acquire_item_score_candidates.assert_not_called()

    def test_active_task_tuple_preserves_score_scan_order(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = (
            "task-2",
            "missing",
            "task-1",
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self._descriptor("task-1"),
            "missing": None,
            "task-2": self._descriptor("task-2"),
        }
        self.task_score.get_score_states.return_value = {
            task_id: TaskScoreState(
                task_id=task_id,
                score=100 + index,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=9_000,
                suffix=0,
            )
            for index, task_id in enumerate(("task-2", "task-1"))
        }
        self.item_score.acquire_item_score_candidates.return_value = {}
        self.item_score.has_active_items.return_value = {
            "task-2": True,
            "task-1": True,
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(0, appended)
        self.assertEqual(
            [
                call(task_id="task-2", limit=3),
                call(task_id="task-1", limit=3),
            ],
            self.item_score.acquire_item_score_candidates.call_args_list,
        )
        self.assertEqual(
            ["task-2", "task-1"],
            [
                rewrite_call.kwargs["task_id"]
                for rewrite_call in (
                    self.task_score.rewrite_same_band_time_millis.call_args_list
                )
            ],
        )

    def test_queue_failure_still_reschedules_running_task(self) -> None:
        self._prepare_task("task-1")
        item = self._item("message-1")
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 3)
        }
        self.task_runtime.load_task_items.return_value = {"message-1": item}
        self.candidate_acquirer.acquire_worker_candidates.return_value = {
            "task-1": (self._candidate("worker-1", 201),)
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                301,
            )
        }
        self.worker_command_runtime.append_worker_commands.side_effect = RuntimeError(
            "queue unavailable"
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ), self.assertRaisesRegex(RuntimeError, "queue unavailable"):
            self.pacer.dispatch_tasks(config=self.config)

        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
        )

    def test_replaced_mailbox_residue_counts_as_published(self) -> None:
        self._prepare_task("task-1")
        item = self._item("message-1")
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 3)
        }
        self.task_runtime.load_task_items.return_value = {"message-1": item}
        self.candidate_acquirer.acquire_worker_candidates.return_value = {
            "task-1": (self._candidate("worker-1", 201),)
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                301,
            )
        }
        self.worker_command_runtime.append_worker_commands.side_effect = None
        self.worker_command_runtime.append_worker_commands.return_value = {
            "worker-1": WorkerCommandAppendStatus.REPLACED
        }

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            published = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(1, published)
        self.task_score.rewrite_same_band_time_millis.assert_called_once()

    def test_non_positive_config_is_rejected(self) -> None:
        invalid = (
            (0, 1, 1),
            (1, 0, 1),
            (1, 1, 0),
        )
        for values in invalid:
            with self.subTest(values=values), self.assertRaises(ValueError):
                TaskDispatchConfig(
                    task_batch_limit=values[0],
                    per_task_dispatch_limit=values[1],
                    item_claim_lease_duration_millis=values[2],
                )

    def test_empty_reusable_task_is_parked_once(self) -> None:
        self._prepare_task(
            "task-1",
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
        )
        self.item_score.acquire_item_score_candidates.return_value = {}
        self.item_score.has_active_items.side_effect = (
            {"task-1": False},
            {"task-1": False},
        )
        self.task_score.park_observed_idle_task.return_value = (
            TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                200,
            )
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_tasks(config=self.config)

        self.assertEqual(0, appended)
        self.task_score.park_observed_idle_task.assert_called_once_with(
            task_id="task-1",
            observed_score=100,
        )
        self.task_score.try_release_idle_park.assert_not_called()

    def test_hold_post_check_releases_when_item_appears_concurrently(self) -> None:
        self._prepare_task(
            "task-1",
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
        )
        self.item_score.acquire_item_score_candidates.return_value = {}
        self.item_score.has_active_items.side_effect = (
            {"task-1": False},
            {"task-1": True},
        )
        self.task_score.park_observed_idle_task.return_value = (
            TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                200,
            )
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.pacer.dispatch_tasks(config=self.config)

        self.task_score.try_release_idle_park.assert_called_once_with(
            task_id="task-1",
        )

    def test_active_retry_item_keeps_task_in_normal_running_pacing(self) -> None:
        self._prepare_task(
            "task-1",
            allocation_mechanism=WorkerAllocationMechanism.DIRECT_ITEM_RULE,
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
        )
        self.item_score.acquire_item_score_candidates.return_value = {}
        self.item_score.has_active_items.return_value = {"task-1": True}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.pacer.dispatch_tasks(config=self.config)

        self.task_score.rewrite_same_band_time_millis.assert_called_once_with(
            task_id="task-1",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.NOW_MILLIS,
        )

    def test_empty_finite_task_closes_exact_observed_score(self) -> None:
        self._prepare_task(
            "task-1",
            idle_disposition=TaskIdleDisposition.CLOSE_WHEN_IDLE,
        )
        self.item_score.acquire_item_score_candidates.return_value = {}
        self.item_score.has_active_items.return_value = {"task-1": False}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.pacer.dispatch_tasks(config=self.config)

        self.task_score.close_observed_score.assert_called_once_with(
            task_id="task-1",
            observed_score=100,
            terminal_score=TaskScoreBandCore.TERMINAL_SCORE_MAX,
        )

    def test_nonzero_running_suffix_is_not_a_dispatch_candidate(self) -> None:
        self._prepare_task("task-1", suffix=2, score=102)

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.pacer.dispatch_tasks(config=self.config)

        self.item_score.acquire_item_score_candidates.assert_not_called()
        self.item_score.has_active_items.assert_not_called()

    def _prepare_task(
        self,
        task_id: str,
        *,
        allocation_mechanism: WorkerAllocationMechanism = (
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ),
        idle_disposition: TaskIdleDisposition = (
            TaskIdleDisposition.CLOSE_WHEN_IDLE
        ),
        suffix: int = 0,
        score: int = 100,
    ) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = (task_id,)
        self.task_score.get_score_states.return_value = {
            task_id: TaskScoreState(
                task_id=task_id,
                score=score,
                band=TaskScoreBand.RUNNING_VISIBLE,
                time_millis=9_000,
                suffix=suffix,
            )
        }
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._descriptor(
                task_id,
                allocation_mechanism=allocation_mechanism,
                idle_disposition=idle_disposition,
            )
        }

    @staticmethod
    def _descriptor(
        task_id: str,
        *,
        allocation_mechanism: WorkerAllocationMechanism = (
            WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ),
        idle_disposition: TaskIdleDisposition = (
            TaskIdleDisposition.CLOSE_WHEN_IDLE
        ),
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id="group-1",
            worker_allocation_mechanism=allocation_mechanism,
            idle_disposition=idle_disposition,
            allocation_rule=(
                {"worker.runtime": {"$eq": "python"}}
                if allocation_mechanism
                is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                else None
            ),
            config={
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        )

    @staticmethod
    def _item(
        message_id: str,
        *,
        allocation_rule: dict[str, object] | None = None,
        expire_at_millis: int | None = None,
    ) -> TaskItem:
        return TaskItem(
            message_id=message_id,
            event_code="event-1",
            created_at_millis=1,
            payload={"value": message_id},
            allocation_rule=allocation_rule,
            expire_at_millis=expire_at_millis,
        )

    @staticmethod
    def _candidate(
        worker_id: str,
        score: int,
        endpoint_manager_id: str = "endpoint-manager-1",
    ) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="group-1",
            endpoint_manager_id=endpoint_manager_id,
            worker_lease_score=score,
        )

    @staticmethod
    def _command(worker_id: str) -> DeliveryCommand:
        return DeliveryCommand.create(
            src=DeliveryEndpoint.TASK,
            dst=DeliveryEndpoint.WORKER,
            message_type="test.event",
            execute_before_millis=20_000,
            payload="delivery",
            forward="context",
        )


if __name__ == "__main__":
    unittest.main()
