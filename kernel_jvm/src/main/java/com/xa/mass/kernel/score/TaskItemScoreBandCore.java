package com.xa.mass.kernel.score;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface TaskItemScoreBandCore {

    int TAG_STRIDE = 4;
    int ACTIVE_TAG = 1;
    int FINAL_FAILED_TAG = ACTIVE_TAG + TAG_STRIDE;
    int FINAL_SUCCESS_TAG = FINAL_FAILED_TAG + TAG_STRIDE;
    Set<Integer> VALID_TAGS = Set.of(
            ACTIVE_TAG,
            FINAL_FAILED_TAG,
            FINAL_SUCCESS_TAG
    );
    int MIN_REMAINING_BUDGET = 0;
    int MAX_REMAINING_BUDGET = 99;
    int FINAL_SUFFIX = 0;
    long SUFFIX_FACTOR = 100;
    long SLOT_MILLIS = 100;
    long MIN_TIME_SLOT = 0;
    long MAX_TIME_SLOT = 99_999_999_999L;
    long TIME_SLOT_FACTOR = MAX_TIME_SLOT + 1;
    long TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR;
    long MAX_SAME_BAND_SCORE_DELTA = TAG_FACTOR - 1;
    long MIN_TIME_MILLIS = 0;
    long MAX_TIME_MILLIS = MAX_TIME_SLOT * SLOT_MILLIS;

    Map<String, TaskItemScoreTransitionResult> initializeItemScores(
            String taskId,
            Map<String, Long> initialDueMillisByMessageId,
            int maxRetryTimes
    );

    Map<String, TaskItemScoreObservation> acquireItemScoreCandidates(
            String taskId,
            int limit
    );

    Map<String, Boolean> hasDueActiveItems(List<String> taskIds);

    Map<String, Boolean> hasActiveItems(List<String> taskIds);

    Map<String, TaskItemScoreTransitionResult> rewriteObservedItemScores(
            String taskId,
            Map<String, Long> observedScores,
            long targetTimeMillis,
            int remainingBudgetDelta
    );

    Map<String, TaskItemScoreTransitionResult> promoteItemOutcomes(
            String taskId,
            List<String> messageIds,
            TaskItemScoreBand targetBand,
            long targetTimeMillis
    );

    Map<String, @Nullable TaskItemScoreState> getItemScoreStates(
            String taskId,
            List<String> messageIds
    );

    enum TaskItemScoreBand {
        ACTIVE("active"),
        FINAL_FAILED("final_failed"),
        FINAL_SUCCESS("final_success");

        private final String wireValue;

        TaskItemScoreBand(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    enum TaskItemScoreTransitionStatus {
        TRANSITIONED("transitioned"),
        NOOP("noop"),
        STALE("stale"),
        NOT_FOUND("not_found"),
        INVALID("invalid"),
        CORRUPT("corrupt");

        private final String wireValue;

        TaskItemScoreTransitionStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record TaskItemScoreObservation(
            long score,
            int remainingBudget
    ) {
    }

    record TaskItemScoreState(
            long score,
            TaskItemScoreBand band,
            long timeMillis,
            @Nullable Integer remainingBudget
    ) {
        public TaskItemScoreState {
            Objects.requireNonNull(band, "band");
        }
    }

    record TaskItemScoreTransitionResult(
            TaskItemScoreTransitionStatus status,
            @Nullable Long score
    ) {
        public TaskItemScoreTransitionResult {
            Objects.requireNonNull(status, "status");
        }
    }
}
