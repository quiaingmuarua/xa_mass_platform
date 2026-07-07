from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Mapping, Sequence


TaskId = str
Score = int
EpochSecond = int
Suffix = int


class TaskScoreBand(Enum):
    PRE_REVIEW = "pre_review"
    RUNNING_VISIBLE = "running_visible"
    EMPTY_RUNNING = "empty_running"
    READY_APPROVED = "ready_approved"
    TERMINAL = "terminal"


class TaskScoreTransitionSource(Enum):
    SCHEDULING_ROUND = "scheduling_round"
    OWNER_TRANSITION = "owner_transition"


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


class TaskScoreBandKernel:
    RUNNING_VISIBLE_TAG = 1
    EMPTY_RUNNING_TAG = 2
    READY_APPROVED_TAG = 3

    PRE_REVIEW_MIN_SCORE = -10
    PRE_REVIEW_MAX_SCORE = -1
    SUFFIX_FACTOR = 100
    DEFAULT_TAG_FACTOR = 10_000_000_000 * SUFFIX_FACTOR

    def __init__(
        self,
        *,
        tag_factor: int = DEFAULT_TAG_FACTOR,
        suffix_factor: int = SUFFIX_FACTOR,
    ) -> None:
        pass

    def get_score_state(
        self,
        *,
        task_id: TaskId,
    ) -> TaskScoreState | None:
        pass

    def acquire_worker_allocatable_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        """Acquire RUNNING_VISIBLE, EMPTY_RUNNING, and READY_APPROVED candidates."""
        pass

    def acquire_dispatch_work_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        """Acquire RUNNING_VISIBLE and EMPTY_RUNNING candidates."""
        pass

    def transition_score(
        self,
        *,
        task_id: TaskId,
        expected_score: Score,
        next_score: Score,
        source: TaskScoreTransitionSource,
        evidence: Mapping[str, str] | None = None,
    ) -> TaskScoreTransitionResult:
        pass
