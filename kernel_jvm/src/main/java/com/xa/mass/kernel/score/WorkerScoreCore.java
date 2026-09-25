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

    /** Atomically writes the maximum time and mark=0, retaining polarity. */
    WorkerSchedulingChangeStatus pauseScheduling(String homeBucketId, String workerId);

    WorkerSchedulingChangeStatus resumeScheduling(String homeBucketId, String workerId);

    /**
     * Reads the head of the current due HOT range, with mark=0.
     * Returns an immutable map in ascending score/member order. Limit is 1..100 raw rows;
     * corrupt rows are omitted without scanning replacements or changing stored scores.
     * Successful candidateization moves candidates out of this range; observation alone does not.
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

    /** Exact-acquires strictly due HOT with either mark into a mark=0 execution hold. */
    Map<String, WorkerScoreTransitionResult> acquireObservedHotScoreLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis
    );

    /** Exact due HOT mark=0 becomes candidate mark=1 without changing generation. */
    Map<String, WorkerScoreTransitionResult> candidateizeObservedHotScores(
            String homeBucketId, Map<String, Long> observedScores);

    /** Ascending bounded raw mark=1 head, within the floor and exclusive cutoff. */
    Map<String, Long> observeHotCandidateScoresBefore(
            String homeBucketId, @Nullable Long floorMillis, long cutoffMillis, int limit);

    /** Exact due HOT mark=1 becomes ordinary HOT at Redis execution time. */
    Map<String, WorkerScoreTransitionResult> recycleObservedHotCandidates(
            String homeBucketId, Map<String, Long> observedScores);

    /**
     * Atomically acquires current strictly due HOT, either mark, into mark=0 execution holds.
     * Current/future, RECOVERY and missing members are STALE; corrupt values are INVALID.
     * Empty input is a no-op. Each Lua processes at most 100 unique IDs without pre-reading.
     */
    Map<String, WorkerScoreTransitionResult> acquireCurrentHotScoreLeases(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis
    );

    /**
     * Advances past times except cold RECOVERY to Redis now for 1..100 unique IDs.
     * Clears the HOT candidate mark; preserves RECOVERY mark and both polarities.
     * Current/future coordinates are unchanged.
     */
    Map<String, WorkerScoreTransitionResult> advancePastScoreTimesToNow(
            String homeBucketId,
            List<String> workerIds
    );

    /** Exact-flips polarity, clears the candidate mark and preserves time. */
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
     * Corrects polarity when the stored slot is current/future or no later than the
     * supplied slot. Current/future coordinates retain time and mark. A past polarity
     * change clears mark and advances to max(storedSlot + 1, min(suppliedSlot, redisNowSlot)).
     * Same-polarity evidence is a no-op except for past coordinates below the minimum:
     * evidence and Redis time must reach that minimum before activation can refresh
     * the generation. Zero disables this activation condition.
     */
    Map<String, WorkerScoreTransitionResult>
            rewriteCurrentPolarityWithinTimeFence(
                    String homeBucketId,
                    Map<String, Long> suppliedTimeMillisByWorkerId,
                    WorkerScorePolarity targetPolarity,
                    long minimumTimeMillis
            );

    /** Exact-replaces a RECOVERY observation at the fixed cold slot, preserving mark. */
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
