package com.xa.mass.kernel.score;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface TaskScoreBandCore {

    int RUNNING_VISIBLE_TAG = 1;
    int ADMISSION_VISIBLE_TAG = 2;
    int PRE_REVIEW_TAG = 3;
    Set<Integer> VALID_POSITIVE_TAGS = Set.of(
            RUNNING_VISIBLE_TAG,
            ADMISSION_VISIBLE_TAG,
            PRE_REVIEW_TAG
    );
    long TERMINAL_SCORE_MAX = -1;
    long MUTABLE_SCORE_MIN = 1;
    long MIN_TIME_SLOT = 0;
    int TIME_SCALE = 10;
    long SLOT_MILLIS = 100;
    int MIN_SUFFIX = 0;
    int MAX_SUFFIX = 99;
    long SUFFIX_FACTOR = 100;
    long TIME_SLOT_FACTOR = 100_000_000_000L;
    long MAX_TIME_SLOT = 99_999_999_999L;
    long PAUSE_TIME_SLOT = MAX_TIME_SLOT;
    long MIN_TIME_MILLIS = 0;
    long MAX_TIME_MILLIS = MAX_TIME_SLOT * SLOT_MILLIS;
    long PAUSE_TIME_MILLIS = MAX_TIME_MILLIS;
    long DEFAULT_TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR;
    int MAX_TASK_SCORE_PREVIEW_LIMIT = 100;

    Map<String, @Nullable TaskScoreState> getScoreStates(
            List<String> taskIds
    );

    List<TaskScoreState> previewScoreStates(int limit);

    int countRunningCapacityTasks();

    List<String> acquireBandTaskCandidates(
            TaskScoreBand band,
            long beforeTimeMillis,
            int limit
    );

    List<String> acquireDispatchWorkTasks(int limit);

    TaskScoreTransitionResult initializeScore(
            String taskId,
            int suffix,
            long leaseDurationMillis
    );

    TaskScoreTransitionResult rewriteScore(
            String taskId,
            TaskScoreBand expectedBand,
            long targetTimeMillis,
            @Nullable TaskScoreBand targetBand,
            @Nullable Integer targetSuffix
    );

    TaskScoreTransitionResult rewriteSameBandTimeMillis(
            String taskId,
            TaskScoreBand expectedBand,
            long targetTimeMillis
    );

    TaskScoreTransitionResult parkObservedIdleTask(
            String taskId,
            long observedScore
    );

    TaskScoreTransitionResult tryReleaseIdlePark(String taskId);

    TaskScoreTransitionResult closeScore(
            String taskId,
            long terminalScore
    );

    TaskScoreTransitionResult closeObservedScore(
            String taskId,
            long observedScore,
            long terminalScore
    );

    TaskScoreTransitionResult releaseObservedScoreHold(
            String taskId,
            long observedHoldScore
    );

    enum TaskScoreBand {
        PRE_REVIEW("pre_review"),
        RUNNING_VISIBLE("running_visible"),
        ADMISSION_VISIBLE("admission_visible"),
        TERMINAL("terminal");

        private final String wireValue;

        TaskScoreBand(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    enum TaskScoreTransitionStatus {
        TRANSITIONED("transitioned"),
        NOOP("noop"),
        STALE("stale"),
        INVALID("invalid");

        private final String wireValue;

        TaskScoreTransitionStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record TaskScoreState(
            String taskId,
            long score,
            TaskScoreBand band,
            @Nullable Long timeMillis,
            @Nullable Integer suffix
    ) {
        public TaskScoreState {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(band, "band");
        }
    }

    record TaskScoreTransitionResult(
            TaskScoreTransitionStatus status,
            @Nullable Long score
    ) {
        public TaskScoreTransitionResult {
            Objects.requireNonNull(status, "status");
        }
    }
}
