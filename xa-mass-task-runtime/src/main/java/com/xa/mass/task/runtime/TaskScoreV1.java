package com.xa.mass.task.runtime;

public record TaskScoreV1(long score) {

    public static final long TIME_SCORE_FLOOR = 1_000_000_000_000L;
    public static final long MAINT_ACTIVE = 100L;
    public static final long NON_SCHED_CREATED = 10L;
    public static final long NON_SCHED_MANUAL_BLOCKED = 20L;
    public static final long TERMINAL_REJECTED = -10L;
    public static final long TERMINAL_CANCELLED = -20L;
    public static final long TERMINAL_DISCARDED = -30L;

    public static TaskScoreV1 dueAt(long dueAtMillis) {
        return new TaskScoreV1(Math.max(TIME_SCORE_FLOOR, dueAtMillis));
    }

    public static TaskScoreV1 createdPending() {
        return new TaskScoreV1(NON_SCHED_CREATED);
    }

    public static TaskScoreV1 maintActive() {
        return new TaskScoreV1(MAINT_ACTIVE);
    }

    public static TaskScoreV1 manualBlocked() {
        return new TaskScoreV1(NON_SCHED_MANUAL_BLOCKED);
    }

    public static TaskScoreV1 rejectedTerminal() {
        return new TaskScoreV1(TERMINAL_REJECTED);
    }

    public static TaskScoreV1 cancelledTerminal() {
        return new TaskScoreV1(TERMINAL_CANCELLED);
    }

    public static TaskScoreV1 discardedTerminal() {
        return new TaskScoreV1(TERMINAL_DISCARDED);
    }

    public boolean isSchedulableBand() {
        return score >= TIME_SCORE_FLOOR;
    }

    public boolean isPositiveNonSchedulableBand() {
        return score >= 0L && score < TIME_SCORE_FLOOR;
    }

    public boolean isMaintenanceBand() {
        return score == MAINT_ACTIVE;
    }

    public boolean isTerminalBand() {
        return score < 0L;
    }
}
