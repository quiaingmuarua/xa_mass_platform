package com.xa.mass.kernel.score;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface WorkerScoreCore {

    int MAX_SCORE_BATCH_SIZE = 100;
    int MAX_REGISTRATION_BATCH_SIZE = 100;
    int MAX_REGISTERED_WORKER_SAMPLE_LIMIT = 1000;

    /** Observes 1..100 unique IDs in request order, with one shared local read time. */
    WorkerSchedulingObservation observeSchedulingStates(
            String homeBucketId,
            List<String> workerIds
    );

    WorkerSchedulingChangeStatus pauseScheduling(String homeBucketId, String workerId);

    WorkerSchedulingChangeStatus resumeScheduling(String homeBucketId, String workerId);

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
    Map<String, Long> observeHotCandidatesBefore(
            String homeBucketId,
            long hotCutoffMillis,
            int limit
    );

    /** Reads the earliest due rechecks after the cold slot, without an age limit or cursor. */
    Map<String, Long> observeRecoveryRecheckCandidates(
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
        HOT_ACQUIRE,
        RECOVERY_RECHECK
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

    enum SchedulingState {
        HOT_SCORE_OVERDUE, HELD_HOT, PAUSED, RECOVERY, COLD, MISSING
    }

    enum WorkerSchedulingChangeStatus {
        APPLIED, UNCHANGED, MISSING, CONFLICT
    }

    record WorkerSchedulingObservation(
            long readAtMillis,
            Map<String, SchedulingState> statesByWorkerId
    ) {
        public WorkerSchedulingObservation {
            statesByWorkerId = Collections.unmodifiableMap(new LinkedHashMap<>(statesByWorkerId));
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

}
