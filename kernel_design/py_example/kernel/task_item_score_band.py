from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import ClassVar, Mapping, Sequence

from .task_runtime import MessageId
from .task_score_band import Score, TaskId, TimeMillis


RemainingBudget = int
TaskItemScoreObservation = tuple[Score, RemainingBudget]


class TaskItemScoreBand(Enum):
    """Semantic view of one TaskItem scheduling score."""

    ACTIVE = "active"
    FINAL_FAILED = "final_failed"
    FINAL_SUCCESS = "final_success"


class TaskItemScoreTransitionStatus(Enum):
    TRANSITIONED = "transitioned"
    NOOP = "noop"
    STALE = "stale"
    NOT_FOUND = "not_found"
    INVALID = "invalid"
    CORRUPT = "corrupt"


@dataclass(frozen=True)
class TaskItemScoreState:
    score: Score
    band: TaskItemScoreBand
    time_millis: TimeMillis
    remaining_budget: RemainingBudget | None


@dataclass(frozen=True)
class TaskItemScoreTransitionResult:
    status: TaskItemScoreTransitionStatus
    score: Score | None = None


class TaskItemScoreBandCore(ABC):
    """Independent TaskItem score-axis contract.

    The core owns score encoding, bounded score ranges, remaining-budget
    encoding, same-band stale fences, and cross-band precedence. It never reads
    or writes TaskItem records. Redis/Lua coordinates stay implementation
    private.
    """

    ACTIVE_TAG: ClassVar[int] = 1
    FINAL_FAILED_TAG: ClassVar[int] = 5
    FINAL_SUCCESS_TAG: ClassVar[int] = 9
    VALID_TAGS: ClassVar[frozenset[int]] = frozenset(
        {ACTIVE_TAG, FINAL_FAILED_TAG, FINAL_SUCCESS_TAG}
    )

    MIN_REMAINING_BUDGET: ClassVar[int] = 0
    MAX_REMAINING_BUDGET: ClassVar[int] = 99
    FINAL_SUFFIX: ClassVar[int] = 0
    SUFFIX_FACTOR: ClassVar[int] = 100
    SLOT_MILLIS: ClassVar[int] = 100
    MIN_TIME_SLOT: ClassVar[int] = 0
    MAX_TIME_SLOT: ClassVar[int] = 99_999_999_999
    TIME_SLOT_FACTOR: ClassVar[int] = MAX_TIME_SLOT + 1
    TAG_FACTOR: ClassVar[int] = TIME_SLOT_FACTOR * SUFFIX_FACTOR
    MIN_TIME_MILLIS: ClassVar[int] = MIN_TIME_SLOT * SLOT_MILLIS
    MAX_TIME_MILLIS: ClassVar[int] = MAX_TIME_SLOT * SLOT_MILLIS

    @abstractmethod
    def initialize_item_scores(
        self,
        *,
        task_id: TaskId,
        initial_due_millis_by_message_id: Mapping[MessageId, TimeMillis],
        max_retry_times: int,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        """Initialize missing ACTIVE scores from policy-resolved due times."""
        pass

    @abstractmethod
    def acquire_item_score_candidates(
        self,
        *,
        task_id: TaskId,
        limit: int,
    ) -> Mapping[MessageId, TaskItemScoreObservation]:
        """Return due ACTIVE observations in score order.

        The implementation owns current-time capture and range construction.
        The score is an opaque stale fence; remaining budget is the only
        decoded scheduling value returned to the caller.
        """
        pass

    @abstractmethod
    def rewrite_observed_item_scores(
        self,
        *,
        task_id: TaskId,
        observed_scores: Mapping[MessageId, Score],
        target_time_millis: TimeMillis,
        remaining_budget_delta: int,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        """Exact-CAS ACTIVE scores to a later same-band coordinate.

        remaining_budget_delta is -1 for claim and 0 for retry/hold. The target
        time slot must move forward, budget may not increase, and the full
        score must move forward.
        """
        pass

    @abstractmethod
    def promote_item_outcomes(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
        target_band: TaskItemScoreBand,
        target_time_millis: TimeMillis,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        """Promote current scores to a higher final band.

        The core reads the current score itself and requires targetTag greater
        than currentTag. Cross-band movement does not compare time coordinates:
        the target band's time comes directly from target_time_millis. A
        concurrent rewrite returns STALE; callers do not provide an observed
        score for outcome precedence.
        """
        pass

    @abstractmethod
    def get_item_score_states(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
    ) -> Mapping[MessageId, TaskItemScoreState | None]:
        """Return decoded score states for one bounded diagnostic batch."""
        pass
