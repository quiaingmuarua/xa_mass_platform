from __future__ import annotations

import inspect
import json
import unittest
from dataclasses import fields
from unittest.mock import Mock, call, patch

from kernel_design.executable_spec import kernel, scheduling
from kernel_design.executable_spec import (
    ResultRoutingConfig,
    ResultRoutingBuiltinPolicies,
    ResultRoutingPacer,
    SeedResult,
    SeedResultOutcomeClass,
    SeedResultRuntime,
    TaskResultEvidence,
    TaskResultHandler,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskRuntime,
    WorkerScoreCore,
    WorkerResultEvidence,
    WorkerResultHandler,
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

        self.assertEqual(["context"], list(inspect.signature(encode_result_context).parameters))
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
            '{"taskId":"task-1","messageId":"message-1","workerId":"worker-1",'
            '"workerLeaseScore":201}',
            '{"taskId":"task-1","messageId":"message-1","workerId":"worker-1",'
            '"workerGroupId":"image-workers",'
            '"workerLeaseScore":0}',
        ):
            with self.subTest(invalid=invalid):
                self.assertIsNone(decode_result_context(invalid))


class ResultRoutingPacerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.runtime = Mock(spec=SeedResultRuntime)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.task_runtime = Mock(spec=TaskRuntime)
        self.queues: dict[SeedResultOutcomeClass, tuple[SeedResult, ...]] = {}
        self.runtime.consume_seed_results.side_effect = (
            lambda *, outcome_class, limit: self.queues.get(outcome_class, ())
        )
        self.builtin_policies = ResultRoutingBuiltinPolicies(
            task_runtime=self.task_runtime,
            item_score=self.item_score,
            worker_score=self.worker_score,
        )
        self.pacer = ResultRoutingPacer(
            self.runtime,
            task_result_handlers=self.builtin_policies.default_task_result_handlers(),
            worker_result_handlers=self.builtin_policies.default_worker_result_handlers(),
        )
        self.config = ResultRoutingConfig(per_outcome_batch_limit=100)

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
            return self.pacer.route_seed_results(config=self.config)

    @staticmethod
    def result(
        *,
        task_id: str = "task-1",
        message_id: str = "message-1",
        worker_id: str = "worker-1",
        worker_group_id: str = "image-workers",
        worker_lease_score: int = 201,
        outcome_code: str = "200",
        payload: str | None = '{"value":1}',
    ) -> SeedResult:
        return SeedResult(
            opaque_result_context=encode_result_context(
                ResultContext(
                    task_id=task_id,
                    message_id=message_id,
                    worker_id=worker_id,
                    worker_group_id=worker_group_id,
                    worker_lease_score=worker_lease_score,
                )
            ),
            outcome_code=outcome_code,
            opaque_result_payload=(payload if outcome_code == "200" else None),
        )

    def test_public_contract(self) -> None:
        self.assertIs(kernel.SeedResultRuntime, SeedResultRuntime)
        self.assertIs(scheduling.ResultRoutingPacer, ResultRoutingPacer)
        self.assertIs(scheduling.TaskResultEvidence, TaskResultEvidence)
        self.assertIs(scheduling.TaskResultHandler, TaskResultHandler)
        self.assertIs(scheduling.WorkerResultEvidence, WorkerResultEvidence)
        self.assertIs(scheduling.WorkerResultHandler, WorkerResultHandler)
        self.assertIs(
            scheduling.ResultRoutingBuiltinPolicies,
            ResultRoutingBuiltinPolicies,
        )
        self.assertFalse(hasattr(kernel, "ResultRoutingPacer"))
        self.assertEqual(
            [
                "self",
                "seed_result_runtime",
                "task_result_handlers",
                "worker_result_handlers",
            ],
            list(inspect.signature(ResultRoutingPacer.__init__).parameters),
        )
        self.assertEqual(
            ["opaque_result_context", "outcome_code", "opaque_result_payload"],
            [field.name for field in fields(SeedResult)],
        )
        self.assertEqual(
            {"append_seed_results", "consume_seed_results"},
            SeedResultRuntime.__abstractmethods__,
        )
        self.assertEqual(
            ["task_id", "message_id", "opaque_result_payload"],
            [field.name for field in fields(TaskResultEvidence)],
        )
        self.assertEqual(
            [
                "worker_id",
                "worker_lease_score",
            ],
            [field.name for field in fields(WorkerResultEvidence)],
        )
        self.assertEqual(
            ["self", "task_id", "results", "result_time_millis"],
            list(inspect.signature(TaskResultHandler.__call__).parameters),
        )
        self.assertEqual(
            ["self", "worker_group_id", "results", "result_time_millis"],
            list(inspect.signature(WorkerResultHandler.__call__).parameters),
        )
        self.assertEqual(
            ["self", "outcome_class", "limit"],
            list(inspect.signature(SeedResultRuntime.consume_seed_results).parameters),
        )
        with self.assertRaises(ValueError):
            ResultRoutingConfig(0)

    def test_owner_result_handlers_are_replaceable_stable_contracts(self) -> None:
        task_handler = Mock()
        worker_handlers = {
            outcome_class: Mock()
            for outcome_class in SeedResultOutcomeClass
        }
        pacer = ResultRoutingPacer(
            self.runtime,
            task_result_handlers={
                SeedResultOutcomeClass.SUCCESS: task_handler,
            },
            worker_result_handlers=worker_handlers,
        )
        self.queues[SeedResultOutcomeClass.SUCCESS] = (self.result(),)

        with patch.object(
            ResultRoutingPacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            self.assertEqual(1, pacer.route_seed_results(config=self.config))

        task_handler.assert_called_once_with(
            task_id="task-1",
            results=(
                TaskResultEvidence(
                    task_id="task-1",
                    message_id="message-1",
                    opaque_result_payload='{"value":1}',
                ),
            ),
            result_time_millis=self.NOW_MILLIS,
        )
        worker_handlers[SeedResultOutcomeClass.SUCCESS].assert_called_once_with(
            worker_group_id="image-workers",
            results=(
                WorkerResultEvidence(
                    worker_id="worker-1",
                    worker_lease_score=201,
                ),
            ),
            result_time_millis=self.NOW_MILLIS,
        )
        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.worker_score.release_score_holds.assert_not_called()

    def test_builtin_policy_mappings_expose_composable_named_methods(self) -> None:
        self.assertEqual(
            {
                SeedResultOutcomeClass.SUCCESS: self.builtin_policies.store_task_success_results,
            },
            self.builtin_policies.default_task_result_handlers(),
        )
        self.assertEqual(
            {
                SeedResultOutcomeClass.SUCCESS: self.builtin_policies.release_worker_score_holds,
                SeedResultOutcomeClass.WORKER_FAILURE: self.builtin_policies.release_worker_score_holds,
                SeedResultOutcomeClass.ADAPTER_REJECTION: self.builtin_policies.demote_worker_score_holds_to_recovery,
            },
            self.builtin_policies.default_worker_result_handlers(),
        )

    def test_owner_result_handler_coverage_is_required(self) -> None:
        with self.assertRaisesRegex(ValueError, "Task result handlers"):
            ResultRoutingPacer(
                self.runtime,
                task_result_handlers={},
                worker_result_handlers=self.builtin_policies.default_worker_result_handlers(),
            )
        with self.assertRaisesRegex(ValueError, "Worker result handlers"):
            ResultRoutingPacer(
                self.runtime,
                task_result_handlers=self.builtin_policies.default_task_result_handlers(),
                worker_result_handlers={
                    SeedResultOutcomeClass.SUCCESS: Mock(),
                },
            )

    def test_success_results_are_last_write_grouped_by_task_and_stored_first(self) -> None:
        self.queues[SeedResultOutcomeClass.SUCCESS] = (
            self.result(payload='{"version":1}'),
            self.result(payload='{"version":2}', worker_lease_score=202),
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

        self.assertEqual(3, self.route())

        self.assertEqual(["store", "promote", "store", "promote"], owner_order)
        self.assertEqual(
            call(task_id="task-1", results={"message-1": '{"version":2}'}),
            self.task_runtime.store_task_item_success_results.call_args_list[0],
        )
        self.assertEqual(
            call(task_id="task-2", results={"message-2": "null"}),
            self.task_runtime.store_task_item_success_results.call_args_list[1],
        )
        self.assertEqual(
            call(
                task_id="task-1",
                message_ids=("message-1",),
                target_band=TaskItemScoreBand.FINAL_SUCCESS,
                target_time_millis=self.NOW_MILLIS,
            ),
            self.item_score.promote_item_outcomes.call_args_list[0],
        )
        self.item_score.rewrite_observed_item_scores.assert_not_called()
        self.assertEqual(
            [
                call(
                    home_bucket_id="image-workers",
                    observed_scores={"worker-1": 202, "worker-2": 203},
                    release_time_millis=self.NOW_MILLIS,
                ),
            ],
            self.worker_score.release_score_holds.call_args_list,
        )

    def test_worker_failure_only_releases_worker_lease(self) -> None:
        failure = self.result(outcome_code="1500", payload=None)
        self.queues[SeedResultOutcomeClass.WORKER_FAILURE] = (failure,)

        self.assertEqual(1, self.route())

        self.worker_score.release_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 201},
            release_time_millis=self.NOW_MILLIS,
        )
        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.item_score.rewrite_observed_item_scores.assert_not_called()

    def test_worker_release_uses_fresh_policy_time_after_round_time(self) -> None:
        self.queues[SeedResultOutcomeClass.SUCCESS] = (self.result(),)
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
            self.assertEqual(1, self.pacer.route_seed_results(config=self.config))

        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("message-1",),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.NOW_MILLIS,
        )
        self.worker_score.release_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 201},
            release_time_millis=release_time_millis,
        )

    def test_worker_failure_batches_each_worker_group_once(self) -> None:
        self.queues[SeedResultOutcomeClass.WORKER_FAILURE] = (
            self.result(outcome_code="1500", payload=None),
            self.result(
                task_id="task-2",
                message_id="message-2",
                worker_id="worker-2",
                worker_group_id="gpu-workers",
                worker_lease_score=202,
                outcome_code="1500",
                payload=None,
            ),
        )

        self.assertEqual(2, self.route())

        self.assertEqual(
            [
                call(
                    home_bucket_id="image-workers",
                    observed_scores={"worker-1": 201},
                    release_time_millis=self.NOW_MILLIS,
                ),
                call(
                    home_bucket_id="gpu-workers",
                    observed_scores={"worker-2": 202},
                    release_time_millis=self.NOW_MILLIS,
                ),
            ],
            self.worker_score.release_score_holds.call_args_list,
        )

    def test_adapter_rejection_only_demotes_worker_lease(self) -> None:
        rejection = self.result(outcome_code="3001", payload=None)
        self.queues[SeedResultOutcomeClass.ADAPTER_REJECTION] = (rejection,)

        self.assertEqual(1, self.route())

        self.worker_score.demote_observed_worker_leases_to_recovery.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 201},
        )
        self.worker_score.release_score_holds.assert_not_called()
        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()

    def test_same_lease_outcomes_are_submitted_independently_to_score_owner(self) -> None:
        self.queues[SeedResultOutcomeClass.SUCCESS] = (self.result(),)
        self.queues[SeedResultOutcomeClass.ADAPTER_REJECTION] = (
            self.result(outcome_code="3001", payload=None),
        )

        self.assertEqual(2, self.route())

        self.worker_score.release_score_holds.assert_called_once()
        self.worker_score.demote_observed_worker_leases_to_recovery.assert_called_once()

    def test_result_context_supplies_worker_disposition_bucket(self) -> None:
        self.queues[SeedResultOutcomeClass.SUCCESS] = (
            self.result(worker_group_id="gpu-workers"),
        )

        self.assertEqual(1, self.route())

        self.task_runtime.store_task_item_success_results.assert_called_once()
        self.item_score.promote_item_outcomes.assert_called_once()
        self.worker_score.release_score_holds.assert_called_once_with(
            home_bucket_id="gpu-workers",
            observed_scores={"worker-1": 201},
            release_time_millis=self.NOW_MILLIS,
        )

    def test_corrupt_or_misrouted_results_are_consumed_without_owner_writes(self) -> None:
        self.queues[SeedResultOutcomeClass.SUCCESS] = (
            SeedResult("{bad-json", "200", "null"),
            self.result(outcome_code="1000", payload=None),
        )

        self.assertEqual(0, self.route())

        self.task_runtime.store_task_item_success_results.assert_not_called()
        self.item_score.promote_item_outcomes.assert_not_called()
        self.worker_score.release_score_holds.assert_not_called()
        self.worker_score.demote_observed_worker_leases_to_recovery.assert_not_called()

    def test_each_lane_uses_its_own_bounded_consume(self) -> None:
        self.assertEqual(0, self.route())

        self.assertEqual(
            [
                call(
                    outcome_class=SeedResultOutcomeClass.SUCCESS,
                    limit=100,
                ),
                call(
                    outcome_class=SeedResultOutcomeClass.WORKER_FAILURE,
                    limit=100,
                ),
                call(
                    outcome_class=SeedResultOutcomeClass.ADAPTER_REJECTION,
                    limit=100,
                ),
            ],
            self.runtime.consume_seed_results.call_args_list,
        )


if __name__ == "__main__":
    unittest.main()
