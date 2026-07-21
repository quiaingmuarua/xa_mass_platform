from __future__ import annotations

import inspect
import json
import unittest
from unittest.mock import Mock, call, patch

from kernel_design.executable_spec import (
    TaskType,
    CandidateWorkerEntry,
    DeliverSeedRuntime,
    TaskDescriptor,
    TaskItem,
    TaskItemDispatchConfig,
    TaskItemDispatchPacer,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
    TaskResourceCatalog,
    TaskRuntime,
    TaskScoreBandCore,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
)


class TaskItemDispatchPacerTest(unittest.TestCase):
    NOW_MILLIS = 10_000
    CLAIM_UNTIL_MILLIS = 15_000

    def setUp(self) -> None:
        self.task_score = Mock(spec=TaskScoreBandCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.deliver_seed_runtime = Mock(spec=DeliverSeedRuntime)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.candidate_acquirer = Mock(spec=WorkerCandidateAcquirer)
        self.pacer = TaskItemDispatchPacer(
            self.task_score,
            self.task_catalog,
            self.deliver_seed_runtime,
            self.item_score,
            self.task_runtime,
            self.candidate_acquirer,
        )
        self.config = TaskItemDispatchConfig(
            task_batch_limit=10,
            per_task_dispatch_limit=3,
            item_claim_lease_duration_millis=5_000,
        )

    def test_contract_has_no_candidate_cache_or_worker_score_dependency(self) -> None:
        self.assertEqual(
            {
                "task_score",
                "task_catalog",
                "deliver_seed_runtime",
                "item_score",
                "task_runtime",
                "candidate_acquirer",
                "delivery_item_encoder",
            },
            set(inspect.signature(TaskItemDispatchPacer).parameters),
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
            self._candidate("worker-1", "endpoint-1", 201),
            self._candidate("worker-2", "endpoint-2", 202),
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
        self.deliver_seed_runtime.append_deliver_seeds.side_effect = (
            lambda **_: events.append("publish")
        )

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_task_items(config=self.config)

        self.assertEqual(2, appended)
        self.assertEqual(
            ["observe", "acquire", "claim", "publish", "publish"],
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
        self.assertEqual(2, self.deliver_seed_runtime.append_deliver_seeds.call_count)
        first_seed = self.deliver_seed_runtime.append_deliver_seeds.call_args_list[
            0
        ].kwargs["deliver_seeds"][0]
        self.assertEqual("worker-1", first_seed.worker_id)
        self.assertEqual(
            {"eventCode": "event-1", "payload": {"value": "message-1"}},
            json.loads(first_seed.opaque_delivery_item),
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
            "task-1": (self._candidate("worker-1", "endpoint-1", 201),)
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
            appended = self.pacer.dispatch_task_items(config=self.config)

        self.assertEqual(1, appended)
        self.item_score.rewrite_observed_item_scores.assert_called_once_with(
            task_id="task-1",
            observed_scores={"message-1": 101},
            target_time_millis=self.CLAIM_UNTIL_MILLIS,
            remaining_budget_delta=-1,
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
            appended = self.pacer.dispatch_task_items(config=self.config)

        self.assertEqual(0, appended)
        self.item_score.rewrite_observed_item_scores.assert_not_called()
        self.deliver_seed_runtime.append_deliver_seeds.assert_not_called()

    def test_item_driven_task_uses_targeted_requests_without_flattening(self) -> None:
        self._prepare_task(
            "task-1",
            task_type=TaskType.ITEM_DRIVEN,
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
            "message-1": (self._candidate("worker-1", "endpoint-1", 201),),
            "message-2": (self._candidate("worker-2", "endpoint-2", 202),),
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
            appended = self.pacer.dispatch_task_items(config=self.config)

        self.assertEqual(2, appended)
        acquisition_call = self.candidate_acquirer.acquire_worker_candidates.call_args
        self.assertIs(
            WorkerCandidateAcquisitionStrategy.TARGETED,
            acquisition_call.kwargs["strategy"],
        )
        targeted_request = acquisition_call.kwargs["candidate_requests"]["message-2"]
        self.assertEqual("workerId", targeted_request.target_field)
        self.assertEqual(
            {"workerId": {"$eq": "worker-2"}},
            targeted_request.allocation_rule,
        )
        published = {
            json.loads(call_.kwargs["deliver_seeds"][0].opaque_delivery_item)[
                "payload"
            ]["value"]: call_.kwargs["deliver_seeds"][0].worker_id
            for call_ in self.deliver_seed_runtime.append_deliver_seeds.call_args_list
        }
        self.assertEqual(
            {
                "message-1": "worker-1",
                "message-2": "worker-2",
            },
            published,
        )

    def test_exhausted_and_missing_records_do_not_request_workers(self) -> None:
        self._prepare_task("task-1")
        self.item_score.acquire_item_score_candidates.return_value = {
            "exhausted": (101, 0),
            "missing": (102, 2),
        }
        self.task_runtime.load_task_items.return_value = {"missing": None}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_task_items(config=self.config)

        self.assertEqual(0, appended)
        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("exhausted",),
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.NOW_MILLIS,
        )
        self.candidate_acquirer.acquire_worker_candidates.assert_not_called()

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
            appended = self.pacer.dispatch_task_items(config=self.config)

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
        self.item_score.acquire_item_score_candidates.return_value = {}

        with patch.object(
            self.pacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            appended = self.pacer.dispatch_task_items(config=self.config)

        self.assertEqual(0, appended)
        self.assertEqual(
            [
                call(task_id="task-2", limit=3),
                call(task_id="task-1", limit=3),
            ],
            self.item_score.acquire_item_score_candidates.call_args_list,
        )

    def test_non_positive_config_is_rejected(self) -> None:
        invalid = ((0, 1, 1), (1, 0, 1), (1, 1, 0))
        for values in invalid:
            with self.subTest(values=values), self.assertRaises(ValueError):
                TaskItemDispatchConfig(
                    task_batch_limit=values[0],
                    per_task_dispatch_limit=values[1],
                    item_claim_lease_duration_millis=values[2],
                )

    def _prepare_task(
        self,
        task_id: str,
        *,
        task_type: TaskType = TaskType.TASK_DRIVEN,
    ) -> None:
        self.task_score.acquire_dispatch_work_tasks.return_value = (task_id,)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._descriptor(
                task_id,
                task_type=task_type,
            )
        }

    @staticmethod
    def _descriptor(
        task_id: str,
        *,
        task_type: TaskType = TaskType.TASK_DRIVEN,
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id="group-1",
            task_type=task_type,
            allocation_rule=(
                {"attributes.runtime": {"$eq": "python"}}
                if task_type is TaskType.TASK_DRIVEN
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
    ) -> TaskItem:
        return TaskItem(
            message_id=message_id,
            event_code="event-1",
            created_at_millis=1,
            payload={"value": message_id},
            allocation_rule=allocation_rule,
        )

    @staticmethod
    def _candidate(
        worker_id: str,
        endpoint_manager_id: str,
        score: int,
    ) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="group-1",
            endpoint_manager_id=endpoint_manager_id,
            worker_lease_score=score,
        )


if __name__ == "__main__":
    unittest.main()
