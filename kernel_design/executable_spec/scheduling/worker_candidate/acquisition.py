from __future__ import annotations

from collections.abc import Mapping as MappingABC
from dataclasses import dataclass
from typing import Mapping, Protocol

from ...kernel.assignment_dispatch_runtime import (
    CandidateId,
    CandidateWorkerCache,
    CandidateWorkerEntry,
)
from ...kernel.task_score_band import TimeMillis
from ...kernel.worker_runtime import WorkerGroupId
from ...kernel.worker_score import (
    Score,
    WorkerId,
    WorkerScoreCore,
    WorkerScoreTransitionStatus,
)
from .matching import (
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
)


@dataclass(frozen=True)
class WorkerCandidateRequest:
    """One independent bounded Worker candidate demand."""

    priority: int
    requested_count: int
    match_rules: Mapping[str, object]

    def __post_init__(self) -> None:
        if (
            isinstance(self.priority, bool)
            or not isinstance(self.priority, int)
            or not 1 <= self.priority <= 100
        ):
            raise ValueError("candidate priority must be in 1..100")
        if (
            isinstance(self.requested_count, bool)
            or not isinstance(self.requested_count, int)
            or self.requested_count <= 0
        ):
            raise ValueError("requested candidate count must be positive")
        if not isinstance(self.match_rules, MappingABC):
            raise ValueError("candidate match rules must be a mapping")


WorkerCandidateAcquisition = Mapping[
    CandidateId,
    tuple[CandidateWorkerEntry, ...],
]


class WorkerCandidateAcquirer(Protocol):
    """Acquire already-leased Workers for independent candidate requests."""

    def acquire_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        ...


class CachedWorkerCandidateAcquirer:
    """Acquire only from cached candidate evidence; never fall back to scan."""

    def __init__(
        self,
        candidate_cache: CandidateWorkerCache,
        worker_score: WorkerScoreCore,
        worker_matcher: WorkerCandidateMatcher,
    ) -> None:
        self.candidate_cache = candidate_cache
        self.worker_score = worker_score
        self.worker_matcher = worker_matcher

    def acquire_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        _validate_worker_group_id(worker_group_id)
        requests = _validate_candidate_requests(candidate_requests)
        acquired: dict[CandidateId, tuple[CandidateWorkerEntry, ...]] = {
            candidate_id: () for candidate_id in requests
        }
        consumed_by_candidate: dict[
            CandidateId,
            tuple[CandidateWorkerEntry, ...],
        ] = {}
        observed_scores: dict[WorkerId, Score] = {}
        reserved_workers: set[WorkerId] = set()

        for candidate_id, request in _ordered_requests(requests):
            cached_entries = self.candidate_cache.consume_candidate_workers(
                candidate_id=candidate_id,
                limit=request.requested_count,
            )
            eligible_entries: list[CandidateWorkerEntry] = []
            for entry in cached_entries:
                if (
                    entry.worker_group_id != worker_group_id
                    or entry.worker_id in reserved_workers
                ):
                    continue
                reserved_workers.add(entry.worker_id)
                eligible_entries.append(entry)
                observed_scores[entry.worker_id] = entry.worker_lease_score
            consumed_by_candidate[candidate_id] = tuple(eligible_entries)

        lease_results = (
            self.worker_score.renew_active_hot_score_leases(
                home_bucket_id=worker_group_id,
                observed_scores=observed_scores,
                target_time_millis=lease_until_millis,
            )
            if observed_scores
            else {}
        )

        for candidate_id, request in _ordered_requests(requests):
            eligible_entries = consumed_by_candidate.get(candidate_id, ())
            if not eligible_entries:
                continue
            renewed_scores = {
                worker_id: result.score
                for worker_id, result in lease_results.items()
                if result.status
                in {
                    WorkerScoreTransitionStatus.TRANSITIONED,
                    WorkerScoreTransitionStatus.NOOP,
                }
                and result.score is not None
            }
            if not renewed_scores:
                continue

            match_result = self.worker_matcher.match_worker_candidates(
                worker_group_id=worker_group_id,
                worker_ids=tuple(
                    entry.worker_id
                    for entry in eligible_entries
                    if entry.worker_id in renewed_scores
                ),
                candidate_constraints={
                    candidate_id: _matcher_constraint(request),
                },
            )
            matched_entries = tuple(
                CandidateWorkerEntry(
                    worker_id=worker_id,
                    worker_group_id=worker_group_id,
                    endpoint_manager_id=(
                        match_result.endpoint_manager_id_by_worker_id[worker_id]
                    ),
                    worker_lease_score=renewed_scores[worker_id],
                )
                for worker_id in match_result.matches.get(candidate_id, ())
            )
            acquired[candidate_id] = matched_entries

        return acquired


