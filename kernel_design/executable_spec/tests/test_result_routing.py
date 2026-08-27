from __future__ import annotations

import inspect
import json
import unittest
from dataclasses import fields
from unittest.mock import Mock, call

from kernel_design.executable_spec import kernel, scheduling
from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskResultBatchPolicy,
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


class TaskResultBatchPolicyTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.clock_values = iter((self.NOW_MILLIS,) * 20)
        self.policy = TaskResultBatchPolicy(
            task_runtime=self.task_runtime,
            item_score=self.item_score,
            worker_score=self.worker_score,
            clock_millis=lambda: next(self.clock_values),
        )

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

    def test_public_contract_is_pure_fixed_batch_policy(self) -> None:
        self.assertIs(kernel.TaskResultRuntime, TaskResultRuntime)
        self.assertIs(kernel.TaskResultClass, TaskResultClass)
        self.assertIs(scheduling.TaskResultBatchPolicy, TaskResultBatchPolicy)
        self.assertIs(scheduling.TaskResultEvidence, TaskResultEvidence)
        self.assertIs(scheduling.WorkerResultEvidence, WorkerResultEvidence)
        self.assertEqual(
            {"append_task_results", "consume_task_results"},
            TaskResultRuntime.__abstractmethods__,
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

    def test_success_preserves_store_promote_release_and_last_semantics(self) -> None:
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

        self.policy.handle_success((
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
        ))

        self.assertEqual(
            ["store", "promote", "store", "promote", "completed-release"],
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

    def test_failure_only_releases_and_does_not_read_outcome_code(self) -> None:
        self.policy.handle_failure((
            self.result(outcome_code="200", payload="unexpected"),
            self.result(outcome_code="23002", worker_lease_score=202),
        ))

        self.worker_score.release_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 202},
            release_time_millis=self.NOW_MILLIS,
        )
        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.worker_score.release_completed_hot_score_holds.assert_not_called()

    def test_corrupt_context_and_non_task_result_do_not_write_owners(self) -> None:
        invalid = (
            self.result(forward="{bad-json"),
            self.result(dst=DeliveryEndpoint.SYSTEM),
        )

        self.policy.handle_success(invalid)
        self.policy.handle_failure(invalid)

        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.worker_score.release_score_holds.assert_not_called()
        self.worker_score.release_completed_hot_score_holds.assert_not_called()


if __name__ == "__main__":
    unittest.main()
