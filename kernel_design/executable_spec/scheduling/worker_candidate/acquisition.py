from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from typing import Mapping

from ...constraint_dsl import ConstraintEvaluator
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
    WorkerScoreTransitionResult,
    WorkerScoreTransitionStatus,
)
from .matching import (
    WorkerCandidateAcquisition,
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
)


MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND = 100
_MAX_DIRECT_EXPLICIT_WORKER_IDS = 100


@dataclass(frozen=True)
class WorkerCandidateRequest:
    """One independent bounded Worker candidate demand."""

    priority: int
    requested_count: int
    allocation_rule: Mapping[str, object]

    def __post_init__(self) -> None:
        if (
            isinstance(self.priority, bool)
            or not isinstance(self.priority, int)
            or not 0 <= self.priority <= 99
        ):
            raise ValueError("candidate priority must be in 0..99")
        if (
            isinstance(self.requested_count, bool)
            or not isinstance(self.requested_count, int)
            or self.requested_count <= 0
        ):
            raise ValueError("requested candidate count must be positive")
        if not isinstance(self.allocation_rule, MappingABC):
            raise ValueError("candidate allocation rule must be a mapping")


class WorkerCandidateAcquisitionStrategy(Enum):
    PRECOMPUTED = "PRECOMPUTED"
    DIRECT = "DIRECT"


