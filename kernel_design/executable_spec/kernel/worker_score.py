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
    """Worker-runtime-classified network availability polarity.

    Positive means network available/online and maps to HOT_ACQUIRE. Negative
    means network unavailable/offline and maps to RECOVERY_RECHECK. This is
    intentionally not a lifecycle band or raw transport-session observation;
    Worker is a long-lived resource and worker-runtime owns the validated
    classification.
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

    Worker score is a signed network-availability and acquisition coordinate:

    - positive score means network available/online HOT_ACQUIRE polarity;
    - negative score means network unavailable/offline RECOVERY_RECHECK polarity;
    - abs(score) carries the internal time coordinate, laneRank, and dirty.

    It is not a worker lifecycle state machine. There is no PARKED band,
    MANUAL_DISABLED band, transport session state, or task-demand truth here.
    Parked / disabled / drain / maintenance are owner evidence plus
    same-polarity holds.

    The public surface stays score-mechanism-first:

    - no business event names;
    - no transition-source parameter;
    - acquired HOT candidates are returned only as WorkerId plus opaque score;
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
    ) -> Mapping[WorkerId, Score]:
        """Return bounded due HOT_ACQUIRE Workers by id and observed score.

        This is a read-only score query. Callers may retain each score only as
        the expected fence for `acquire_observed_hot_score_leases`; they must not
        decode or rewrite it. Concurrent rounds may observe the same Worker,
        but only one can later acquire its lease through exact score CAS. The
        mapping does not expose scan order as a public contract.
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
    def rewrite_current_scores(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_ids: Sequence[WorkerId],
        target_time_millis: TimeMillis,
        target_lane_rank: LaneRank | None = None,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        """Rewrite current scores within the same polarity.

        This is the ordinary same-lane score update used for renew, retry,
        cooldown, manual hold, drain, maintenance, or policy hold. Implementations
        read the current stored score, preserve its polarity and dirty bit,
        require target time to map after the stored time slot, and default
        target_lane_rank to the stored lane_rank. No observed-score CAS is
        required because this operation never lowers the score coordinate.
        """
        pass

    @abstractmethod
    def acquire_observed_hot_score_leases(
        self,
        *,
        home_bucket_id: HomeBucketId,
        observed_scores: Mapping[WorkerId, Score],
        target_time_millis: TimeMillis,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        """Lease observed due HOT_ACQUIRE scores through independent exact CAS.

        Implementations validate the opaque observation as a due HOT score,
        require the target to be a future time slot, preserve lane rank, clear
        dirty, and write each score only when storedScore still equals its
        observed score. The batch is not an all-or-nothing transaction.
        """
        pass

    @abstractmethod
    def renew_active_hot_score_leases(
        self,
        *,
        home_bucket_id: HomeBucketId,
        observed_scores: Mapping[WorkerId, Score],
        target_time_millis: TimeMillis,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        """Extend still-active HOT_ACQUIRE score leases without rematching.

        Implementations require storedScore == observed_score, observed time
        polarity == HOT_ACQUIRE, observed time slot >= current time slot,
        dirty == 0, and target time slot after the observed time slot. Dirty
        returns STALE because active renewal does not prove current descriptor /
        dynamic metadata validity.
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
        score time coordinate, and lane_rank. This applies to both due scores
        and future-held allocation leases. A relevant metadata change after
        lease acquisition marks the lease dirty so later dispatch renewal or
        revalidation cannot continue from stale match evidence.
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
    def release_score_holds(
        self,
        *,
        home_bucket_id: HomeBucketId,
        observed_scores: Mapping[WorkerId, Score],
        release_time_millis: TimeMillis,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        """Release exact held scores while preserving polarity.

        Release time must not precede the current score-clock slot. Its minted
        absolute slot base must be lower than each accepted absolute observed
        score. Release preserves polarity, lane rank, and dirty through exact
        observed-score CAS. It is not a RECOVERY_RECHECK -> HOT_ACQUIRE reopen.
        If an observed score is negative, the worker remains in
        RECOVERY_RECHECK and still requires recovery validation before hot
        score acquire.
        """
        pass
