package com.xa.mass.kernel.score;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface WorkerScoreCore {

    int HOT_ACQUIRE_POLARITY = 1;
    int RECOVERY_RECHECK_POLARITY = -1;
    long ZERO_SCORE = 0;
    long MIN_BASE = 1;
    long MIN_TIME_SLOT = 0;
    long SLOT_MILLIS = 100;
    long MAX_TIME_SLOT = 99_999_999_999L;
    long PAUSE_TIME_SLOT = MAX_TIME_SLOT;
    long MIN_TIME_MILLIS = 0;
    long MAX_TIME_MILLIS = MAX_TIME_SLOT * SLOT_MILLIS;
    long PAUSE_TIME_MILLIS = MAX_TIME_MILLIS;
    int MIN_DIRTY = 0;
    int MAX_DIRTY = 1;
    int SLOT_FACTOR = 2;
    int MAX_SCORE_BATCH_SIZE = 100;
    int MAX_REGISTRATION_BATCH_SIZE = 100;
    int MAX_REGISTERED_WORKER_SAMPLE_LIMIT = 1000;

    Map<String, @Nullable WorkerScoreState> getScoreStates(
            String homeBucketId,
            List<String> workerIds
    );

    /**
     * Reads the head of the current due HOT range, including either dirty value.
     * Returns an immutable map in ascending score/member order. Limit is 1..100 raw rows;
     * corrupt rows are omitted without scanning replacements or changing stored scores.
     * Successful acquisition moves candidates out of this range; observation alone does not.
     */
    Map<String, Long> observeDueHotScoreCandidates(
            String workerGroupId,
            @Nullable Long hotEligibilityFloorMillis,
            int limit
    );

    /** Reads a bounded descending HOT head below the exclusive cutoff, without a cursor. */
    List<WorkerScoreObservation> observeHotCandidatesBefore(
            String homeBucketId,
            long hotCutoffMillis,
            int limit
    );

    /** Reads the earliest due rechecks after the cold slot, without an age limit or cursor. */
    List<WorkerScoreObservation> observeRecoveryRecheckCandidates(
            String homeBucketId,
            int limit
    );

    /** Initializes only absent members at the Owner's cold coordinate. */
    Set<String> initializeRegisteredScores(
            String homeBucketId,
            List<String> workerIds
    );

    /** Samples registered members without interpreting their scheduling state. */
    List<String> sampleRegisteredWorkerIds(
            String homeBucketId,
            int limit
    );

    Map<String, WorkerScoreTransitionResult> rewriteCurrentScores(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis
    );

    Map<String, WorkerScoreTransitionResult> acquireObservedHotScoreLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis
    );

    /** Consumes each exact, clean active hold once and returns its execution fence. */
    Map<String, WorkerScoreTransitionResult> confirmActiveHotScoreLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis
    );

    /** Invalidates candidate eligibility for 1..100 unique IDs without changing deadlines. */
    Map<String, WorkerScoreTransitionResult> markCurrentLeasesDirty(
            String homeBucketId,
            List<String> workerIds
    );

    WorkerScoreTransitionResult toggleCurrentPolarity(
            String homeBucketId,
            String workerId,
            long observedScore
    );

    /**
     * Defers up to 100 exact due observations using one delay from Redis execution time.
     * Empty input returns no results; an invalid common delay returns INVALID per member
     * without accessing Redis.
     */
    Map<String, WorkerScoreTransitionResult> deferObservedToRecovery(
            String homeBucketId, Map<String, Long> observedScores, long delayMillis
    );

    /**
     * Corrects current polarity when the stored slot is current/future or no later than
     * the supplied slot. Optional refresh advances only a strictly older past slot;
     * dirty is always retained.
     */
    Map<String, WorkerScoreTransitionResult>
            rewriteCurrentPolarityWithinTimeFence(
                    String homeBucketId,
                    Map<String, Long> suppliedTimeMillisByWorkerId,
                    WorkerScorePolarity targetPolarity,
                    boolean refreshPastTime
            );

    /** Exact-replaces a RECOVERY observation at the fixed cold slot, preserving dirty. */
    WorkerScoreTransitionResult parkObservedRecoveryScore(
            String homeBucketId,
            String workerId,
            long observedScore
    );

    Map<String, WorkerScoreTransitionResult> releaseScoreHolds(
            String homeBucketId,
            Map<String, Long> observedScores,
            long releaseTimeMillis
    );

    /** Releases only the supplied HOT fence or its exact negative counterpart. */
    Map<String, WorkerScoreTransitionResult> releaseObservedHotScoreHolds(
            String homeBucketId,
            Map<String, Long> observedHotScores,
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

}
