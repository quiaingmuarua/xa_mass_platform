package com.xa.mass.task.runtime;

public record TaskScoreV1(long score) {

    public static final long TIME_SCORE_FLOOR = 1_000_000_000_000L;
    public static final long MAINT_ACTIVE = 100L;
    public static final long PARKED_PAUSED = -10L;
    public static final long PARKED_BLOCKED = -20L;

    public static TaskScoreV1 dueAt(long dueAtMillis) {
        return new TaskScoreV1(Math.max(TIME_SCORE_FLOOR, dueAtMillis));
    }

    public static TaskScoreV1 maintActive() {
        return new TaskScoreV1(MAINT_ACTIVE);
    }

    public static TaskScoreV1 pausedParked() {
        return new TaskScoreV1(PARKED_PAUSED);
    }

    public static TaskScoreV1 blockedParked() {
        return new TaskScoreV1(PARKED_BLOCKED);
    }

    public boolean isSchedulableBand() {
        return score >= TIME_SCORE_FLOOR;
    }

    public boolean isMaintenanceBand() {
        return score >= 0L && score < TIME_SCORE_FLOOR;
    }

    public boolean isPausedParked() {
        return score == PARKED_PAUSED;
    }

    public boolean isBlockedParked() {
        return score == PARKED_BLOCKED || score < 0L && score != PARKED_PAUSED;
    }
}
