from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import ClassVar, Mapping, Sequence


TaskId = str
Score = int
EpochSecond = int
Suffix = int


class TaskScoreBand(Enum):
    """Decoded score-band view.

    Business event names are intentionally absent. The kernel interface only
    sees score coordinates and task ids.
    """

    PRE_REVIEW = "pre_review"
    RUNNING_VISIBLE = "running_visible"
    READY_APPROVED = "ready_approved"
    TERMINAL = "terminal"


class TaskScoreTransitionStatus(Enum):
    TRANSITIONED = "transitioned"
    NOOP = "noop"
    STALE = "stale"
    INVALID = "invalid"


@dataclass(frozen=True)
class TaskScoreState:
    task_id: TaskId
    score: Score
    band: TaskScoreBand
    epoch_second: EpochSecond | None
    suffix: Suffix | None


@dataclass(frozen=True)
class TaskScoreTransitionResult:
    status: TaskScoreTransitionStatus
    score: Score | None = None


class TaskScoreBandKernel(ABC):
    """Task score-band kernel interface.

    This is an executable-spec shaped interface, not a production storage
    implementation. Implementations may use memory, Redis, or another ordered
    index, but the public surface stays score-first:

    - no business event names;
    - no transition-source parameter;
    - no candidate DTOs;
    - no internal score range coordinates;
    - no append/result/worker truth mutation;
    - no pagination cursor owned by task score.

    Internal Redis/Lua coordinates such as minExpectedScore, maxExpectedScore,
    and targetScoreBase are implementation-private protocol values. If a caller
    can pass them directly, the kernel boundary has already been broken.
    """

    RUNNING_VISIBLE_TAG: ClassVar[int] = 1
    READY_APPROVED_TAG: ClassVar[int] = 2
    PRE_REVIEW_TAG: ClassVar[int] = 3
    VALID_POSITIVE_TAGS: ClassVar[frozenset[int]] = frozenset(
        {RUNNING_VISIBLE_TAG, READY_APPROVED_TAG, PRE_REVIEW_TAG}
    )

    TERMINAL_SCORE_MAX: ClassVar[int] = -1
    MUTABLE_SCORE_MIN: ClassVar[int] = 1
    MIN_EPOCH_SECOND: ClassVar[int] = 0
    EXHAUSTED_SUFFIX: ClassVar[int] = 0
    MIN_SUFFIX: ClassVar[int] = 0
    MAX_SUFFIX: ClassVar[int] = 99
    SUFFIX_FACTOR: ClassVar[int] = 100
    EPOCH_FACTOR: ClassVar[int] = 10_000_000_000
    MAX_EPOCH_SECOND: ClassVar[int] = 9_999_999_999
    PAUSE_EPOCH_SECOND: ClassVar[int] = MAX_EPOCH_SECOND
    DEFAULT_TAG_FACTOR: ClassVar[int] = EPOCH_FACTOR * SUFFIX_FACTOR

    def __init__(
        self,
        *,
        tag_factor: int = DEFAULT_TAG_FACTOR,
        suffix_factor: int = SUFFIX_FACTOR,
    ) -> None:
        pass

    @abstractmethod
    def get_score_states(
        self,
        *,
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, TaskScoreState | None]:
        """Return stored score states for task ids.

        This is intentionally batch-only to discourage N+1 point reads. Missing
        scores are represented by a present task id with None.
        """
        pass

    @abstractmethod
    def acquire_worker_allocatable_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        """Acquire due RUNNING_VISIBLE and READY_APPROVED task ids.

        The implementation owns band-range scans and limit enforcement. It must
        not return PRE_REVIEW, TERMINAL, hard-paused, or non-due future scores.
        """
        pass

    @abstractmethod
    def acquire_dispatch_work_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        """Acquire due RUNNING_VISIBLE task ids for dispatch-work rounds."""
        pass

    @abstractmethod
    def initialize_score(
        self,
        *,
        task_id: TaskId,
        suffix: Suffix,
    ) -> TaskScoreTransitionResult:
        """Create the first score for a task when no score exists.

        Initialization always enters PRE_REVIEW. The implementation owns the
        initial band and epochSecond; callers only provide the owner-defined
        PRE_REVIEW suffix / review-state code.
        """
        pass

    @abstractmethod
    def rewrite_score(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_epoch_second: EpochSecond,
        target_band: TaskScoreBand | None = None,
        target_suffix: Suffix | None = None,
    ) -> TaskScoreTransitionResult:
        """Rewrite a positive score after reading the stored score.

        Ordinary positive rewrites do not trust a caller-supplied full
        expected score. The implementation reads the stored score, checks the
        expected band, then writes a target score only when:

        - target tag keeps or lowers lifecycle direction;
        - target epochSecond is newer than the stored epochSecond;
        - suffix is preserved unless target_suffix is supplied.
        """
        pass

    @abstractmethod
    def rewrite_same_band_epoch(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_epoch_second: EpochSecond,
    ) -> TaskScoreTransitionResult:
        """Rewrite same-band epochSecond while preserving stored suffix.

        Epoch input is absolute. The kernel does not expose a stable
        epoch-delta API. Owners that need delay-based behavior compute the
        target epoch before calling this method. This operation is a same-band
        range mint and does not consume scheduling-round suffix budget.
        """
        pass

    @abstractmethod
    def consume_same_band_budget(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
        target_epoch_second: EpochSecond,
        suffix_delta: int,
    ) -> TaskScoreTransitionResult:
        """Adjust scheduling-band suffix using an exact observed-score fence.

        Suffix budget consumption is scheduling-round evidence. The stored score
        must still equal observed_score, otherwise the round is stale and must
        not overwrite newer same-band classification. suffix_delta must be
        negative; budget replenishment is an explicit owner rewrite, not a
        scheduling consumption primitive.
        """
        pass

    @abstractmethod
    def close_score(
        self,
        *,
        task_id: TaskId,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        """Close any positive score to a negative terminal score."""
        pass

    @abstractmethod
    def release_score_lease(
        self,
        *,
        task_id: TaskId,
        observed_lease_score: Score,
        release_epoch_second: EpochSecond,
    ) -> TaskScoreTransitionResult:
        """Release an exact held score.

        The release target derives tag and suffix from observed_lease_score and
        may only move epochSecond nearer or keep it unchanged. It does not reset
        same-band budget.
        """
        pass
