from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum, IntEnum
from typing import ClassVar, Mapping, Sequence


HomeBucketId = str
WorkerId = str
Score = int
EpochSecond = int
LaneRank = int
Version = int


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
    epoch_second: EpochSecond
    lane_rank: LaneRank
    version: Version


@dataclass(frozen=True)
class WorkerScoreTransitionResult:
    status: WorkerScoreTransitionStatus
    score: Score | None = None


class WorkerScoreCore(ABC):
    """Worker score core interface.

    Worker score is a signed acquisition coordinate:

    - positive score means HOT_ACQUIRE worker-acquire visibility;
    - negative score means RECOVERY_RECHECK recovery visibility;
    - abs(score) carries epochSecond, laneRank, and version.

    It is not a worker lifecycle state machine. There is no PARKED band,
    MANUAL_DISABLED band, transport session state, or task-demand truth here.
    Parked / disabled / drain / maintenance are owner evidence plus
    same-polarity holds.

    The public surface stays score-mechanism-first:

    - no business event names;
    - no transition-source parameter;
    - no candidate DTOs beyond (workerId, observedScore);
    - no internal score range coordinates;
    - no task backlog, task score, or transport mutation;
    - no decoded observed-score construction by callers.
    """

    HOT_ACQUIRE_POLARITY: ClassVar[int] = int(WorkerScorePolarity.HOT_ACQUIRE)
    RECOVERY_RECHECK_POLARITY: ClassVar[int] = int(WorkerScorePolarity.RECOVERY_RECHECK)

    ZERO_SCORE: ClassVar[int] = 0
    MIN_BASE: ClassVar[int] = 1
    MIN_EPOCH_SECOND: ClassVar[int] = 0
    MAX_EPOCH_SECOND: ClassVar[int] = 9_999_999_999
    PAUSE_EPOCH_SECOND: ClassVar[int] = MAX_EPOCH_SECOND
    MIN_LANE_RANK: ClassVar[int] = 0
    MAX_LANE_RANK: ClassVar[int] = 99
    LANE_RANK_FACTOR: ClassVar[int] = 100
    MIN_VERSION: ClassVar[int] = 0
    MAX_VERSION: ClassVar[int] = 99
    VERSION_FACTOR: ClassVar[int] = 100
    SLOT_FACTOR: ClassVar[int] = LANE_RANK_FACTOR * VERSION_FACTOR

    def __init__(
        self,
        *,
        lane_rank_factor: int = LANE_RANK_FACTOR,
        version_factor: int = VERSION_FACTOR,
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
        may pass it back to worker score primitives, but must not trim, decode,
        construct, or reinterpret it outside worker-runtime score/admission
        logic.
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
        epochSecond; caller-provided lane_rank is policy-owned ordering / budget /
        tie-break input.
        """
        pass

    @abstractmethod
    def rewrite_observed_score(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
        target_polarity: WorkerScorePolarity,
        target_epoch_second: EpochSecond,
        target_lane_rank: LaneRank | None = None,
    ) -> WorkerScoreTransitionResult:
        """Rewrite through an exact signed observed-score fence.

        Admission and recovery rounds use this after acquiring a worker.
        Implementations must require storedScore == observedScore.

        Same-polarity rewrite preserves lane_rank when target_lane_rank is omitted.
        Polarity flip is an owner-validated availability transition and must
        provide a target_lane_rank because HOT_ACQUIRE and RECOVERY_RECHECK lane_rank meanings are
        lane-local. target_epoch_second must not be lower than the observed
        epochSecond. Score version normally preserves the observed value; a
        scheduling-signature refresh may change it inside worker-runtime owner
        logic.
        """
        pass

    @abstractmethod
    def hold_current_polarity(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        target_epoch_second: EpochSecond,
        target_lane_rank: LaneRank | None = None,
    ) -> WorkerScoreTransitionResult:
        """Hold the currently stored polarity without reopening or blocking.

        Manual disable, drain, maintenance, capacity hold, parked, and recovery
        exhausted all fit this shape. The implementation reads the stored score,
        preserves sign, requires target_epoch_second to be same or newer than the
        stored epochSecond, and preserves lane_rank unless target_lane_rank is
        supplied.
        """
        pass

    @abstractmethod
    def release_score_hold(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
        release_epoch_second: EpochSecond,
    ) -> WorkerScoreTransitionResult:
        """Release an exact held score while preserving polarity.

        Release is the only ordinary operation allowed to lower epochSecond. It
        is not a RECOVERY_RECHECK -> HOT_ACQUIRE reopen. If observed_score is negative, the
        worker remains in RECOVERY_RECHECK and still requires recovery validation
        before hot admission.
        """
        pass
