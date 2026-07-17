from __future__ import annotations

import inspect
import json
import unittest
from dataclasses import fields
from unittest.mock import Mock, call, patch

from kernel_design.executable_spec import kernel, scheduling
from kernel_design.executable_spec import (
    ResultRoutingConfig,
    ResultRoutingPacer,
    SeedResult,
    SeedResultRuntime,
    TaskDescriptor,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
    TaskResourceCatalog,
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
            claim_score=101,
            worker_lease_score=201,
            task_item_claim_until_millis=105_000,
        )

        encoded = encode_result_context(context)

        self.assertEqual(["context"], list(inspect.signature(encode_result_context).parameters))
        self.assertEqual(
            '{"claimScore":101,"messageId":"message-1","taskId":"task-1",'
            '"taskItemClaimUntilMillis":105000,"workerId":"worker-1",'
            '"workerLeaseScore":201}',
            encoded,
        )
        self.assertEqual(context, decode_result_context(encoded))

    def test_decode_ignores_unknown_fields_and_rejects_invalid_contexts(self) -> None:
        context = ResultContext("task-1", "message-1", "worker-1", 101, 201, 105_000)
        payload = json.loads(encode_result_context(context))
        payload["futureField"] = "ignored"

        self.assertEqual(context, decode_result_context(json.dumps(payload)))
        for invalid in (
            "{bad-json",
            "[]",
            '{"taskId":"task-1"}',
            '{"taskId":"task-1","messageId":"message-1","workerId":"worker-1",'
            '"claimScore":0,"workerLeaseScore":201,"taskItemClaimUntilMillis":105000}',
        ):
            with self.subTest(invalid=invalid):
                self.assertIsNone(decode_result_context(invalid))


class ResultRoutingPacerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        self.runtime = Mock(spec=SeedResultRuntime)
        self.item_score = Mock(spec=TaskItemScoreBandCore)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.task_catalog = Mock(spec=TaskResourceCatalog)
        self.pacer = ResultRoutingPacer(
            self.runtime,
            self.item_score,
            self.worker_score,
            self.task_catalog,
        )
        self.config = ResultRoutingConfig(
            batch_limit=100,
            retry_delay_millis=1_000,
        )

    def route(self) -> int:
        with patch.object(
            ResultRoutingPacer,
            "_current_time_millis",
            return_value=self.NOW_MILLIS,
        ):
            return self.pacer.route_seed_results(config=self.config)

    @staticmethod
    def descriptor(task_id: str, worker_group_id: str) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            allocation_rule={},
            config={
                "priority": "50",
                "maximumCandidateWorkers": "100",
                "runningVisibleMinimumCandidateWorkers": "1",
                "maxRetryTimes": "5",
            },
        )

    @staticmethod
    def result(
        *,
        task_id: str = "task-1",
        message_id: str = "message-1",
        worker_id: str = "worker-1",
        claim_score: int = 101,
        worker_lease_score: int = 201,
        claim_until_millis: int = 105_000,
        outcome_code: str = "200",
    ) -> SeedResult:
        return SeedResult(
            opaque_result_context=encode_result_context(
                ResultContext(
                    task_id=task_id,
                    message_id=message_id,
                    worker_id=worker_id,
                    claim_score=claim_score,
                    worker_lease_score=worker_lease_score,
                    task_item_claim_until_millis=claim_until_millis,
                )
            ),
            outcome_code=outcome_code,
        )

    def test_public_contract(self) -> None:
        self.assertIs(kernel.SeedResultRuntime, SeedResultRuntime)
        self.assertIs(scheduling.ResultRoutingPacer, ResultRoutingPacer)
        self.assertFalse(hasattr(kernel, "ResultRoutingPacer"))
        self.assertEqual(
            [
                "opaque_result_context",
                "outcome_code",
                "opaque_result_payload",
            ],
            [field.name for field in fields(SeedResult)],
        )
        self.assertEqual(
            {"append_seed_results", "consume_seed_results"},
            SeedResultRuntime.__abstractmethods__,
        )
        self.assertEqual(
            ["self", "results"],
            list(inspect.signature(SeedResultRuntime.append_seed_results).parameters),
        )
        self.assertEqual(
            ["self", "limit"],
            list(inspect.signature(SeedResultRuntime.consume_seed_results).parameters),
        )
        for values in ((0, 1), (1, 0), (-1, 1)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                ResultRoutingConfig(*values)

    def test_success_wins_over_failure_and_all_decoded_workers_are_released(self) -> None:
        failure = self.result(outcome_code="1000", worker_lease_score=201)
        success = self.result(outcome_code="200", worker_lease_score=202)
        self.runtime.consume_seed_results.return_value = (failure, success)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self.descriptor("task-1", "image-workers")
        }
        self.item_score.promote_item_outcomes.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                301,
            )
        }

        self.assertEqual(1, self.route())

        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("message-1",),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.NOW_MILLIS,
        )
        self.item_score.rewrite_observed_item_scores.assert_not_called()
        self.assertEqual(
            [
                call(
                    home_bucket_id="image-workers",
                    observed_scores={"worker-1": 201},
                    release_time_millis=self.NOW_MILLIS,
                ),
                call(
                    home_bucket_id="image-workers",
                    observed_scores={"worker-1": 202},
                    release_time_millis=self.NOW_MILLIS,
                ),
            ],
            self.worker_score.release_score_holds.call_args_list,
        )

    def test_later_failure_cannot_replace_retained_success(self) -> None:
        success = self.result(outcome_code="200")
        failure = self.result(outcome_code="1000")
        self.runtime.consume_seed_results.return_value = (success, failure)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": None
        }
        self.item_score.promote_item_outcomes.return_value = {}

        self.route()

        self.item_score.promote_item_outcomes.assert_called_once_with(
            task_id="task-1",
            message_ids=("message-1",),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.NOW_MILLIS,
        )
        self.item_score.rewrite_observed_item_scores.assert_not_called()

    def test_adapter_rejection_retries_item_and_demotes_worker_to_recovery(self) -> None:
        rejection = self.result(outcome_code="3001", worker_lease_score=201)
        self.runtime.consume_seed_results.return_value = (rejection,)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self.descriptor("task-1", "image-workers")
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                301,
            )
        }

        self.assertEqual(1, self.route())

        self.worker_score.demote_observed_worker_leases_to_recovery.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 201},
        )
        self.worker_score.release_score_holds.assert_not_called()

    def test_worker_execution_evidence_wins_over_same_lease_adapter_rejection(
        self,
    ) -> None:
        rejection = self.result(
            message_id="message-1",
            outcome_code="3001",
            worker_lease_score=201,
        )
        worker_failure = self.result(
            message_id="message-2",
            outcome_code="1000",
            worker_lease_score=201,
        )
        self.runtime.consume_seed_results.return_value = (
            rejection,
            worker_failure,
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self.descriptor("task-1", "image-workers")
        }
        self.item_score.rewrite_observed_item_scores.return_value = {}

        self.route()

        self.worker_score.release_score_holds.assert_called_once_with(
            home_bucket_id="image-workers",
            observed_scores={"worker-1": 201},
            release_time_millis=self.NOW_MILLIS,
        )
        self.worker_score.demote_observed_worker_leases_to_recovery.assert_not_called()

    def test_non_200_uses_exact_claim_retry_after_latest_claim_deadline(self) -> None:
        first = self.result(
            message_id="message-1",
            worker_id="worker-1",
            claim_score=101,
            claim_until_millis=104_000,
            outcome_code="1409",
        )
        second = self.result(
            message_id="message-2",
            worker_id="worker-2",
            claim_score=102,
            worker_lease_score=202,
            claim_until_millis=106_000,
            outcome_code="1500",
        )
        self.runtime.consume_seed_results.return_value = (first, second)
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": self.descriptor("task-1", "image-workers")
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.TRANSITIONED,
                401,
            ),
            "message-2": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.STALE,
            ),
        }

        self.assertEqual(1, self.route())

        self.item_score.rewrite_observed_item_scores.assert_called_once_with(
            task_id="task-1",
            observed_scores={"message-1": 101, "message-2": 102},
            target_time_millis=107_000,
            remaining_budget_delta=0,
        )
        self.item_score.promote_item_outcomes.assert_not_called()

    def test_retry_due_is_scoped_per_task(self) -> None:
        self.runtime.consume_seed_results.return_value = (
            self.result(
                task_id="task-1",
                message_id="message-1",
                claim_score=101,
                claim_until_millis=104_000,
                outcome_code="1000",
            ),
            self.result(
                task_id="task-2",
                message_id="message-2",
                worker_id="worker-2",
                claim_score=102,
                worker_lease_score=202,
                claim_until_millis=110_000,
                outcome_code="1000",
            ),
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": None,
            "task-2": None,
        }
        self.item_score.rewrite_observed_item_scores.return_value = {}

        self.route()

        self.assertEqual(
            [
                call(
                    task_id="task-1",
                    observed_scores={"message-1": 101},
                    target_time_millis=105_000,
                    remaining_budget_delta=0,
                ),
                call(
                    task_id="task-2",
                    observed_scores={"message-2": 102},
                    target_time_millis=111_000,
                    remaining_budget_delta=0,
                ),
            ],
            self.item_score.rewrite_observed_item_scores.call_args_list,
        )

    def test_corrupt_context_is_dropped_and_missing_descriptor_only_skips_release(self) -> None:
        valid = self.result(outcome_code="1000")
        self.runtime.consume_seed_results.return_value = (
            SeedResult("{bad-json", "200"),
            valid,
        )
        self.task_catalog.load_task_allocation_descriptors.return_value = {
            "task-1": None
        }
        self.item_score.rewrite_observed_item_scores.return_value = {
            "message-1": TaskItemScoreTransitionResult(
                TaskItemScoreTransitionStatus.NOOP
            )
        }

        self.assertEqual(0, self.route())

        self.runtime.consume_seed_results.assert_called_once_with(limit=100)
        self.worker_score.release_score_holds.assert_not_called()

    def test_empty_or_fully_corrupt_batch_is_bounded_noop(self) -> None:
        for results in ((), (SeedResult("{}", "200"),)):
            with self.subTest(results=results):
                self.runtime.reset_mock()
                self.item_score.reset_mock()
                self.worker_score.reset_mock()
                self.task_catalog.reset_mock()
                self.runtime.consume_seed_results.return_value = results

                self.assertEqual(0, self.route())

                self.item_score.promote_item_outcomes.assert_not_called()
                self.item_score.rewrite_observed_item_scores.assert_not_called()
                self.task_catalog.load_task_allocation_descriptors.assert_not_called()
                self.worker_score.release_score_holds.assert_not_called()


if __name__ == "__main__":
    unittest.main()
