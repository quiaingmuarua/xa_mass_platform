from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum, IntEnum
from typing import ClassVar, Mapping, Sequence


HomeBucketId = str
WorkerId = str
Score = int
EpochSecond = int
Suffix = int


class WorkerScorePolarity(IntEnum):
    """Signed worker acquisition lane.

    This is intentionally weaker than a lifecycle band. A worker is a
    long-lived resource identity, so score sign only says which acquisition
    lane may inspect it.
    """

    HOT = 1
    LOW_RECHECK = -1


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
    suffix: Suffix


@dataclass(frozen=True)
class WorkerScoreTransitionResult:
    status: WorkerScoreTransitionStatus
    score: Score | None = None


class WorkerScoreCore(ABC):
    """Worker score core interface.

    Worker score is a signed acquisition coordinate:

    - positive score means HOT worker-acquire visibility;
    - negative score means LOW_RECHECK recovery visibility;
    - abs(score) carries epochSecond and suffix.

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

    HOT_POLARITY: ClassVar[int] = int(WorkerScorePolarity.HOT)
    LOW_RECHECK_POLARITY: ClassVar[int] = int(WorkerScorePolarity.LOW_RECHECK)

    ZERO_SCORE: ClassVar[int] = 0
    MIN_BASE: ClassVar[int] = 1
    MIN_EPOCH_SECOND: ClassVar[int] = 0
    MAX_EPOCH_SECOND: ClassVar[int] = 9_999_999_999
    PAUSE_EPOCH_SECOND: ClassVar[int] = MAX_EPOCH_SECOND
    MIN_SUFFIX: ClassVar[int] = 0
    MAX_SUFFIX: ClassVar[int] = 99
    SUFFIX_FACTOR: ClassVar[int] = 100

    def __init__(
        self,
        *,
        suffix_factor: int = SUFFIX_FACTOR,
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
    def acquire_hot_workers(
        self,
        *,
        home_bucket_id: HomeBucketId,
        limit: int,
    ) -> Sequence[tuple[WorkerId, Score]]:
        """Acquire due HOT worker candidates.

        The returned score is a complete signed observed-score fence. Callers
        may pass it back to worker score primitives, but must not trim, decode,
        construct, or reinterpret it outside worker-runtime score/admission
        logic.
        """
        pass

    @abstractmethod
    def acquire_low_recheck_workers(
        self,
        *,
        home_bucket_id: HomeBucketId,
        limit: int,
    ) -> Sequence[tuple[WorkerId, Score]]:
        """Acquire due LOW_RECHECK candidates for recovery validation.

        This is not a worker selection lane. It must not return a selected
        worker handle to assignment-dispatch.
        """
        pass

    @abstractmethod
    def initialize_hot_score(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        suffix: Suffix,
    ) -> WorkerScoreTransitionResult:
        """Create the first score for a validated worker.

        Initialization enters HOT. The implementation owns the initial
        epochSecond; caller-provided suffix is policy-owned ordering / budget /
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
        target_suffix: Suffix | None = None,
    ) -> WorkerScoreTransitionResult:
        """Rewrite through an exact signed observed-score fence.

        Admission and recovery rounds use this after acquiring a worker.
        Implementations must require storedScore == observedScore.

        Same-polarity rewrite preserves suffix when target_suffix is omitted.
        Polarity flip is an owner-validated availability transition and must
        provide a target_suffix because HOT and LOW_RECHECK suffix meanings are
        lane-local. target_epoch_second must not be lower than the observed
        epochSecond.
        """
        pass

    @abstractmethod
    def hold_current_polarity(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        target_epoch_second: EpochSecond,
        target_suffix: Suffix | None = None,
    ) -> WorkerScoreTransitionResult:
        """Hold the currently stored polarity without reopening or blocking.

        Manual disable, drain, maintenance, capacity hold, parked, and recovery
        exhausted all fit this shape. The implementation reads the stored score,
        preserves sign, requires target_epoch_second to be same or newer than the
        stored epochSecond, and preserves suffix unless target_suffix is
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
        is not a LOW_RECHECK -> HOT reopen. If observed_score is negative, the
        worker remains in LOW_RECHECK and still requires recovery validation
        before hot admission.
        """
        pass
