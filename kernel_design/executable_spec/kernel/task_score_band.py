from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import ClassVar, Mapping, Sequence


TaskId = str
Score = int
TimeMillis = int
Suffix = int


class TaskScoreBand(Enum):
    """Decoded score-band view.

    Business event names are intentionally absent. The kernel interface only
    sees score coordinates and task ids.
    """

    PRE_REVIEW = "pre_review"
    RUNNING_VISIBLE = "running_visible"
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
    time_millis: TimeMillis | None
    suffix: Suffix | None

    def is_initial(self) -> bool:
        """Return whether this RUNNING state uses the fixed INITIAL range."""
        return (
            self.band is TaskScoreBand.RUNNING_VISIBLE
            and self.time_millis is not None
            and self.suffix == TaskScoreBandCore.MIN_SUFFIX
            and self.time_millis
            <= TaskScoreBandCore.INITIAL_TIME_CEILING_MILLIS
        )

    def is_due_normal(self, current_time_millis: TimeMillis) -> bool:
        """Return whether this state is due in the NORMAL RUNNING range."""
        if current_time_millis < TaskScoreBandCore.MIN_TIME_MILLIS:
            return False
        current_slot_millis = (
            current_time_millis
            // TaskScoreBandCore.SLOT_MILLIS
            * TaskScoreBandCore.SLOT_MILLIS
        )
        return (
            self.band is TaskScoreBand.RUNNING_VISIBLE
            and self.time_millis is not None
            and self.suffix == TaskScoreBandCore.MIN_SUFFIX
            and self.time_millis >= TaskScoreBandCore.NORMAL_TIME_MIN_MILLIS
            and self.time_millis < current_slot_millis
        )


@dataclass(frozen=True)
class TaskScoreTransitionResult:
    status: TaskScoreTransitionStatus
    score: Score | None = None


class TaskScoreBandCore(ABC):
    """Task score-band core interface.

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
    can pass them directly, the core boundary has already been broken.
    """

    RUNNING_VISIBLE_TAG: ClassVar[int] = 1
    PRE_REVIEW_TAG: ClassVar[int] = 2
    VALID_POSITIVE_TAGS: ClassVar[frozenset[int]] = frozenset(
        {RUNNING_VISIBLE_TAG, PRE_REVIEW_TAG}
    )

    TERMINAL_SCORE_MAX: ClassVar[int] = -1
    MUTABLE_SCORE_MIN: ClassVar[int] = 1
    MIN_TIME_SLOT: ClassVar[int] = 0
    TIME_SCALE: ClassVar[int] = 10
    SLOT_MILLIS: ClassVar[int] = 1_000 // TIME_SCALE
    MIN_SUFFIX: ClassVar[int] = 0
    MAX_SUFFIX: ClassVar[int] = 99
    SUFFIX_FACTOR: ClassVar[int] = 100
    TIME_SLOT_FACTOR: ClassVar[int] = 100_000_000_000
    MAX_TIME_SLOT: ClassVar[int] = 99_999_999_999
    PAUSE_TIME_SLOT: ClassVar[int] = MAX_TIME_SLOT
    MIN_TIME_MILLIS: ClassVar[int] = MIN_TIME_SLOT * SLOT_MILLIS
    MAX_TIME_MILLIS: ClassVar[int] = MAX_TIME_SLOT * SLOT_MILLIS
    PAUSE_TIME_MILLIS: ClassVar[int] = MAX_TIME_MILLIS
    DEFAULT_TAG_FACTOR: ClassVar[int] = TIME_SLOT_FACTOR * SUFFIX_FACTOR
    MAX_TASK_SCORE_PREVIEW_LIMIT: ClassVar[int] = 100
    INITIAL_TIME_CEILING_MILLIS: ClassVar[int] = 10_000
    INITIAL_PRIORITY_STEP_MILLIS: ClassVar[int] = SLOT_MILLIS
    NORMAL_TIME_MIN_MILLIS: ClassVar[int] = (
        INITIAL_TIME_CEILING_MILLIS + SLOT_MILLIS
    )

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
    def preview_score_states(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskScoreState]:
        """Return one bounded highest-score runtime preview window.

        The owner preserves descending score order and decodes each member into
        its semantic band. The operation exposes no cursor or raw range input;
        repeated windows need not be stable when scores change.
        """
        pass

    @abstractmethod
    def count_running_tasks(self) -> int:
        """Return the current number of scores in the RUNNING band."""
        pass

    @abstractmethod
    def acquire_dispatch_work_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        """Acquire due NORMAL RUNNING task ids for dispatch-work rounds."""
        pass

    @abstractmethod
    def acquire_initial_running_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        """Acquire INITIAL RUNNING ids in descending startup priority."""
        pass

    @abstractmethod
    def initialize_score(
        self,
        *,
        task_id: TaskId,
        suffix: Suffix,
        lease_duration_millis: TimeMillis,
    ) -> TaskScoreTransitionResult:
        """Acquire the PRE_REVIEW initialization lease.

        A missing score is initialized with a future lease-until coordinate.
        Any existing score makes initialization fail, regardless of band,
        suffix, or due time. The implementation converts lease duration into an
        absolute future time slot; callers never construct the lease score.
        """
        pass

    @abstractmethod
    def start_observed_pre_review_task(
        self,
        *,
        task_id: TaskId,
        observed_pre_review_score: Score,
        priority: int,
    ) -> TaskScoreTransitionResult:
        """Start the exact observed PRE_REVIEW Task at its INITIAL coordinate."""
        pass

    @abstractmethod
    def promote_observed_initial_task(
        self,
        *,
        task_id: TaskId,
        observed_initial_score: Score,
    ) -> TaskScoreTransitionResult:
        """Promote the exact INITIAL RUNNING score to current NORMAL time."""
        pass

    @abstractmethod
    def rewrite_same_band_time_millis(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_time_millis: TimeMillis,
    ) -> TaskScoreTransitionResult:
        """Rewrite same-band time while preserving stored suffix.

        Time input is absolute milliseconds. The kernel converts it to the
        internal slot coordinate and does not expose a stable delta API. Owners
        that need delay-based behavior compute the target time before calling
        this method. This operation is a same-band range mint and does not
        consume scheduling-round suffix budget. RUNNING rewrites are confined
        to NORMAL coordinates and cannot promote INITIAL Tasks.
        """
        pass

    @abstractmethod
    def park_observed_idle_task(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
    ) -> TaskScoreTransitionResult:
        """Move the exact observed NORMAL RUNNING score to the idle park.

        The private park coordinate belongs to the score owner. Callers cannot
        choose it or use this operation as an arbitrary future hold.
        """
        pass

    @abstractmethod
    def try_release_idle_park(
        self,
        *,
        task_id: TaskId,
    ) -> TaskScoreTransitionResult:
        """Release the private idle park or accept an unprotected positive score.

        The implementation reads and classifies the current score atomically.
        The exact private RUNNING park is released to the current due slot;
        a positive score below the park or above the RUNNING band is a NOOP.
        Missing, terminal, and RUNNING pause coordinates are rejected without
        exposing the private score to callers.
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
    def close_observed_score(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        """Close the exact observed positive score to a terminal score."""
        pass

    @abstractmethod
    def release_observed_score_hold(
        self,
        *,
        task_id: TaskId,
        observed_hold_score: Score,
    ) -> TaskScoreTransitionResult:
        """Release an exact held score.

        The score owner derives current time, tag, and suffix internally. The
        release may only move time nearer or keep it unchanged. It does not
        reset same-band suffix.
        """
        pass
