from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock

from kernel_design.executable_spec import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
    WorkerCandidateMatcher,
    WorkerDynamicAttributeRuntime,
    WorkerScoreCore,
    WorkerScoreTransitionResult,
    WorkerScoreTransitionStatus,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
    WorkerCandidateRequest,
)


class WorkerCandidateAcquirerContractTest(unittest.TestCase):
    def test_internal_contract_is_group_local_and_strategy_explicit(self) -> None:
        self.assertEqual(
            ("PRECOMPUTED", "TARGETED"),
            tuple(strategy.value for strategy in WorkerCandidateAcquisitionStrategy),
        )
        self.assertEqual(
            {
                "self",
                "worker_group_id",
                "candidate_requests",
                "lease_until_millis",
            },
            set(
                inspect.signature(
                    WorkerCandidateAcquirer.acquire_hot_pool_candidates
                ).parameters
            ),
        )
        self.assertEqual(
            {
                "self",
                "strategy",
                "worker_group_id",
                "candidate_requests",
                "lease_until_millis",
            },
            set(
                inspect.signature(
                    WorkerCandidateAcquirer.acquire_worker_candidates
                ).parameters
            ),
        )
        self.assertEqual(
            {
                "priority",
                "requested_count",
                "allocation_rule",
                "target_field",
            },
            set(inspect.signature(WorkerCandidateRequest).parameters),
        )

    def test_request_validates_its_own_bound_and_target_field(self) -> None:
        request = WorkerCandidateRequest(
            priority=80,
            requested_count=2,
            allocation_rule={"attributes.runtime": {"$eq": "python"}},
        )
        self.assertEqual(2, request.requested_count)
        self.assertIsNone(request.target_field)

        for priority in (0, 99):
            with self.subTest(priority=priority):
                WorkerCandidateRequest(
                    priority=priority,
                    requested_count=1,
                    allocation_rule={},
                )

        for priority in (-1, 100, True):
            with self.subTest(priority=priority), self.assertRaises(ValueError):
                WorkerCandidateRequest(
                    priority=priority,
                    requested_count=1,
                    allocation_rule={},
                )

        with self.assertRaises(ValueError):
            WorkerCandidateRequest(
                priority=80,
                requested_count=0,
                allocation_rule={},
            )
        with self.assertRaises(ValueError):
            WorkerCandidateRequest(
                priority=80,
                requested_count=1,
                allocation_rule={},
                target_field="",
            )


class WorkerCandidateAcquirerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cache = Mock(spec=CandidateWorkerCache)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.matcher = Mock(spec=WorkerCandidateMatcher)
        self.dynamic_attributes = Mock(spec=WorkerDynamicAttributeRuntime)
        self.acquirer = WorkerCandidateAcquirer(
            self.cache,
            self.worker_score,
            self.matcher,
            self.dynamic_attributes,
            worker_scan_limit=50,
        )

    def test_precomputed_miss_does_not_fallback_to_targeted_source(self) -> None:
        self.cache.consume_candidate_workers.return_value = ()

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
            worker_group_id="group-1",
            candidate_requests={"candidate-1": self._request(count=2)},
            lease_until_millis=20_000,
        )

        self.assertEqual({"candidate-1": ()}, result)
        self.worker_score.acquire_hot_acquire_candidates.assert_not_called()
        self.worker_score.observe_due_hot_scores.assert_not_called()
        self.matcher.match_worker_candidates.assert_not_called()

    def test_precomputed_renews_and_rematches_flat_worker_scores_once(self) -> None:
        self.cache.consume_candidate_workers.side_effect = (
            (self._entry("worker-1", 101),),
            (self._entry("worker-2", 102),),
        )
        self.worker_score.renew_active_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.NOOP,
                201,
            ),
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                202,
            ),
        }
        self.matcher.match_worker_candidates.return_value = {
            "candidate-a": (self._entry("worker-1", 201),),
            "candidate-b": (self._entry("worker-2", 202),),
        }

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
            worker_group_id="group-1",
            candidate_requests={
                "candidate-a": self._request(priority=90),
                "candidate-b": self._request(priority=80),
            },
            lease_until_millis=20_000,
        )

        self.assertEqual("worker-1", result["candidate-a"][0].worker_id)
        self.worker_score.renew_active_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-1": 101, "worker-2": 102},
            target_time_millis=20_000,
        )
        matcher_call = self.matcher.match_worker_candidates.call_args.kwargs
        self.assertEqual(
            {"worker-1": 201, "worker-2": 202},
            matcher_call["worker_lease_scores"],
        )

    def test_precomputed_rejects_target_field(self) -> None:
        with self.assertRaises(ValueError):
            self.acquirer.acquire_worker_candidates(
                strategy=WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
                worker_group_id="group-1",
                candidate_requests={
                    "candidate-1": self._request(
                        allocation_rule={"workerId": {"$eq": "worker-1"}},
                        target_field="workerId",
                    )
                },
                lease_until_millis=20_000,
            )

    def test_hot_pool_precomputation_does_not_read_cache(self) -> None:
        self.worker_score.acquire_hot_acquire_candidates.return_value = {
            "worker-1": 101,
            "worker-2": 102,
        }
        self.worker_score.acquire_observed_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                201,
            ),
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                202,
            ),
        }
        self.matcher.match_worker_candidates.return_value = {
            "candidate-1": (self._entry("worker-1", 201),),
        }

        result = self.acquirer.acquire_hot_pool_candidates(
            worker_group_id="group-1",
            candidate_requests={"candidate-1": self._request()},
            lease_until_millis=20_000,
        )

        self.assertEqual("worker-1", result["candidate-1"][0].worker_id)
        self.worker_score.acquire_hot_acquire_candidates.assert_called_once_with(
            home_bucket_id="group-1",
            limit=50,
        )
        self.cache.consume_candidate_workers.assert_not_called()
        self.worker_score.observe_due_hot_scores.assert_not_called()

    def test_targeted_requires_an_explicit_target_field(self) -> None:
        with self.assertRaises(ValueError):
            self.acquirer.acquire_worker_candidates(
                strategy=WorkerCandidateAcquisitionStrategy.TARGETED,
                worker_group_id="group-1",
                candidate_requests={"candidate-1": self._request()},
                lease_until_millis=20_000,
            )

        self.worker_score.acquire_hot_acquire_candidates.assert_not_called()
        self.worker_score.observe_due_hot_scores.assert_not_called()

    def test_targeted_worker_id_observes_and_leases_only_declared_ids(self) -> None:
        self.worker_score.observe_due_hot_scores.return_value = {
            "worker-2": 102,
        }
        self.worker_score.acquire_observed_hot_score_leases.return_value = {
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                202,
            )
        }
        self.matcher.match_worker_candidates.return_value = {
            "message-1": (self._entry("worker-2", 202),),
        }

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.TARGETED,
            worker_group_id="group-1",
            candidate_requests={
                "message-1": self._request(
                    allocation_rule={
                        "workerId": {"$in": ["worker-2", "worker-1"]}
                    },
                    target_field="workerId",
                )
            },
            lease_until_millis=20_000,
        )

        self.assertEqual("worker-2", result["message-1"][0].worker_id)
        self.worker_score.observe_due_hot_scores.assert_called_once_with(
            home_bucket_id="group-1",
            worker_ids=("worker-2", "worker-1"),
        )
        self.assertEqual(
            {"worker-2": 202},
            self.matcher.match_worker_candidates.call_args.kwargs[
                "worker_lease_scores"
            ],
        )
        self.worker_score.acquire_hot_acquire_candidates.assert_not_called()
        self.cache.consume_candidate_workers.assert_not_called()

    def test_targeted_dynamic_field_uses_handler_owned_candidate_index(self) -> None:
        self.dynamic_attributes.supports_candidate_query.return_value = True
        self.dynamic_attributes.query_candidate_worker_ids.return_value = (
            "worker-3",
            "worker-2",
        )
        self.worker_score.observe_due_hot_scores.return_value = {
            "worker-3": 103,
        }
        self.worker_score.acquire_observed_hot_score_leases.return_value = {
            "worker-3": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                203,
            )
        }
        self.matcher.match_worker_candidates.return_value = {
            "message-1": (self._entry("worker-3", 203),),
        }
        rule = {"dynamic.battery": {"$gte": 80}}

        self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.TARGETED,
            worker_group_id="group-1",
            candidate_requests={
                "message-1": self._request(
                    allocation_rule=rule,
                    target_field="dynamic.battery",
                )
            },
            lease_until_millis=20_000,
        )

        self.dynamic_attributes.query_candidate_worker_ids.assert_called_once_with(
            worker_group_id="group-1",
            attribute_name="battery",
            operator_rule={"$gte": 80},
            limit=50,
        )
        self.worker_score.observe_due_hot_scores.assert_called_once_with(
            home_bucket_id="group-1",
            worker_ids=("worker-3", "worker-2"),
        )

    @staticmethod
    def _request(
        *,
        priority: int = 80,
        count: int = 1,
        allocation_rule: dict[str, object] | None = None,
        target_field: str | None = None,
    ) -> WorkerCandidateRequest:
        return WorkerCandidateRequest(
            priority=priority,
            requested_count=count,
            allocation_rule=(
                {"attributes.runtime": {"$eq": "python"}}
                if allocation_rule is None
                else allocation_rule
            ),
            target_field=target_field,
        )

    @staticmethod
    def _entry(worker_id: str, score: int) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="group-1",
            endpoint_manager_id="endpoint-manager-1",
            worker_lease_score=score,
        )


if __name__ == "__main__":
    unittest.main()
