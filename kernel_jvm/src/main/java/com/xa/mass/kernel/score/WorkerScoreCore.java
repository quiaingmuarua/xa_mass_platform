package com.xa.mass.kernel.score;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface WorkerScoreCore {

    int HOT_ACQUIRE_POLARITY = 1;
    int RECOVERY_RECHECK_POLARITY = -1;
    long ZERO_SCORE = 0;
    long MIN_BASE = 1;
    long MIN_TIME_SLOT = 0;
    int TIME_SCALE = 10;
    long SLOT_MILLIS = 100;
    long MAX_TIME_SLOT = 99_999_999_999L;
    long PAUSE_TIME_SLOT = MAX_TIME_SLOT;
    long MIN_TIME_MILLIS = 0;
    long MAX_TIME_MILLIS = MAX_TIME_SLOT * SLOT_MILLIS;
    long PAUSE_TIME_MILLIS = MAX_TIME_MILLIS;
    int MIN_LANE_RANK = 0;
    int MAX_LANE_RANK = 99;
    int LANE_RANK_FACTOR = 100;
    int MIN_DIRTY = 0;
    int MAX_DIRTY = 1;
    int DIRTY_FACTOR = 2;
    int SLOT_FACTOR = LANE_RANK_FACTOR * DIRTY_FACTOR;

    Map<String, @Nullable WorkerScoreState> getScoreStates(
            String homeBucketId,
            List<String> workerIds
    );

    Map<String, Long> acquireHotAcquireCandidates(
            String homeBucketId,
            int limit
    );

    Map<String, Long> observeDueHotScores(
            String homeBucketId,
            List<String> workerIds
    );

    List<WorkerScoreObservation> acquireRecoveryRecheckCandidates(
            String homeBucketId,
            int limit
    );

    WorkerScoreTransitionResult initializeHotAcquireScore(
            String homeBucketId,
            String workerId
    );

    Map<String, WorkerScoreTransitionResult> rewriteCurrentScores(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis,
            @Nullable Integer targetLaneRank
    );

    Map<String, WorkerScoreTransitionResult> acquireObservedHotScoreLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis
    );

    Map<String, WorkerScoreTransitionResult> renewActiveHotScoreLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis
    );

    WorkerScoreTransitionResult markCurrentLeaseDirty(
            String homeBucketId,
            String workerId
    );

    WorkerScoreTransitionResult toggleCurrentPolarity(
            String homeBucketId,
            String workerId,
            long observedScore
    );

    WorkerScoreTransitionResult exhaustRecoveryRecheck(
            String homeBucketId,
            String workerId,
            long observedScore
    );

    Map<String, WorkerScoreTransitionResult> applyWorkerServiceabilityChecks(
            String homeBucketId,
            Map<String, WorkerServiceabilityCheck> checksByWorkerId,
            int maxRecoveryAttempts
    );

    Map<String, WorkerScoreTransitionResult> releaseScoreHolds(
            String homeBucketId,
            Map<String, Long> observedScores,
            long releaseTimeMillis
    );

    enum WorkerScorePolarity {
        HOT_ACQUIRE(1),
        RECOVERY_RECHECK(-1);

        private final int value;

        WorkerScorePolarity(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    enum WorkerScoreTransitionStatus {
        TRANSITIONED("transitioned"),
        NOOP("noop"),
        STALE("stale"),
        INVALID("invalid");

        private final String wireValue;

        WorkerScoreTransitionStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record WorkerScoreState(
            String workerId,
            long score,
            WorkerScorePolarity polarity,
            long timeMillis,
            int laneRank,
            int dirty
    ) {
        public WorkerScoreState {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(polarity, "polarity");
        }
    }

    record WorkerScoreTransitionResult(
            WorkerScoreTransitionStatus status,
            @Nullable Long score
    ) {
        public WorkerScoreTransitionResult {
            Objects.requireNonNull(status, "status");
        }
    }

    record WorkerScoreObservation(
            String workerId,
            long score
    ) {
        public WorkerScoreObservation {
            Objects.requireNonNull(workerId, "workerId");
        }
    }

    record WorkerServiceabilityCheck(
            long checkStartedAtMillis,
            boolean serviceable
    ) {
    }
}
