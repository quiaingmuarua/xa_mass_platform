from __future__ import annotations

import inspect
import json
import unittest
from dataclasses import fields
from unittest.mock import Mock, call, patch

from kernel_design.executable_spec import kernel, scheduling
from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
    ResultRoutingBuiltinPolicies,
    ResultRoutingConfig,
    ResultRoutingPacer,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskResultClass,
    TaskResultEvidence,
    TaskResultRuntime,
    TaskRuntime,
    WorkerResultEvidence,
    WorkerScoreCore,
)
from kernel_design.executable_spec.kernel.result_context import (
    ResultContext,
    decode_result_context,
    encode_result_context,
)


class ResultContextCodecTest(unittest.TestCase):
    def test_round_trip_is_deterministic_and_uses_one_context_parameter(self) -> None:
        context = ResultContext(
            task_id="task-1",
            message_id="message-1",
            worker_id="worker-1",
            worker_group_id="image-workers",
            worker_lease_score=201,
        )

        encoded = encode_result_context(context)

        self.assertEqual(
            ["context"],
            list(inspect.signature(encode_result_context).parameters),
        )
        self.assertEqual(
            '{"messageId":"message-1","taskId":"task-1",'
            '"workerGroupId":"image-workers",'
            '"workerId":"worker-1",'
            '"workerLeaseScore":201}',
            encoded,
        )
        self.assertEqual(context, decode_result_context(encoded))

    def test_decode_ignores_unknown_fields_and_rejects_invalid_contexts(self) -> None:
        context = ResultContext(
            "task-1",
            "message-1",
            "worker-1",
            "image-workers",
            201,
        )
        payload = json.loads(encode_result_context(context))
        payload["futureField"] = "ignored"

        self.assertEqual(context, decode_result_context(json.dumps(payload)))
        for invalid in (
            "{bad-json",
            "[]",
            '{"taskId":"task-1"}',
            '{"taskId":"task-1","messageId":"message-1",'
            '"workerId":"worker-1","workerLeaseScore":201}',
            '{"taskId":"task-1","messageId":"message-1",'
            '"workerId":"worker-1","workerGroupId":"image-workers",'
            '"workerLeaseScore":0}',
        ):
            with self.subTest(invalid=invalid):
                self.assertIsNone(decode_result_context(invalid))


class ResultRoutingPacerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.runtime = Mock(spec=TaskResultRuntime)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.queues: dict[
            TaskResultClass, tuple[DeliveryReport, ...]
        ] = {}
        self.runtime.consume_task_results.side_effect = (
            lambda *, result_class, limit: self.queues.get(
                result_class,
                (),
            )
        )
        self.pacer = ResultRoutingPacer(
            self.runtime,
            task_runtime=self.task_runtime,
            item_score=self.item_score,
            worker_score=self.worker_score,
        )
        self.config = ResultRoutingConfig(per_result_class_batch_limit=100)

    def route(self) -> int:
        with patch.object(
            ResultRoutingPacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ), patch.object(
            ResultRoutingBuiltinPolicies,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            return self.pacer.route_worker_results(config=self.config)

    @staticmethod
    def result(
        *,
        task_id: str = "task-1",
        message_id: str = "message-1",
        worker_id: str = "worker-1",
        worker_group_id: str = "image-workers",
        worker_lease_score: int = 201,
        outcome_code: str = "200",
        payload: str = '{"value":1}',
        dst: DeliveryEndpoint = DeliveryEndpoint.TASK,
        forward: str | None = None,
    ) -> DeliveryReport:
        return DeliveryReport.create(
            src=DeliveryEndpoint.WORKER,
            source_id=worker_id,
            dst=dst,
            message_type="test.event",
            outcome_code=outcome_code,
            payload=payload,
            forward=forward or encode_result_context(
                ResultContext(
                    task_id=task_id,
                    message_id=message_id,
                    worker_id=worker_id,
                    worker_group_id=worker_group_id,
                    worker_lease_score=worker_lease_score,
                )
            ),
        )

    def test_public_contract_is_fixed_to_two_result_lanes(self) -> None:
        self.assertIs(kernel.TaskResultRuntime, TaskResultRuntime)
        self.assertIs(kernel.TaskResultClass, TaskResultClass)
        self.assertIs(scheduling.ResultRoutingPacer, ResultRoutingPacer)
        self.assertIs(scheduling.TaskResultEvidence, TaskResultEvidence)
        self.assertIs(scheduling.WorkerResultEvidence, WorkerResultEvidence)
        self.assertFalse(hasattr(scheduling, "TaskResultHandler"))
        self.assertFalse(hasattr(scheduling, "WorkerResultHandler"))
        self.assertEqual(
            [
                "self",
                "task_result_runtime",
                "task_runtime",
                "item_score",
                "worker_score",
            ],
            list(inspect.signature(ResultRoutingPacer.__init__).parameters),
        )
        self.assertEqual(
            {"append_task_results", "consume_task_results"},
            TaskResultRuntime.__abstractmethods__,
        )
        self.assertEqual(
            ["self", "result_class", "limit"],
            list(
                inspect.signature(
                    TaskResultRuntime.consume_task_results
                ).parameters
            ),
        )
        self.assertEqual(
            ["SUCCESS", "FAILURE"],
            [value.name for value in TaskResultClass],
        )
        self.assertEqual(
            [
                "src",
                "source_id",
                "dst",
                "message_type",
                "outcome_code",
                "payload",
                "forward",
            ],
            [field.name for field in fields(DeliveryReport)],
        )
        with self.assertRaises(ValueError):
            ResultRoutingConfig(0)

    def test_success_lane_trusts_lane_and_preserves_store_promote_release_order(
        self,
    ) -> None:
        self.queues[TaskResultClass.SUCCESS] = (
            self.result(payload='{"version":1}'),
            self.result(
                outcome_code="3500",
                payload='{"version":2}',
                worker_lease_score=202,
            ),
            self.result(
                task_id="task-2",
                message_id="message-2",
                worker_id="worker-2",
                worker_lease_score=203,
                payload="null",
            ),
        )
        owner_order: list[str] = []
        self.task_runtime.store_task_item_success_results.side_effect = (
            lambda **_kwargs: owner_order.append("store")
        )
        self.item_score.promote_item_outcomes.side_effect = (
            lambda **_kwargs: owner_order.append("promote") or {}
        )
        self.worker_score.release_completed_hot_score_holds.side_effect = (
            lambda **_kwargs: owner_order.append("completed-release") or {}
        )

        self.assertEqual(3, self.route())

        self.assertEqual(
            [
                "store",
                "promote",
                "store",
                "promote",
                "completed-release",
            ],
            owner_order,
        )
        self.assertEqual(
            call(task_id="task-1", results={"message-1": '{"version":2}'}),
            self.task_runtime.store_task_item_success_results.call_args_list[0],
        )
        self.item_score.promote_item_outcomes.assert_any_call(
            task_id="task-1",
            message_ids=("message-1",),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.NOW_MILLIS,
        )
        self.worker_score.release_completed_hot_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_hot_scores={"worker-1": 202, "worker-2": 203},
            release_time_millis=self.NOW_MILLIS,
        )
        self.worker_score.release_score_holds.assert_not_called()

    def test_failure_lane_only_releases_and_does_not_read_outcome_code(self) -> None:
        self.queues[TaskResultClass.FAILURE] = (
            self.result(outcome_code="200", payload="unexpected"),
            self.result(outcome_code="23002", worker_lease_score=202),
        )

        self.assertEqual(2, self.route())

        self.worker_score.release_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 202},
            release_time_millis=self.NOW_MILLIS,
        )
        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.worker_score.release_completed_hot_score_holds.assert_not_called()

    def test_each_lane_uses_its_own_bounded_consume_in_fixed_order(self) -> None:
        self.assertEqual(0, self.route())

        self.assertEqual(
            [
                call(result_class=TaskResultClass.SUCCESS, limit=100),
                call(result_class=TaskResultClass.FAILURE, limit=100),
            ],
            self.runtime.consume_task_results.call_args_list,
        )

    def test_corrupt_context_and_non_task_result_are_consumed_without_writes(
        self,
    ) -> None:
        self.queues[TaskResultClass.SUCCESS] = (
            self.result(forward="{bad-json"),
            self.result(dst=DeliveryEndpoint.SYSTEM),
        )

        self.assertEqual(0, self.route())

        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.worker_score.release_score_holds.assert_not_called()
        self.worker_score.release_completed_hot_score_holds.assert_not_called()

    def test_worker_release_uses_fresh_policy_time_after_round_time(self) -> None:
        self.queues[TaskResultClass.SUCCESS] = (self.result(),)
        release_time_millis = self.NOW_MILLIS + WorkerScoreCore.SLOT_MILLIS

        with patch.object(
            ResultRoutingPacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ), patch.object(
            ResultRoutingBuiltinPolicies,
            "_current_time_millis",
            return_value=release_time_millis,
        ):
            self.assertEqual(
                1,
                self.pacer.route_worker_results(config=self.config),
            )

        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("message-1",),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.NOW_MILLIS,
        )
        self.worker_score.release_completed_hot_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_hot_scores={"worker-1": 201},
            release_time_millis=release_time_millis,
        )


if __name__ == "__main__":
    unittest.main()
