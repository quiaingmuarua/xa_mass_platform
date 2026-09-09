package com.xa.mass.kernel.score;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface TaskItemScoreBandCore {

    int ACTIVE_TAG = 1;
    int MIN_TERMINAL_TAG = 2;
    int MAX_TERMINAL_TAG = 9;
    int MAX_ITEM_BATCH_SIZE = 100;
    int MIN_REMAINING_BUDGET = 0;
    int MAX_REMAINING_BUDGET = 99;
    int TERMINAL_SUFFIX = 0;
    long SUFFIX_FACTOR = 100;
    long SLOT_MILLIS = 100;
    long MIN_TIME_SLOT = 0;
    long MAX_TIME_SLOT = 99_999_999_999L;
    long TIME_SLOT_FACTOR = MAX_TIME_SLOT + 1;
    long TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR;
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

    /**
     * Advances existing members to a terminal tag (2..9) only when the encoded
     * target is greater. At most 100 per-Item targets, in input order.
     * Missing or corrupt members are never created or overwritten.
     */
    Map<String, TaskItemScoreTransitionResult> promoteItemOutcomes(
            String taskId,
            Map<String, TaskItemOutcomeTarget> targets
    );

    record TaskItemOutcomeTarget(int tag, long timeMillis) {
    }

    /**
     * Reads at most 100 IDs in one Owner operation. Missing members map to null;
     * corrupt scores fail the read. Time is band-local Score time.
     */
    Map<String, @Nullable TaskItemScoreState> getItemScoreStates(
            String taskId,
            List<String> messageIds
    );

    enum TaskItemScoreBand {
        ACTIVE("active"),
        TERMINAL("terminal");

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
            int tag,
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
