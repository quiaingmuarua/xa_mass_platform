from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Mapping, Sequence

from ...constraint_dsl import (
    ConstraintEvaluator,
    ConstraintMap,
)
from ...kernel.assignment_dispatch_runtime import (
    CandidateId,
    CandidateWorkerEntry,
)
from ...kernel.worker_runtime import (
    WorkerDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
)
from ...kernel.worker_score import Score, WorkerId


_LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class WorkerCandidateConstraint:
    """One candidate's allocation rule and per-call Worker bound."""

    priority: int
    limit: int
    allocation_rule: Mapping[str, object]


WorkerCandidateAcquisition = Mapping[
    CandidateId,
    tuple[CandidateWorkerEntry, ...],
]


class WorkerCandidateMatcher:
    """Match bounded Worker ids through canonical property snapshots."""

    def __init__(
        self,
        catalog: WorkerResourceCatalog,
    ) -> None:
        self.catalog = catalog

    def match_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_lease_scores: Mapping[WorkerId, Score],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> WorkerCandidateAcquisition:
        return self.match_explicit_worker_candidates(
            worker_group_id=worker_group_id,
            worker_lease_scores=worker_lease_scores,
            candidate_worker_ids={
                candidate_id: tuple(worker_lease_scores)
                for candidate_id in candidate_constraints
            },
            candidate_constraints=candidate_constraints,
        )

    def filter_candidate_worker_ids(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_worker_ids: Mapping[CandidateId, Sequence[WorkerId]],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> Mapping[CandidateId, tuple[WorkerId, ...]]:
        """Apply complete rules before score observation or lease mutation."""

        candidates = self._prepare_candidates(
            worker_group_id,
            candidate_constraints,
        )
        matches, _ = self._match_bounded_worker_ids(
            worker_group_id=worker_group_id,
            candidate_worker_ids=candidate_worker_ids,
            candidate_constraints=candidate_constraints,
            candidates=candidates,
            limit_matches=False,
            unique_matches=False,
        )
        return self._freeze_worker_ids(matches)

    def match_explicit_worker_candidates(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_lease_scores: Mapping[WorkerId, Score],
        candidate_worker_ids: Mapping[CandidateId, Sequence[WorkerId]],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> WorkerCandidateAcquisition:
        mutable_matches: dict[CandidateId, list[CandidateWorkerEntry]] = {
            candidate_id: [] for candidate_id in candidate_constraints
        }
        candidates = self._prepare_candidates(
            worker_group_id,
            candidate_constraints,
        )
        if not candidate_constraints or not worker_lease_scores:
            return self._freeze_acquisitions(mutable_matches)

        leased_ids = set(worker_lease_scores)
        bounded_ids = {
            candidate_id: tuple(
                worker_id
                for worker_id in dict.fromkeys(
                    candidate_worker_ids.get(candidate_id, ())
                )
                if worker_id in leased_ids
            )
            for candidate_id in candidate_constraints
        }
        matched_worker_ids, descriptors = self._match_bounded_worker_ids(
            worker_group_id=worker_group_id,
            candidate_worker_ids=bounded_ids,
            candidate_constraints=candidate_constraints,
            candidates=candidates,
            limit_matches=True,
            unique_matches=True,
        )
        for candidate_id, worker_ids in matched_worker_ids.items():
            for worker_id in worker_ids:
                descriptor = descriptors[worker_id]
                mutable_matches[candidate_id].append(
                    CandidateWorkerEntry(
                        worker_id=worker_id,
                        worker_group_id=worker_group_id,
                        endpoint_manager_id=descriptor.endpoint_manager_id,
                        worker_lease_score=worker_lease_scores[worker_id],
                    )
                )
        return self._freeze_acquisitions(mutable_matches)

    def _match_bounded_worker_ids(
        self,
        *,
        worker_group_id: WorkerGroupId,
        candidate_worker_ids: Mapping[CandidateId, Sequence[WorkerId]],
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
        candidates: tuple[
            tuple[CandidateId, WorkerCandidateConstraint, ConstraintMap],
            ...,
        ],
        limit_matches: bool,
        unique_matches: bool,
    ) -> tuple[
        dict[CandidateId, list[WorkerId]],
        Mapping[WorkerId, WorkerDescriptor],
    ]:
        mutable_matches: dict[CandidateId, list[WorkerId]] = {
            candidate_id: [] for candidate_id in candidate_constraints
        }
        if not candidates:
            return mutable_matches, {}

        all_worker_ids = tuple(
            dict.fromkeys(
                worker_id
                for candidate_id, _, _ in candidates
                for worker_id in candidate_worker_ids.get(candidate_id, ())
            )
        )
        if not all_worker_ids:
            return mutable_matches, {}
        raw_descriptors = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=all_worker_ids,
        )
        descriptors = {
            worker_id: descriptor
            for worker_id, descriptor in raw_descriptors.items()
            if descriptor is not None
            and descriptor.worker_group_id == worker_group_id
        }
        used_worker_ids: set[WorkerId] = set()
        for candidate_id, constraints, compiled_rule in candidates:
            for worker_id in dict.fromkeys(
                candidate_worker_ids.get(candidate_id, ())
            ):
                if unique_matches and worker_id in used_worker_ids:
                    continue
                descriptor = descriptors.get(worker_id)
                if descriptor is None:
                    continue
                if not ConstraintEvaluator.evaluate_match_rules(
                    self._match_context(
                        worker_id,
                        descriptor,
                    ),
                    compiled_rule,
                ):
                    continue
                mutable_matches[candidate_id].append(worker_id)
                if unique_matches:
                    used_worker_ids.add(worker_id)
                if (
                    limit_matches
                    and len(mutable_matches[candidate_id]) >= constraints.limit
                ):
                    break
        return mutable_matches, descriptors

    def _prepare_candidates(
        self,
        worker_group_id: WorkerGroupId,
        candidate_constraints: Mapping[CandidateId, WorkerCandidateConstraint],
    ) -> tuple[
        tuple[CandidateId, WorkerCandidateConstraint, ConstraintMap],
        ...,
    ]:
        candidates: list[
            tuple[CandidateId, WorkerCandidateConstraint, ConstraintMap]
        ] = []
        invalid_count = 0
        for candidate_id, constraints in sorted(
            candidate_constraints.items(),
            key=lambda item: (item[1].priority, item[0]),
        ):
            if not candidate_id:
                raise ValueError("candidate id must be non-empty")
            if (
                isinstance(constraints.priority, bool)
                or not isinstance(constraints.priority, int)
                or not 0 <= constraints.priority <= 99
            ):
                raise ValueError("candidate priority must be in 0..99")
            if constraints.limit <= 0:
                raise ValueError("candidate limit must be positive")
            try:
                compiled_rule = ConstraintEvaluator.compile_match_rules(
                    constraints.allocation_rule
                )
                if any(
                    not _valid_allocation_field(field_name)
                    for field_name in compiled_rule
                ):
                    raise ValueError("unsupported Worker allocation field")
            except ValueError:
                invalid_count += 1
                continue
            candidates.append((candidate_id, constraints, compiled_rule))
        if invalid_count:
            _LOGGER.warning(
                "Worker allocation rule rejected workerGroupId=%s "
                "errorType=INVALID_RULE candidateCount=%d",
                worker_group_id,
                invalid_count,
            )
        return tuple(candidates)

    @staticmethod
    def _match_context(
        worker_id: WorkerId,
        descriptor: WorkerDescriptor,
    ) -> dict[str, object]:
        return {
            "workerId": worker_id,
            "worker": dict(descriptor.worker_properties),
            "platform": dict(descriptor.platform_properties),
        }

    @staticmethod
    def _freeze_acquisitions(
        matches: Mapping[CandidateId, Sequence[CandidateWorkerEntry]],
    ) -> WorkerCandidateAcquisition:
        return {
            candidate_id: tuple(entries)
            for candidate_id, entries in matches.items()
        }

    @staticmethod
    def _freeze_worker_ids(
        matches: Mapping[CandidateId, Sequence[WorkerId]],
    ) -> Mapping[CandidateId, tuple[WorkerId, ...]]:
        return {
            candidate_id: tuple(worker_ids)
            for candidate_id, worker_ids in matches.items()
        }


def _valid_allocation_field(field_name: object) -> bool:
    return (
        field_name == "workerId"
        or isinstance(field_name, str)
        and (
            field_name.startswith("worker.")
            and len(field_name) > len("worker.")
            or field_name.startswith("platform.")
            and len(field_name) > len("platform.")
        )
    )
