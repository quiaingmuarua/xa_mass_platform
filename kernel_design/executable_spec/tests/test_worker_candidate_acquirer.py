from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock

from kernel_design.executable_spec import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
    WorkerScoreCore,
    WorkerScoreTransitionResult,
    WorkerScoreTransitionStatus,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
    WorkerCandidateMatcher,
    WorkerCandidateRequest,
)


class WorkerCandidateAcquirerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cache = Mock(spec=CandidateWorkerCache)
        self.score = Mock(spec=WorkerScoreCore)
        self.matcher = Mock(spec=WorkerCandidateMatcher)
        self.matcher.filter_candidate_worker_ids.side_effect = (
            lambda **kwargs: {
                candidate_id: tuple(worker_ids)
                for candidate_id, worker_ids in kwargs[
                    "candidate_worker_ids"
                ].items()
            }
        )
        self.acquirer = WorkerCandidateAcquirer(
            self.cache,
            self.score,
            self.matcher,
            worker_scan_limit=25,
        )

    def test_contract_is_group_local_and_strategy_explicit(self) -> None:
        self.assertEqual(
            tuple(
                strategy.value
                for strategy in WorkerCandidateAcquisitionStrategy
            ),
            ("PRECOMPUTED", "DIRECT"),
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerCandidateAcquirer.acquire_worker_candidates
                ).parameters
            ),
            {
                "self",
                "strategy",
                "worker_group_id",
                "candidate_requests",
                "lease_until_millis",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerCandidateAcquirer.acquire_hot_pool_candidates
                ).parameters
            ),
            {
                "self",
                "worker_group_id",
                "candidate_requests",
                "lease_until_millis",
            },
        )

    def test_request_contains_only_complete_allocation_rule(self) -> None:
        request = WorkerCandidateRequest(
            priority=5,
            requested_count=2,
            allocation_rule={"worker.region": {"$eq": "cn-east"}},
        )
        self.assertEqual(
            set(request.__dataclass_fields__),
            {"priority", "requested_count", "allocation_rule"},
        )

    def test_request_validates_priority_count_and_rule(self) -> None:
        for priority in (0, 99):
            WorkerCandidateRequest(priority, 1, {})
        for priority in (-1, 100, True):
            with self.subTest(priority=priority), self.assertRaises(ValueError):
                WorkerCandidateRequest(priority, 1, {})
        with self.assertRaises(ValueError):
            WorkerCandidateRequest(0, 0, {})
        with self.assertRaises(ValueError):
            WorkerCandidateRequest(0, 1, None)  # type: ignore[arg-type]

    def test_direct_empty_rules_share_one_bounded_hot_score_query(self) -> None:
        self.score.acquire_hot_acquire_candidates.return_value = {
            "worker-1": 101,
            "worker-2": 102,
        }
        self.score.acquire_observed_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score=201,
            ),
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score=202,
            ),
        }
        expected = {
            "item-1": (self.entry("worker-1", 201),),
            "item-2": (self.entry("worker-2", 202),),
        }
        self.matcher.match_explicit_worker_candidates.return_value = expected

        actual = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id="group-1",
            candidate_requests={
                "item-2": WorkerCandidateRequest(20, 1, {}),
                "item-1": WorkerCandidateRequest(10, 1, {}),
            },
            lease_until_millis=5000,
        )

        self.assertEqual(actual, expected)
        self.score.acquire_hot_acquire_candidates.assert_called_once_with(
            home_bucket_id="group-1",
            hot_eligibility_floor_millis=None,
            limit=25,
        )
        self.score.observe_due_hot_scores.assert_not_called()
        self.matcher.filter_candidate_worker_ids.assert_not_called()
        self.score.acquire_observed_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-1": 101, "worker-2": 102},
            target_time_millis=5000,
        )
        self.assertEqual(
            self.matcher.match_explicit_worker_candidates.call_args.kwargs[
                "candidate_worker_ids"
            ],
            {"item-1": ("worker-1",), "item-2": ("worker-2",)},
        )
        self.cache.consume_candidate_workers.assert_not_called()

    def test_direct_uses_worker_id_candidates_then_full_match(self) -> None:
        rule = {
            "workerId": {"$in": ["worker-1", "worker-2", "worker-1"]},
            "index.worker.region": {"$eq": "cn-east"},
            "platform.pool": {"$in": ["batch", "burst"]},
        }
        request = WorkerCandidateRequest(0, 1, rule)
        self.score.observe_due_hot_scores.return_value = {
            "worker-1": 101,
            "worker-2": 102,
        }
        self.score.acquire_observed_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score=201,
            ),
        }
        expected = {
            "item-1": (
                CandidateWorkerEntry(
                    worker_id="worker-1",
                    worker_group_id="group-1",
                    endpoint_manager_id="adapter-1",
                    worker_lease_score=201,
                ),
            )
        }
        self.matcher.match_explicit_worker_candidates.return_value = expected

        actual = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id="group-1",
            candidate_requests={"item-1": request},
            lease_until_millis=5000,
        )

        self.assertEqual(actual, expected)
        self.score.observe_due_hot_scores.assert_called_once_with(
            home_bucket_id="group-1",
            worker_ids=("worker-1", "worker-2"),
            hot_eligibility_floor_millis=None,
        )
        self.score.acquire_observed_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-1": 101},
            target_time_millis=5000,
        )
        self.assertEqual(
            self.matcher.match_explicit_worker_candidates.call_args.kwargs[
                "candidate_constraints"
            ]["item-1"].allocation_rule,
            rule,
        )
        self.assertEqual(
            self.matcher.match_explicit_worker_candidates.call_args.kwargs[
                "candidate_worker_ids"
            ],
            {"item-1": ("worker-1",)},
        )
        self.cache.consume_candidate_workers.assert_not_called()

    def test_direct_does_not_observe_or_lease_rule_mismatches(self) -> None:
        self.matcher.filter_candidate_worker_ids.side_effect = None
        self.matcher.filter_candidate_worker_ids.return_value = {
            "item-1": ("worker-2",)
        }
        self.score.observe_due_hot_scores.return_value = {"worker-2": 102}
        self.score.acquire_observed_hot_score_leases.return_value = {
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score=202,
            )
        }
        self.matcher.match_explicit_worker_candidates.return_value = {
            "item-1": (self.entry("worker-2", 202),)
        }

        self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id="group-1",
            candidate_requests={
                "item-1": WorkerCandidateRequest(
                    0,
                    1,
                    {
                        "workerId": {"$in": ["worker-1", "worker-2"]},
                        "index.worker.region": {"$eq": "cn-east"},
                    },
                )
            },
            lease_until_millis=5000,
        )

        self.score.observe_due_hot_scores.assert_called_once_with(
            home_bucket_id="group-1",
            worker_ids=("worker-2",),
            hot_eligibility_floor_millis=None,
        )
        self.score.acquire_observed_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-2": 102},
            target_time_millis=5000,
        )

    def test_direct_without_worker_id_does_not_fallback_to_cache(self) -> None:
        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id="group-1",
            candidate_requests={
                "item-1": WorkerCandidateRequest(
                    0,
                    1,
                    {"worker.region": {"$eq": "missing"}},
                )
            },
            lease_until_millis=5000,
        )

        self.assertEqual(result, {"item-1": ()})
        self.cache.consume_candidate_workers.assert_not_called()
        self.score.observe_due_hot_scores.assert_not_called()

    def test_direct_worker_ids_are_not_truncated_by_hot_scan_limit(self) -> None:
        worker_ids = [f"worker-{index}" for index in range(30)]
        self.matcher.filter_candidate_worker_ids.side_effect = None
        self.matcher.filter_candidate_worker_ids.return_value = {"item-1": ()}

        self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id="group-1",
            candidate_requests={
                "item-1": WorkerCandidateRequest(
                    0,
                    1,
                    {"workerId": {"$in": worker_ids}},
                )
            },
            lease_until_millis=5000,
        )

        self.assertEqual(
            self.matcher.filter_candidate_worker_ids.call_args.kwargs[
                "candidate_worker_ids"
            ],
            {"item-1": tuple(worker_ids)},
        )
        self.score.observe_due_hot_scores.assert_not_called()

    def test_direct_applies_one_priority_ordered_unique_worker_budget(self) -> None:
        self.matcher.filter_candidate_worker_ids.side_effect = None
        self.matcher.filter_candidate_worker_ids.return_value = {
            "first": (),
            "second": (),
        }
        first_worker_ids = [f"worker-{index}" for index in range(75)]
        second_worker_ids = [f"worker-{index}" for index in range(50, 125)]

        self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id="group-1",
            candidate_requests={
                "second": WorkerCandidateRequest(
                    20,
                    1,
                    {"workerId": {"$in": second_worker_ids}},
                ),
                "first": WorkerCandidateRequest(
                    10,
                    1,
                    {"workerId": {"$in": first_worker_ids}},
                ),
            },
            lease_until_millis=5000,
        )

        admitted = self.matcher.filter_candidate_worker_ids.call_args.kwargs[
            "candidate_worker_ids"
        ]
        self.assertEqual(tuple(first_worker_ids), admitted["first"])
        self.assertEqual(
            tuple(f"worker-{index}" for index in range(50, 100)),
            admitted["second"],
        )
        self.assertEqual(
            100,
            len(set(admitted["first"]).union(admitted["second"])),
        )
        self.score.observe_due_hot_scores.assert_not_called()

    def test_direct_rejects_unbounded_worker_id_candidates(self) -> None:
        for worker_id_rule in (
            {"$in": []},
            {"$in": [f"worker-{index}" for index in range(101)]},
        ):
            with self.subTest(worker_id_rule=worker_id_rule):
                result = self.acquirer.acquire_worker_candidates(
                    strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
                    worker_group_id="group-1",
                    candidate_requests={
                        "item-1": WorkerCandidateRequest(
                            0,
                            1,
                            {"workerId": worker_id_rule},
                        )
                    },
                    lease_until_millis=5000,
                )

                self.assertEqual(result, {"item-1": ()})
        self.cache.consume_candidate_workers.assert_not_called()
        self.score.observe_due_hot_scores.assert_not_called()

    def test_precomputed_uses_only_candidate_cache(self) -> None:
        self.cache.consume_candidate_workers.return_value = ()

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
            worker_group_id="group-1",
            candidate_requests={
                "task-1": WorkerCandidateRequest(
                    0,
                    1,
                    {"worker.arch": {"$eq": "arm64"}},
                )
            },
            lease_until_millis=5000,
        )

        self.assertEqual(result, {"task-1": ()})

    def test_precomputed_renews_and_rematches_flat_worker_scores_once(self) -> None:
        self.cache.consume_candidate_workers.side_effect = (
            (self.entry("worker-1", 101),),
            (self.entry("worker-2", 102),),
        )
        self.score.renew_active_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.NOOP,
                score=201,
            ),
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score=202,
            ),
        }
        expected = {
            "candidate-a": (self.entry("worker-1", 201),),
            "candidate-b": (self.entry("worker-2", 202),),
        }
        self.matcher.match_worker_candidates.return_value = expected

        actual = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
            worker_group_id="group-1",
            candidate_requests={
                "candidate-a": WorkerCandidateRequest(90, 1, {}),
                "candidate-b": WorkerCandidateRequest(80, 1, {}),
            },
            lease_until_millis=5000,
        )

        self.assertEqual(actual, expected)
        self.score.renew_active_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-1": 101, "worker-2": 102},
            target_time_millis=5000,
        )
        self.assertEqual(
            self.matcher.match_worker_candidates.call_args.kwargs[
                "worker_lease_scores"
            ],
            {"worker-1": 201, "worker-2": 202},
        )

    def test_hot_pool_precomputation_uses_score_source_not_cache(self) -> None:
        self.score.acquire_hot_acquire_candidates.return_value = {
            "worker-1": 101
        }
        self.score.acquire_observed_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score=201,
            )
        }
        expected = {"candidate": (self.entry("worker-1", 201),)}
        self.matcher.match_worker_candidates.return_value = expected

        actual = self.acquirer.acquire_hot_pool_candidates(
            worker_group_id="group-1",
            candidate_requests={
                "candidate": WorkerCandidateRequest(0, 1, {})
            },
            lease_until_millis=5000,
        )

        self.assertEqual(actual, expected)
        self.score.acquire_hot_acquire_candidates.assert_called_once_with(
            home_bucket_id="group-1",
            hot_eligibility_floor_millis=None,
            limit=25,
        )
        self.cache.consume_candidate_workers.assert_not_called()

    @staticmethod
    def entry(worker_id: str, score: int) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="group-1",
            endpoint_manager_id="adapter-1",
            worker_lease_score=score,
        )


if __name__ == "__main__":
    unittest.main()
