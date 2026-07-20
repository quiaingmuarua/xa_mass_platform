from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock

from kernel_design.executable_spec import (
    CandidateWorkerCache,
    CandidateWorkerEntry,
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
    WorkerCandidateMatcher,
    WorkerCandidateRequest,
    WorkerScoreCore,
    WorkerScoreTransitionResult,
    WorkerScoreTransitionStatus,
)


class WorkerCandidateAcquirerContractTest(unittest.TestCase):
    def test_public_signature_requires_one_worker_group(self) -> None:
        self.assertEqual(
            ("CACHED", "REALTIME"),
            tuple(
                strategy.value
                for strategy in WorkerCandidateAcquisitionStrategy
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

    def test_request_validates_its_own_requested_count(self) -> None:
        self.assertEqual(
            {"priority", "requested_count", "match_rules"},
            set(inspect.signature(WorkerCandidateRequest).parameters),
        )
        request = WorkerCandidateRequest(
            priority=80,
            requested_count=2,
            match_rules={"attributes.runtime": {"$eq": "python"}},
        )
        self.assertEqual(2, request.requested_count)

        with self.assertRaises(ValueError):
            WorkerCandidateRequest(
                priority=80,
                requested_count=0,
                match_rules={},
            )


class WorkerCandidateCachedStrategyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cache = Mock(spec=CandidateWorkerCache)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.matcher = Mock(spec=WorkerCandidateMatcher)
        self.acquirer = WorkerCandidateAcquirer(
            self.cache,
            self.worker_score,
            self.matcher,
            worker_scan_limit=50,
        )
        self.request = WorkerCandidateRequest(
            priority=80,
            requested_count=2,
            match_rules={"attributes.runtime": {"$eq": "python"}},
        )

    def test_cache_miss_returns_empty_without_hot_scan(self) -> None:
        self.cache.consume_candidate_workers.return_value = ()

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.CACHED,
            worker_group_id="group-1",
            candidate_requests={"candidate-1": self.request},
            lease_until_millis=20_000,
        )

        self.assertEqual({"candidate-1": ()}, result)
        self.worker_score.acquire_hot_acquire_candidates.assert_not_called()
        self.worker_score.acquire_observed_hot_score_leases.assert_not_called()
        self.matcher.match_worker_candidates.assert_not_called()

    def test_validates_renews_and_rematches_cached_entries(self) -> None:
        entries = (
            self._entry("worker-1", 101),
            self._entry("worker-2", 102),
        )
        self.cache.consume_candidate_workers.return_value = entries
        self.worker_score.renew_active_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.NOOP,
                201,
            ),
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.STALE,
            ),
        }
        matched_entry = CandidateWorkerEntry(
            worker_id="worker-1",
            worker_group_id="group-1",
            endpoint_manager_id="endpoint-1",
            worker_lease_score=201,
        )
        self.matcher.match_worker_candidates.return_value = {
            "candidate-1": (matched_entry,),
        }

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.CACHED,
            worker_group_id="group-1",
            candidate_requests={"candidate-1": self.request},
            lease_until_millis=20_000,
        )

        self.assertEqual(
            (matched_entry,),
            result["candidate-1"],
        )
        self.cache.consume_candidate_workers.assert_called_once_with(
            candidate_id="candidate-1",
            limit=2,
        )
        self.worker_score.renew_active_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-1": 101, "worker-2": 102},
            target_time_millis=20_000,
        )
        matcher_call = self.matcher.match_worker_candidates.call_args
        self.assertEqual(
            {"worker-1": 201},
            matcher_call.kwargs["worker_lease_scores"],
        )
        self.assertEqual(
            2,
            matcher_call.kwargs["candidate_constraints"][
                "candidate-1"
            ].limit,
        )

    def test_rematch_failure_stays_empty_without_realtime_fallback(self) -> None:
        self.cache.consume_candidate_workers.return_value = (
            self._entry("worker-1", 101),
        )
        self.worker_score.renew_active_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                201,
            )
        }
        self.matcher.match_worker_candidates.return_value = {
            "candidate-1": (),
        }

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.CACHED,
            worker_group_id="group-1",
            candidate_requests={"candidate-1": self.request},
            lease_until_millis=20_000,
        )

        self.assertEqual({"candidate-1": ()}, result)
        self.worker_score.acquire_hot_acquire_candidates.assert_not_called()

    def test_same_group_cached_requests_share_one_score_renewal_batch(self) -> None:
        second_request = WorkerCandidateRequest(
            priority=70,
            requested_count=1,
            match_rules={},
        )
        self.cache.consume_candidate_workers.side_effect = (
            (self._entry("worker-1", 101),),
            (self._entry("worker-2", 102),),
        )
        self.worker_score.renew_active_hot_score_leases.return_value = {
            "worker-1": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.STALE
            ),
            "worker-2": WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.STALE
            ),
        }

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.CACHED,
            worker_group_id="group-1",
            candidate_requests={
                "candidate-1": self.request,
                "candidate-2": second_request,
            },
            lease_until_millis=20_000,
        )

        self.assertEqual(
            {"candidate-1": (), "candidate-2": ()},
            result,
        )
        self.worker_score.renew_active_hot_score_leases.assert_called_once_with(
            home_bucket_id="group-1",
            observed_scores={"worker-1": 101, "worker-2": 102},
            target_time_millis=20_000,
        )

    @staticmethod
    def _entry(worker_id: str, score: int) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="group-1",
            endpoint_manager_id=f"old-{worker_id}",
            worker_lease_score=score,
        )


class WorkerCandidateRealtimeStrategyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cache = Mock(spec=CandidateWorkerCache)
        self.worker_score = Mock(spec=WorkerScoreCore)
        self.matcher = Mock(spec=WorkerCandidateMatcher)
        self.acquirer = WorkerCandidateAcquirer(
            self.cache,
            self.worker_score,
            self.matcher,
            worker_scan_limit=50,
        )

    def test_applies_each_requested_count_within_one_worker_group(self) -> None:
        requests = {
            "candidate-a": WorkerCandidateRequest(
                priority=90,
                requested_count=1,
                match_rules={"attributes.runtime": {"$eq": "python"}},
            ),
            "candidate-b": WorkerCandidateRequest(
                priority=80,
                requested_count=2,
                match_rules={"attributes.region": {"$eq": "east"}},
            ),
        }
        self.worker_score.acquire_hot_acquire_candidates.return_value = {
            "worker-1": 101,
            "worker-2": 102,
            "worker-3": 103,
        }
        self.worker_score.acquire_observed_hot_score_leases.return_value = {
            worker_id: WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score + 100,
            )
            for worker_id, score in {
                "worker-1": 101,
                "worker-2": 102,
                "worker-3": 103,
            }.items()
        }
        self.matcher.match_worker_candidates.return_value = {
            "candidate-a": (
                CandidateWorkerEntry(
                    worker_id="worker-1",
                    worker_group_id="group-1",
                    endpoint_manager_id="endpoint-1",
                    worker_lease_score=201,
                ),
            ),
            "candidate-b": (
                CandidateWorkerEntry(
                    worker_id="worker-2",
                    worker_group_id="group-1",
                    endpoint_manager_id="endpoint-2",
                    worker_lease_score=202,
                ),
                CandidateWorkerEntry(
                    worker_id="worker-3",
                    worker_group_id="group-1",
                    endpoint_manager_id="endpoint-3",
                    worker_lease_score=203,
                ),
            ),
        }

        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.REALTIME,
            worker_group_id="group-1",
            candidate_requests=requests,
            lease_until_millis=20_000,
        )

        self.assertEqual(1, len(result["candidate-a"]))
        self.assertEqual(2, len(result["candidate-b"]))
        self.assertEqual(
            {"worker-1", "worker-2", "worker-3"},
            {
                entry.worker_id
                for entries in result.values()
                for entry in entries
            },
        )
        self.worker_score.acquire_hot_acquire_candidates.assert_called_once_with(
            home_bucket_id="group-1",
            limit=50,
        )
        self.cache.consume_candidate_workers.assert_not_called()
        constraints = self.matcher.match_worker_candidates.call_args.kwargs[
            "candidate_constraints"
        ]
        self.assertEqual(1, constraints["candidate-a"].limit)
        self.assertEqual(2, constraints["candidate-b"].limit)

    def test_empty_requests_do_not_scan_the_worker_group(self) -> None:
        result = self.acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.REALTIME,
            worker_group_id="group-1",
            candidate_requests={},
            lease_until_millis=20_000,
        )

        self.assertEqual({}, result)
        self.worker_score.acquire_hot_acquire_candidates.assert_not_called()


if __name__ == "__main__":
    unittest.main()