class WorkerCandidateAcquirer:
    """Execute one explicit built-in Worker candidate acquisition strategy."""

    def __init__(
        self,
        candidate_cache: CandidateWorkerCache,
        worker_score: WorkerScoreCore,
        worker_matcher: WorkerCandidateMatcher,
        *,
        worker_scan_limit: int,
    ) -> None:
        if worker_scan_limit <= 0:
            raise ValueError("worker scan limit must be positive")
        self.candidate_cache = candidate_cache
        self.worker_score = worker_score
        self.worker_matcher = worker_matcher
        self.worker_scan_limit = worker_scan_limit

    def acquire_worker_candidates(
        self,
        *,
        strategy: WorkerCandidateAcquisitionStrategy,
        worker_group_id: WorkerGroupId,
        candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        if not isinstance(strategy, WorkerCandidateAcquisitionStrategy):
            raise TypeError("candidate acquisition strategy is invalid")
        _validate_worker_group_id(worker_group_id)
        requests = _validate_candidate_requests(candidate_requests)
        if strategy is WorkerCandidateAcquisitionStrategy.PRECOMPUTED:
            return self._acquire_precomputed(
                worker_group_id=worker_group_id,
                requests=requests,
                lease_until_millis=lease_until_millis,
            )
        if strategy is WorkerCandidateAcquisitionStrategy.DIRECT:
            return self._acquire_direct(
                worker_group_id=worker_group_id,
                requests=requests,
                lease_until_millis=lease_until_millis,
            )
        raise AssertionError("unhandled Worker candidate acquisition strategy")

    def acquire_hot_pool_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        """Acquire broad HOT candidates for the precomputation pacer."""

        _validate_worker_group_id(worker_group_id)
        requests = _validate_candidate_requests(candidate_requests)
        empty = _empty_acquisition(requests)
        if not requests:
            return empty

        observed_scores = self.worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=worker_group_id,
            limit=self.worker_scan_limit,
        )
        if not observed_scores:
            return empty
        return self._lease_and_match(
            worker_group_id=worker_group_id,
            requests=requests,
            observed_scores=observed_scores,
            lease_until_millis=lease_until_millis,
        )

    def _acquire_precomputed(
        self,
        *,
        worker_group_id: WorkerGroupId,
        requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        empty = _empty_acquisition(requests)
        if not requests:
            return empty

        observed_scores: dict[WorkerId, Score] = {}

        for candidate_id, request in _ordered_requests(requests):
            cached_entries = self.candidate_cache.consume_candidate_workers(
                candidate_id=candidate_id,
                limit=request.requested_count,
            )
            for entry in cached_entries:
                if entry.worker_group_id != worker_group_id:
                    continue
                observed_scores.setdefault(
                    entry.worker_id,
                    entry.worker_lease_score,
                )

        lease_results = (
            self.worker_score.renew_active_hot_score_leases(
                home_bucket_id=worker_group_id,
                observed_scores=observed_scores,
                target_time_millis=lease_until_millis,
            )
            if observed_scores
            else {}
        )
        renewed_scores = _successful_lease_scores(
            lease_results,
            allow_noop=True,
        )
        if not renewed_scores:
            return empty
        return self.worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_lease_scores=renewed_scores,
            candidate_constraints={
                candidate_id: _matcher_constraint(request)
                for candidate_id, request in requests.items()
            },
        )

    def _acquire_direct(
        self,
        *,
        worker_group_id: WorkerGroupId,
        requests: Mapping[CandidateId, WorkerCandidateRequest],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        empty = _empty_acquisition(requests)
        if not requests:
            return empty

        ordered_requests = _ordered_requests(requests)
        unrestricted = {
            candidate_id
            for candidate_id, request in ordered_requests
            if not request.allocation_rule
        }
        broad_scores = (
            self.worker_score.acquire_hot_acquire_candidates(
                home_bucket_id=worker_group_id,
                limit=min(
                    self.worker_scan_limit,
                    MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND,
                ),
            )
            if unrestricted
            else {}
        )

        candidate_worker_ids: dict[CandidateId, tuple[WorkerId, ...]] = {}
        admitted_worker_ids: set[WorkerId] = set()
        for candidate_id, request in ordered_requests:
            admitted_for_candidate: list[WorkerId] = []
            requested_worker_ids = (
                tuple(broad_scores)
                if candidate_id in unrestricted
                else self._worker_id_candidates(
                    allocation_rule=request.allocation_rule,
                )
            )
            for worker_id in requested_worker_ids:
                if worker_id in admitted_worker_ids:
                    admitted_for_candidate.append(worker_id)
                    continue
                if (
                    len(admitted_worker_ids)
                    >= MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                ):
                    continue
                admitted_worker_ids.add(worker_id)
                admitted_for_candidate.append(worker_id)
            candidate_worker_ids[candidate_id] = tuple(
                admitted_for_candidate
            )

        constraints = {
            candidate_id: _matcher_constraint(request)
            for candidate_id, request in requests.items()
        }
        explicit_candidate_worker_ids = {
            candidate_id: worker_ids
            for candidate_id, worker_ids in candidate_worker_ids.items()
            if candidate_id not in unrestricted
        }
        explicit_constraints = {
            candidate_id: constraints[candidate_id]
            for candidate_id in explicit_candidate_worker_ids
        }
        prefiltered_explicit_worker_ids = (
            self.worker_matcher.filter_candidate_worker_ids(
                worker_group_id=worker_group_id,
                candidate_worker_ids=explicit_candidate_worker_ids,
                candidate_constraints=explicit_constraints,
            )
            if explicit_candidate_worker_ids
            else {}
        )
        matched_worker_ids = {
            candidate_id: (
                worker_ids
                if candidate_id in unrestricted
                else prefiltered_explicit_worker_ids.get(candidate_id, ())
            )
            for candidate_id, worker_ids in candidate_worker_ids.items()
        }
        point_worker_ids = tuple(
            dict.fromkeys(
                worker_id
                for candidate_id, _ in ordered_requests
                for worker_id in matched_worker_ids.get(candidate_id, ())
                if worker_id not in broad_scores
            )
        )

        point_observed_scores = (
            self.worker_score.observe_due_hot_scores(
                home_bucket_id=worker_group_id,
                worker_ids=point_worker_ids,
            )
            if point_worker_ids
            else {}
        )
        observed_scores = {**broad_scores, **point_observed_scores}
        if not observed_scores:
            return empty

        selected_worker_ids: dict[CandidateId, tuple[WorkerId, ...]] = {}
        reserved_worker_ids: set[WorkerId] = set()
        for candidate_id, request in ordered_requests:
            selected = tuple(
                worker_id
                for worker_id in matched_worker_ids.get(candidate_id, ())
                if worker_id in observed_scores
                and worker_id not in reserved_worker_ids
            )[: request.requested_count]
            selected_worker_ids[candidate_id] = selected
            reserved_worker_ids.update(selected)

        selected_scores = {
            worker_id: observed_scores[worker_id]
            for worker_ids in selected_worker_ids.values()
            for worker_id in worker_ids
        }
        if not selected_scores:
            return empty
        lease_results = self.worker_score.acquire_observed_hot_score_leases(
            home_bucket_id=worker_group_id,
            observed_scores=selected_scores,
            target_time_millis=lease_until_millis,
        )
        leased_scores = _successful_lease_scores(
            lease_results,
            allow_noop=False,
        )
        if not leased_scores:
            return empty
        return self.worker_matcher.match_explicit_worker_candidates(
            worker_group_id=worker_group_id,
            worker_lease_scores=leased_scores,
            candidate_worker_ids=selected_worker_ids,
            candidate_constraints=constraints,
        )

    def _worker_id_candidates(
        self,
        *,
        allocation_rule: Mapping[str, object],
    ) -> tuple[WorkerId, ...]:
        try:
            compiled_rule = ConstraintEvaluator.compile_match_rules(
                allocation_rule
            )
            worker_id_rule = compiled_rule.get("workerId")
            if worker_id_rule is None:
                return ()
            return _worker_ids_from_operator_rule(worker_id_rule)
        except Exception:
            # DIRECT has no descriptor scan, index discovery, or cache fallback.
            return ()

    def _lease_and_match(
        self,
        *,
        worker_group_id: WorkerGroupId,
        requests: Mapping[CandidateId, WorkerCandidateRequest],
        observed_scores: Mapping[WorkerId, Score],
        lease_until_millis: TimeMillis,
    ) -> WorkerCandidateAcquisition:
        empty = _empty_acquisition(requests)

        lease_results = self.worker_score.acquire_observed_hot_score_leases(
            home_bucket_id=worker_group_id,
            observed_scores=observed_scores,
            target_time_millis=lease_until_millis,
        )
        leased_scores = _successful_lease_scores(
            lease_results,
            allow_noop=False,
        )
        if not leased_scores:
            return empty
        return self.worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_lease_scores=leased_scores,
            candidate_constraints={
                candidate_id: _matcher_constraint(request)
                for candidate_id, request in requests.items()
            },
        )

