from __future__ import annotations

from collections.abc import Mapping as MappingABC
from dataclasses import dataclass
from enum import Enum
from typing import Mapping

from ...kernel.assignment_dispatch_runtime import (
    CandidateId,
    CandidateWorkerCache,
    CandidateWorkerEntry,
)
from ...kernel.task_score_band import TimeMillis
from ...kernel.worker_runtime import (
    WorkerDynamicAttributeRuntime,
    WorkerGroupId,
)
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
from .rules import worker_ids_from_target_rule


@dataclass(frozen=True)
class WorkerCandidateRequest:
    """One independent bounded Worker candidate demand."""

    priority: int
    requested_count: int
    allocation_rule: Mapping[str, object]
    target_field: str | None = None

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
        if not isinstance(self.allocation_rule, MappingABC):
            raise ValueError("candidate allocation rule must be a mapping")
        if self.target_field is not None and (
            not isinstance(self.target_field, str) or not self.target_field
        ):
            raise ValueError("candidate target field must be non-empty")


class WorkerCandidateAcquisitionStrategy(Enum):
    PRECOMPUTED = "PRECOMPUTED"
    TARGETED = "TARGETED"


class WorkerCandidateAcquirer:
    """Execute one explicit built-in Worker candidate acquisition strategy."""

    def __init__(
        self,
        candidate_cache: CandidateWorkerCache,
        worker_score: WorkerScoreCore,
        worker_matcher: WorkerCandidateMatcher,
        dynamic_attributes: WorkerDynamicAttributeRuntime,
        *,
        worker_scan_limit: int,
    ) -> None:
        if worker_scan_limit <= 0:
            raise ValueError("worker scan limit must be positive")
        self.candidate_cache = candidate_cache
        self.worker_score = worker_score
        self.worker_matcher = worker_matcher
        self.dynamic_attributes = dynamic_attributes
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
        if strategy is WorkerCandidateAcquisitionStrategy.TARGETED:
            return self._acquire_targeted(
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
        if any(request.target_field is not None for request in requests.values()):
            raise ValueError("HOT pool requests cannot declare a target field")
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
        if any(request.target_field is not None for request in requests.values()):
            raise ValueError("PRECOMPUTED requests cannot declare a target field")
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

    def _acquire_targeted(
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
        point_worker_ids: list[WorkerId] = []
        for _, request in ordered_requests:
            if request.target_field is None:
                raise ValueError("TARGETED requests require a target field")
            worker_ids = self._query_target_worker_ids(
                worker_group_id=worker_group_id,
                request=request,
            )
            point_worker_ids.extend(worker_ids)

        observed_scores = (
            self.worker_score.observe_due_hot_scores(
                home_bucket_id=worker_group_id,
                worker_ids=tuple(dict.fromkeys(point_worker_ids)),
            )
            if point_worker_ids
            else {}
        )
        if not observed_scores:
            return empty

        return self._lease_and_match(
            worker_group_id=worker_group_id,
            requests=requests,
            observed_scores=observed_scores,
            lease_until_millis=lease_until_millis,
        )

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

    def _query_target_worker_ids(
        self,
        *,
        worker_group_id: WorkerGroupId,
        request: WorkerCandidateRequest,
    ) -> tuple[WorkerId, ...]:
        target_field = request.target_field
        if target_field is None or target_field not in request.allocation_rule:
            raise ValueError("TARGETED request target field is not in allocation rule")
        operator_rule = request.allocation_rule[target_field]
        if not isinstance(operator_rule, MappingABC):
            raise ValueError("target field operator rule must be a mapping")
        if target_field == "workerId":
            return worker_ids_from_target_rule(
                operator_rule,
                limit=self.worker_scan_limit,
            )
        domain, separator, attribute_name = target_field.partition(".")
        if not separator or domain != "dynamic" or not attribute_name:
            raise ValueError("TARGETED field has no bounded candidate source")
        if not self.dynamic_attributes.supports_candidate_query(
            attribute_name=attribute_name,
            operator_rule=operator_rule,
        ):
            raise ValueError("dynamic target field has no candidate query handler")
        return self.dynamic_attributes.query_candidate_worker_ids(
            worker_group_id=worker_group_id,
            attribute_name=attribute_name,
            operator_rule=operator_rule,
            limit=self.worker_scan_limit,
        )


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