class RealtimeWorkerCandidateAcquirer:
    """Acquire candidates directly from due HOT Workers; never read cache."""

    def __init__(
        self,
        worker_score: WorkerScoreCore,
        worker_matcher: WorkerCandidateMatcher,
        *,
        worker_scan_limit: int,
    ) -> None:
        if worker_scan_limit <= 0:
            raise ValueError("worker scan limit must be positive")
        self.worker_score = worker_score
        self.worker_matcher = worker_matcher
        self.worker_scan_limit = worker_scan_limit

    def acquire_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        _validate_worker_group_id(worker_group_id)
        requests = _validate_candidate_requests(candidate_requests)
        acquired: dict[CandidateId, tuple[CandidateWorkerEntry, ...]] = {
            candidate_id: () for candidate_id in requests
        }
        if not requests:
            return acquired
        observed_scores = self.worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=worker_group_id,
            limit=self.worker_scan_limit,
        )
        if not observed_scores:
            return acquired
        lease_results = self.worker_score.acquire_observed_hot_score_leases(
            home_bucket_id=worker_group_id,
            observed_scores=observed_scores,
            target_time_millis=lease_until_millis,
        )
        leased_scores: dict[WorkerId, Score] = {
            worker_id: result.score
            for worker_id, result in lease_results.items()
            if result.status is WorkerScoreTransitionStatus.TRANSITIONED
            and result.score is not None
        }
        if not leased_scores:
            return acquired

        match_result = self.worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=tuple(leased_scores),
            candidate_constraints={
                candidate_id: _matcher_constraint(request)
                for candidate_id, request in requests.items()
            },
        )
        for candidate_id, worker_ids in match_result.matches.items():
            acquired[candidate_id] = tuple(
                CandidateWorkerEntry(
                    worker_id=worker_id,
                    worker_group_id=worker_group_id,
                    endpoint_manager_id=(
                        match_result.endpoint_manager_id_by_worker_id[worker_id]
                    ),
                    worker_lease_score=leased_scores[worker_id],
                )
                for worker_id in worker_ids
            )

        return acquired


def _validate_worker_group_id(worker_group_id: WorkerGroupId) -> None:
    if not isinstance(worker_group_id, str) or not worker_group_id:
        raise ValueError("worker group id must be non-empty")


def _validate_candidate_requests(
    candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
) -> dict[CandidateId, WorkerCandidateRequest]:
    requests = dict(candidate_requests)
    for candidate_id, request in requests.items():
        if not isinstance(candidate_id, str) or not candidate_id:
            raise ValueError("candidate id must be non-empty")
        if not isinstance(request, WorkerCandidateRequest):
            raise TypeError("candidate request must be WorkerCandidateRequest")
    return requests


def _ordered_requests(
    requests: Mapping[CandidateId, WorkerCandidateRequest],
) -> tuple[tuple[CandidateId, WorkerCandidateRequest], ...]:
    return tuple(
        sorted(
            requests.items(),
            key=lambda item: (-item[1].priority, item[0]),
        )
    )


def _matcher_constraint(
    request: WorkerCandidateRequest,
) -> WorkerCandidateConstraint:
    return WorkerCandidateConstraint(
        priority=request.priority,
        limit=request.requested_count,
        match_rules=request.match_rules,
    )