def _validate_worker_group_id(worker_group_id: WorkerGroupId) -> None:
    if not isinstance(worker_group_id, str) or not worker_group_id:
        raise ValueError("worker group id must be non-empty")


def _worker_ids_from_operator_rule(
    operator_rule: Mapping[str, object],
) -> tuple[WorkerId, ...]:
    if not isinstance(operator_rule, MappingABC) or len(operator_rule) != 1:
        raise ValueError("workerId requires one operator")
    operator, operand = next(iter(operator_rule.items()))
    if operator in {"$eq", "$equal"}:
        values = (operand,)
    elif (
        operator == "$in"
        and not isinstance(operand, (str, bytes))
        and isinstance(operand, SequenceABC)
        and 0
        < len(operand)
        <= _MAX_DIRECT_EXPLICIT_WORKER_IDS
    ):
        values = tuple(operand)
    else:
        raise ValueError("workerId supports only $eq/$equal/$in")
    if any(not isinstance(value, str) or not value for value in values):
        raise ValueError("workerId values must be non-empty strings")
    return tuple(dict.fromkeys(values))


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
            key=lambda item: (item[1].priority, item[0]),
        )
    )


def _matcher_constraint(
    request: WorkerCandidateRequest,
) -> WorkerCandidateConstraint:
    return WorkerCandidateConstraint(
        priority=request.priority,
        limit=request.requested_count,
        allocation_rule=request.allocation_rule,
    )


def _empty_acquisition(
    requests: Mapping[CandidateId, WorkerCandidateRequest],
) -> dict[CandidateId, tuple[CandidateWorkerEntry, ...]]:
    return {candidate_id: () for candidate_id in requests}


def _successful_lease_scores(
    results: Mapping[WorkerId, WorkerScoreTransitionResult],
    *,
    allow_noop: bool,
) -> dict[WorkerId, Score]:
    accepted_statuses = {WorkerScoreTransitionStatus.TRANSITIONED}
    if allow_noop:
        accepted_statuses.add(WorkerScoreTransitionStatus.NOOP)
    return {
        worker_id: result.score
        for worker_id, result in results.items()
        if result.status in accepted_statuses and result.score is not None
    }
