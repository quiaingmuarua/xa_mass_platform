package com.xa.mass.runtime.worker.slot;

/**
 * Score-band constants and predicates for worker resource slot scheduling.
 */
public final class WorkerScoreBand {

    /**
     * Fixed epoch for mapping low-recheck due timestamps into a reserved score
     * range. 2026-01-01T00:00:00Z.
     */
    public static final long LOW_RECHECK_EPOCH_MILLIS = 1_767_225_600_000L;

    /**
     * Scores below this value are parked or low-recheck. Scores at or above it
     * are epoch-millis time scores.
     */
    public static final long TIME_SCORE_FLOOR = 1_000_000_000_000L;

    public static final long PARKED_DISABLED = -1L;
    public static final long PARKED_DRAIN = -2L;
    public static final long PARKED_COLD = -3L;

    private WorkerScoreBand() {
    }

    public static WorkerScoreBandKind classify(long score, long nowMillis) {
        if (score < 0L) {
            return WorkerScoreBandKind.PARKED;
        }
        if (score < TIME_SCORE_FLOOR) {
            return WorkerScoreBandKind.LOW_RECHECK;
        }
        return score <= nowMillis ? WorkerScoreBandKind.TIME_DUE : WorkerScoreBandKind.FUTURE;
    }

    public static boolean isParked(long score) {
        return score < 0L;
    }

    public static boolean isLowRecheck(long score) {
        return score >= 0L && score < TIME_SCORE_FLOOR;
    }

    public static boolean isTimeScore(long score) {
        return score >= TIME_SCORE_FLOOR;
    }

    public static boolean isAcquireVisible(long score, long nowMillis) {
        return isTimeScore(score) && score <= nowMillis;
    }

    public static long eligibleScore(long nowMillis) {
        return Math.max(TIME_SCORE_FLOOR, nowMillis);
    }

    public static long futureScore(long untilEpochMillis) {
        if (untilEpochMillis < TIME_SCORE_FLOOR) {
            throw new IllegalArgumentException("future score must be an epoch-millis time score");
        }
        return untilEpochMillis;
    }

    public static long lowRecheckScore(long nextRecheckAtMillis) {
        long score = nextRecheckAtMillis - LOW_RECHECK_EPOCH_MILLIS;
        if (score < 0L || score >= TIME_SCORE_FLOOR) {
            throw new IllegalArgumentException("low-recheck due score is outside the reserved range");
        }
        return score;
    }

    public static long lowRecheckDueAtMillis(long lowRecheckScore) {
        if (!isLowRecheck(lowRecheckScore)) {
            throw new IllegalArgumentException("score is not in LOW_RECHECK_BAND");
        }
        return LOW_RECHECK_EPOCH_MILLIS + lowRecheckScore;
    }
}
