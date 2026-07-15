from __future__ import annotations

import inspect
import unittest
from dataclasses import fields
from unittest.mock import Mock, call, patch

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    AssignmentDispatchRuntime,
    CandidateWorkerEntry,
    DeliverSeed,
    TaskItem,
    TaskItemDispatchConfig,
    TaskItemDispatchPacer,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
    TaskRuntime,
    TaskScoreBandCore,
)


class TaskItemDispatchPacerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.dispatch_runtime = Mock(spec=AssignmentDispatchRuntime)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.pacer = TaskItemDispatchPacer(
            self.task_score,
            self.dispatch_runtime,
            self.item_score,
            self.task_runtime,
        )
        self.config = TaskItemDispatchConfig(
            task_batch_limit=10,
            per_task_dispatch_limit=4,
            item_claim_lease_duration_millis=3_000,
        )

    @staticmethod
    def candidate(
        worker_id: str,
        *,
        endpoint_manager_id: str = "endpoint-1",
        lease_score: int = 10_001,
    ) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="image-workers",
            endpoint_manager_id=endpoint_manager_id,
            worker_lease_score=lease_score,
        )

    @staticmethod
    def item(message_id: str) -> TaskItem:
        return TaskItem(
            message_id=message_id,
            event_code="image.resize",
            created_at_millis=90_000,
            payload={"messageId": message_id},
        )

    def dispatch(self) -> int:
        with patch.object(
            TaskItemDispatchPacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            return self.pacer.dispatch_task_items(config=self.config)

    def test_public_contract_and_config_validation(self) -> None:
        self.assertEqual(
            [
                "task_id",
                "selected_worker_id",
                "worker_group_id",
                "endpoint_manager_id",
                "task_item",
                "claim_score",
                "worker_lease_score",
            ],
            [field.name for field in fields(DeliverSeed)],
        )
        self.assertEqual(
            ["self", "endpoint_manager_id", "deliver_seeds"],
            list(
                inspect.signature(
                    AssignmentDispatchRuntime.append_deliver_seeds
                ).parameters
            ),
        )
        self.assertIs(executable_spec.TaskItemDispatchPacer, TaskItemDispatchPacer)
        for values in ((0, 1, 1), (1, 0, 1), (1, 1, 0), (-1, 1, 1)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                TaskItemDispatchConfig(*values)

    def test_no_running_task_stops_before_other_owners(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = ()

        self.assertEqual(0, self.dispatch())

        self.task_score.acquire_dispatch_work_tasks.assert_called_once_with(limit=10)
        self.dispatch_runtime.consume_candidate_workers.assert_not_called()
        self.item_score.acquire_item_score_candidates.assert_not_called()
        self.task_runtime.load_task_items.assert_not_called()
        self.dispatch_runtime.append_deliver_seeds.assert_not_called()

    def test_no_candidate_does_not_acquire_items(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = ("task-1",)
        self.dispatch_runtime.consume_candidate_workers.return_value = ()

        self.assertEqual(0, self.dispatch())

        self.dispatch_runtime.consume_candidate_workers.assert_called_once_with(
            task_id="task-1",
            limit=4,
        )
        self.item_score.acquire_item_score_candidates.assert_not_called()
        self.task_runtime.load_task_items.assert_not_called()

    def test_exhausted_missing_and_stale_items_do_not_produce_seeds(self) -> None:
        candidates = tuple(self.candidate(f"worker-{index}") for index in range(1, 5))
        item_1 = self.item("message-1")
        item_3 = self.item("message-3")
        self.task_score.acquire_dispatch_work_tasks.return_value = ("task-1",)
        self.dispatch_runtime.consume_candidate_workers.return_value = candidates
        self.item_score.acquire_item_score_candidates.return_value = {
            "exhausted": (101, 0),
            "message-1": (102, 3),
            "missing": (103, 2),
            "message-3": (104, 1),
        }
        self.task_runtime.load_task_items.return_value = {
            "message-1": item_1,
            "missing": None,
            "message-3": item_3,
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.STALE
            ),
            "message-3": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                304,
            ),
        }

        self.assertEqual(1, self.dispatch())

        self.item_score.acquire_item_score_candidates.assert_called_once_with(
            task_id="task-1",
            limit=4,
        )
        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("exhausted",),
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.NOW_MILLIS,
        )
        self.item_score.rewrite_observed_item_scores.assert_called_once_with(
            task_id="task-1",
            observed_scores={"message-1": 102, "message-3": 104},
            target_time_millis=self.NOW_MILLIS + 3_000,
            remaining_budget_delta=-1,
        )
        seed = self.dispatch_runtime.append_deliver_seeds.call_args.kwargs[
            "deliver_seeds"
        ][0]
        self.assertEqual("worker-1", seed.selected_worker_id)
        self.assertIs(item_3, seed.task_item)
        self.assertEqual(304, seed.claim_score)
        self.assertEqual(10_001, seed.worker_lease_score)

    def test_claimed_items_keep_observation_order_when_paired(self) -> None:
        candidates = (self.candidate("worker-1"), self.candidate("worker-2"))
        item_2 = self.item("message-2")
        item_1 = self.item("message-1")
        self.task_score.acquire_dispatch_work_tasks.return_value = ("task-1",)
        self.dispatch_runtime.consume_candidate_workers.return_value = candidates
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-2": (202, 2),
            "message-1": (201, 2),
        }
        self.task_runtime.load_task_items.return_value = {
            "message-2": item_2,
            "message-1": item_1,
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                401,
            ),
            "message-2": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                402,
            ),
        }

        self.assertEqual(2, self.dispatch())

        seeds = self.dispatch_runtime.append_deliver_seeds.call_args.kwargs[
            "deliver_seeds"
        ]
        self.assertEqual(
            [("worker-1", "message-2"), ("worker-2", "message-1")],
            [
                (seed.selected_worker_id, seed.task_item.message_id)
                for seed in seeds
            ],
        )

    def test_whole_round_aggregates_by_endpoint_manager(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = (
            "task-1",
            "task-2",
        )
        self.dispatch_runtime.consume_candidate_workers.side_effect = [
            (self.candidate("worker-1", lease_score=11),),
            (self.candidate("worker-2", lease_score=12),),
        ]
        self.item_score.acquire_item_score_candidates.side_effect = [
            {"message-1": (101, 2)},
            {"message-2": (102, 2)},
        ]
        self.task_runtime.load_task_items.side_effect = [
            {"message-1": self.item("message-1")},
            {"message-2": self.item("message-2")},
        ]
        self.item_score.rewrite_observed_item_scores.side_effect = [
            {
                "message-1": TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.TRANSITIONED,
                    301,
                )
            },
            {
                "message-2": TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.TRANSITIONED,
                    302,
                )
            },
        ]

        self.assertEqual(2, self.dispatch())

        self.dispatch_runtime.append_deliver_seeds.assert_called_once()
        append_call = self.dispatch_runtime.append_deliver_seeds.call_args
        seeds = append_call.kwargs["deliver_seeds"]
        self.assertEqual("endpoint-1", append_call.kwargs["endpoint_manager_id"])
        self.assertEqual(["task-1", "task-2"], [seed.task_id for seed in seeds])

    def test_different_endpoint_managers_are_appended_separately(self) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = ("task-1",)
        self.dispatch_runtime.consume_candidate_workers.return_value = (
            self.candidate("worker-1", endpoint_manager_id="endpoint-1"),
            self.candidate("worker-2", endpoint_manager_id="endpoint-2"),
        )
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 2),
            "message-2": (102, 2),
        }
        self.task_runtime.load_task_items.return_value = {
            "message-1": self.item("message-1"),
            "message-2": self.item("message-2"),
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

        self.assertEqual(2, self.dispatch())

        self.assertEqual(
            ["endpoint-1", "endpoint-2"],
            [
                append_call.kwargs["endpoint_manager_id"]
                for append_call in self.dispatch_runtime.append_deliver_seeds.call_args_list
            ],
        )

    def test_queue_failure_keeps_prior_batch_and_does_not_compensate(self) -> None:
        def fail_second_queue(*, endpoint_manager_id, deliver_seeds):
            if endpoint_manager_id == "endpoint-2":
                raise RuntimeError("queue unavailable")

        self.dispatch_runtime.append_deliver_seeds.side_effect = fail_second_queue
        self.task_score.acquire_dispatch_work_tasks.return_value = ("task-1",)
        self.dispatch_runtime.consume_candidate_workers.return_value = (
            self.candidate("worker-1", endpoint_manager_id="endpoint-1"),
            self.candidate("worker-2", endpoint_manager_id="endpoint-2"),
        )
        self.item_score.acquire_item_score_candidates.return_value = {
            "message-1": (101, 2),
            "message-2": (102, 2),
        }
        self.task_runtime.load_task_items.return_value = {
            "message-1": self.item("message-1"),
            "message-2": self.item("message-2"),
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

        with self.assertRaisesRegex(RuntimeError, "queue unavailable"):
            self.dispatch()

        self.assertEqual(
            ["endpoint-1", "endpoint-2"],
            [
                append_call.kwargs["endpoint_manager_id"]
                for append_call in self.dispatch_runtime.append_deliver_seeds.call_args_list
            ],
        )
        self.assertEqual(
            [call.acquire_dispatch_work_tasks(limit=10)],
            self.task_score.method_calls,
        )


if __name__ == "__main__":
    unittest.main()
