package com.xa.mass.task.runtime;

public record TaskScoreV1(long score) {

    public static final long TIME_SCORE_FLOOR = 1_000_000_000_000L;
    public static final long SCHEDULER_HOLD_FLOOR = 32_503_680_000_000L;
    public static final long NON_SCHEDULABLE_CREATED = 0L;
    public static final long TERMINAL_CLOSED = -1L;

    public static TaskScoreV1 dueAt(long dueAtMillis) {
        return new TaskScoreV1(timeScore(dueAtMillis));
    }

    public static TaskScoreV1 futureAt(long dueAtMillis) {
        return dueAt(dueAtMillis);
    }

    public static TaskScoreV1 schedulerHold() {
        return new TaskScoreV1(SCHEDULER_HOLD_FLOOR);
    }

    public static TaskScoreV1 createdNonSchedulable() {
        return nonSchedulable(NON_SCHEDULABLE_CREATED);
    }

    public static TaskScoreV1 nonSchedulable(long code) {
        if (code < 0L || code >= TIME_SCORE_FLOOR) {
            throw new IllegalArgumentException("non-schedulable score must be in [0, TIME_SCORE_FLOOR)");
        }
        return new TaskScoreV1(code);
    }

    public static TaskScoreV1 terminalClosed() {
        return terminal(TERMINAL_CLOSED);
    }

    public static TaskScoreV1 terminal(long code) {
        if (code >= 0L) {
            throw new IllegalArgumentException("terminal score must be negative");
        }
        return new TaskScoreV1(code);
    }

    public boolean isSchedulableBand() {
        return score >= TIME_SCORE_FLOOR;
    }

    public boolean isSchedulableTimeBand() {
        return score >= TIME_SCORE_FLOOR;
    }

    public boolean isDueAt(long nowMillis) {
        return score >= TIME_SCORE_FLOOR && score < SCHEDULER_HOLD_FLOOR && score <= nowMillis;
    }

    public boolean isFutureAt(long nowMillis) {
        return score >= TIME_SCORE_FLOOR && score < SCHEDULER_HOLD_FLOOR && score > nowMillis;
    }

    public boolean isSchedulerHold() {
        return score >= SCHEDULER_HOLD_FLOOR;
    }

    public boolean isNonSchedulableBand() {
        return score >= 0L && score < TIME_SCORE_FLOOR;
    }

    public boolean isTerminalBand() {
        return score < 0L;
    }

    private static long timeScore(long dueAtMillis) {
        long lowerBounded = Math.max(TIME_SCORE_FLOOR, dueAtMillis);
        return Math.min(lowerBounded, SCHEDULER_HOLD_FLOOR - 1L);
    }
}
