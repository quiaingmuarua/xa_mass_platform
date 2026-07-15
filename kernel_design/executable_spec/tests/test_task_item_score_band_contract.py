from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreState,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
)


class TaskItemScoreBandContractTest(unittest.TestCase):
    def test_score_state_exposes_budget_semantics_not_suffix_encoding(self) -> None:
        state = TaskItemScoreState(
            score=101,
            band=TaskItemScoreBand.ACTIVE,
            time_millis=1_000,
            remaining_budget=3,
        )

        self.assertEqual(
            {field.name for field in fields(TaskItemScoreState)},
            {"score", "band", "time_millis", "remaining_budget"},
        )
        self.assertFalse(hasattr(state, "suffix"))
        self.assertFalse(hasattr(state, "message_id"))

    def test_transition_result_carries_only_status_and_opaque_score(self) -> None:
        result = TaskItemScoreTransitionResult(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            201,
        )

        self.assertEqual(201, result.score)
        self.assertEqual(
            {
                "transitioned",
                "noop",
                "stale",
                "not_found",
                "invalid",
                "corrupt",
            },
            {status.value for status in TaskItemScoreTransitionStatus},
        )

    def test_core_surface_has_only_two_mutation_classes(self) -> None:
        expected_methods = {
            "initialize_item_scores",
            "acquire_item_score_candidates",
            "rewrite_observed_item_scores",
            "promote_item_outcomes",
            "get_item_score_states",
        }
        self.assertEqual(expected_methods, TaskItemScoreBandCore.__abstractmethods__)
        self.assertEqual(
            {
                "self",
                "task_id",
                "initial_due_millis_by_message_id",
                "max_retry_times",
            },
            set(
                inspect.signature(
                    TaskItemScoreBandCore.initialize_item_scores
                ).parameters
            ),
        )
        self.assertEqual(
            {"self", "task_id", "limit"},
            set(
                inspect.signature(
                    TaskItemScoreBandCore.acquire_item_score_candidates
                ).parameters
            ),
        )
        self.assertEqual(
            {
                "self",
                "task_id",
                "observed_scores",
                "target_time_millis",
                "remaining_budget_delta",
            },
            set(
                inspect.signature(
                    TaskItemScoreBandCore.rewrite_observed_item_scores
                ).parameters
            ),
        )
        self.assertEqual(
            {
                "self",
                "task_id",
                "message_ids",
                "target_band",
                "target_time_millis",
            },
            set(
                inspect.signature(
                    TaskItemScoreBandCore.promote_item_outcomes
                ).parameters
            ),
        )

    def test_removed_operation_specific_contracts_do_not_survive(self) -> None:
        self.assertFalse(hasattr(TaskItemScoreBandCore, "claim_observed_items"))
        self.assertFalse(hasattr(TaskItemScoreBandCore, "retry_observed_claim"))
        self.assertFalse(hasattr(executable_spec, "TaskItemClaimResult"))
        self.assertFalse(hasattr(executable_spec, "TaskItemClaimStatus"))
        self.assertFalse(hasattr(executable_spec, "TaskItemFinalOutcome"))

    def test_contracts_are_package_exports(self) -> None:
        self.assertIs(executable_spec.TaskItemScoreBand, TaskItemScoreBand)
        self.assertIs(executable_spec.TaskItemScoreBandCore, TaskItemScoreBandCore)
        self.assertIs(executable_spec.TaskItemScoreState, TaskItemScoreState)
        self.assertIs(
            executable_spec.TaskItemScoreTransitionResult,
            TaskItemScoreTransitionResult,
        )
        self.assertIs(
            executable_spec.TaskItemScoreTransitionStatus,
            TaskItemScoreTransitionStatus,
        )


if __name__ == "__main__":
    unittest.main()
