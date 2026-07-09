from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum, IntEnum
from typing import ClassVar, Mapping, Sequence


HomeBucketId = str
WorkerId = str
Score = int
TimeMillis = int
LaneRank = int
Dirty = int


class WorkerScorePolarity(IntEnum):
    """Signed worker acquisition lane.

    This is intentionally weaker than a lifecycle band. A worker is a
    long-lived resource identity, so score sign only says which acquisition
    lane may inspect it.
    """

    HOT_ACQUIRE = 1
    RECOVERY_RECHECK = -1


class WorkerScoreTransitionStatus(Enum):
    TRANSITIONED = "transitioned"
    NOOP = "noop"
    STALE = "stale"
    INVALID = "invalid"


@dataclass(frozen=True)
class WorkerScoreState:
    worker_id: WorkerId
    score: Score
    polarity: WorkerScorePolarity
    time_millis: TimeMillis
    lane_rank: LaneRank
    dirty: Dirty


@dataclass(frozen=True)
class WorkerScoreTransitionResult:
    status: WorkerScoreTransitionStatus
    score: Score | None = None


class WorkerScoreCore(ABC):
    """Worker score core interface.

    Worker score is a signed acquisition coordinate:

    - positive score means HOT_ACQUIRE worker-acquire visibility;
    - negative score means RECOVERY_RECHECK recovery visibility;
    - abs(score) carries the internal time coordinate, laneRank, and dirty.

    It is not a worker lifecycle state machine. There is no PARKED band,
    MANUAL_DISABLED band, transport session state, or task-demand truth here.
    Parked / disabled / drain / maintenance are owner evidence plus
    same-polarity holds.

    The public surface stays score-mechanism-first:

    - no business event names;
    - no transition-source parameter;
    - no candidate DTOs beyond (workerId, observedScore);
    - no internal score range coordinates;
    - no caller-supplied cold time slot, scan bounds, polarity sign, dirty bit,
      or encoded base/tag fields;
    - no fake strategy knobs for unimplemented business workflows;
    - no task backlog, task score, or transport mutation;
    - no decoded observed-score construction by callers.
    """

    HOT_ACQUIRE_POLARITY: ClassVar[int] = int(WorkerScorePolarity.HOT_ACQUIRE)
    RECOVERY_RECHECK_POLARITY: ClassVar[int] = int(WorkerScorePolarity.RECOVERY_RECHECK)

    ZERO_SCORE: ClassVar[int] = 0
    MIN_BASE: ClassVar[int] = 1
    MIN_TIME_SLOT: ClassVar[int] = 0
    TIME_SCALE: ClassVar[int] = 10
    SLOT_MILLIS: ClassVar[int] = 1_000 // TIME_SCALE
    MAX_TIME_SLOT: ClassVar[int] = 99_999_999_999
    PAUSE_TIME_SLOT: ClassVar[int] = MAX_TIME_SLOT
    MIN_TIME_MILLIS: ClassVar[int] = MIN_TIME_SLOT * SLOT_MILLIS
    MAX_TIME_MILLIS: ClassVar[int] = MAX_TIME_SLOT * SLOT_MILLIS
    PAUSE_TIME_MILLIS: ClassVar[int] = MAX_TIME_MILLIS
    MIN_LANE_RANK: ClassVar[int] = 0
    MAX_LANE_RANK: ClassVar[int] = 99
    LANE_RANK_FACTOR: ClassVar[int] = 100
    MIN_DIRTY: ClassVar[int] = 0
    MAX_DIRTY: ClassVar[int] = 1
    DIRTY_FACTOR: ClassVar[int] = 2
    SLOT_FACTOR: ClassVar[int] = LANE_RANK_FACTOR * DIRTY_FACTOR

    def __init__(
        self,
        *,
        lane_rank_factor: int = LANE_RANK_FACTOR,
        dirty_factor: int = DIRTY_FACTOR,
    ) -> None:
        pass

    @abstractmethod
    def get_score_states(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerScoreState | None]:
        """Return stored worker score states.

        This is batch-only to discourage N+1 point reads. Missing scores are
        represented by a present worker id with None. Score absence is not an
        unavailable-worker state.
        """
        pass

    @abstractmethod
    def acquire_hot_acquire_candidates(
        self,
        *,
        home_bucket_id: HomeBucketId,
        limit: int,
    ) -> Sequence[tuple[WorkerId, Score]]:
        """Acquire due HOT_ACQUIRE worker candidates.

        The returned score is a complete signed observed-score fence. Callers
        must not trim, decode, construct, or reinterpret it. Ordinary monotonic
        score writes do not need it; lowering operations such as release or
        recovery exhaustion use it as exact CAS protection.
        """
        pass

    @abstractmethod
    def acquire_recovery_recheck_candidates(
        self,
        *,
        home_bucket_id: HomeBucketId,
        limit: int,
    ) -> Sequence[tuple[WorkerId, Score]]:
        """Acquire due RECOVERY_RECHECK candidates for recovery validation.

        This is not a worker selection lane. It must not return a selected
        worker handle to assignment-dispatch.
        """
        pass

    @abstractmethod
    def initialize_hot_acquire_score(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        lane_rank: LaneRank,
    ) -> WorkerScoreTransitionResult:
        """Create the first score for a validated worker.

        Initialization enters HOT_ACQUIRE. The implementation owns the initial
        time coordinate; caller-provided lane_rank is policy-owned ordering /
        budget / tie-break input.
        """
        pass

    @abstractmethod
    def rewrite_current_score(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        target_time_millis: TimeMillis,
        target_lane_rank: LaneRank | None = None,
    ) -> WorkerScoreTransitionResult:
        """Rewrite the current score within the same polarity.

        This is the ordinary same-lane score update used for renew, retry,
        cooldown, manual hold, drain, maintenance, or policy hold. Implementations
        read the current stored score, preserve its polarity and dirty bit,
        require target time to map after the stored time slot, and default
        target_lane_rank to the stored lane_rank. No observed-score CAS is
        required because this operation never lowers the score coordinate.
        """
        pass

    @abstractmethod
    def renew_current_lease(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        target_time_millis: TimeMillis,
    ) -> WorkerScoreTransitionResult:
        """Renew a due worker score as the current lease owner.

        This is the only API that clears dirty. Implementations read the
        current stored score, require stored time slot < current time slot,
        require target_time_millis > current time millis, preserve polarity and
        lane_rank, and write dirty=0. It is intentionally narrower than
        rewrite_current_score.
        """
        pass

    @abstractmethod
    def mark_current_lease_dirty(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
    ) -> WorkerScoreTransitionResult:
        """Mark the current or future-held score dirty.

        This is the non-lease-owner side of the dirty fence. Implementations
        read the current stored score, only set dirty=1, and preserve polarity,
        score time coordinate, and lane_rank. Expired scores can be left
        unchanged because no active score lease needs a dirty fence.
        """
        pass

    @abstractmethod
    def toggle_current_polarity(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
        target_lane_rank: LaneRank,
    ) -> WorkerScoreTransitionResult:
        """Move the current score to the opposite polarity.

        Implementations require storedScore == observed_score. The target
        polarity is simply the opposite sign. Time coordinate and dirty are
        preserved; target_lane_rank is explicit because HOT_ACQUIRE and
        RECOVERY_RECHECK lane_rank meanings are lane-local. Full observed-score
        CAS is intentional here because cross-polarity writes are important
        owner transitions.
        """
        pass

    @abstractmethod
    def exhaust_recovery_recheck(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
    ) -> WorkerScoreTransitionResult:
        """Move RECOVERY_RECHECK outside the routine recovery window.

        Recovery exhausted / cold parked is represented by a too-old
        RECOVERY_RECHECK time coordinate, not a far-future hold. Implementations must
        require storedScore == observed_score and source polarity
        RECOVERY_RECHECK. The implementation mints the cold coordinate internally
        using its recovery lookback policy. Dirty and lane_rank are preserved.
        """
        pass

    @abstractmethod
    def release_score_hold(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
        release_time_millis: TimeMillis,
    ) -> WorkerScoreTransitionResult:
        """Release an exact held score while preserving polarity.

        Release is the only ordinary operation allowed to lower the score time
        coordinate. It
        is not a RECOVERY_RECHECK -> HOT_ACQUIRE reopen. If observed_score is negative, the
        worker remains in RECOVERY_RECHECK and still requires recovery validation
        before hot reservation.
        """
        pass
