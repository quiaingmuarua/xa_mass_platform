package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.*;

/** Candidate lane and generation arithmetic, private to the Score Owner. */
final class WorkerScoreEncoding {
    static final long ZERO_SCORE = 0;
    static final long MIN_BASE = 1;
    static final long MIN_TIME_SLOT = 0;
    static final long SLOT_MILLIS = 100;
    static final long MAX_TIME_SLOT = 99_999_999_999L;
    static final long PAUSE_TIME_SLOT = MAX_TIME_SLOT;
    static final long MIN_TIME_MILLIS = 0;
    static final long MAX_TIME_MILLIS = MAX_TIME_SLOT * SLOT_MILLIS;
    static final long PAUSE_TIME_MILLIS = MAX_TIME_MILLIS;
    static final int ORDINARY_MARK = 0;
    static final int CANDIDATE_MARK = 1;
    static final long MARK_BASE = MAX_TIME_SLOT + 1;
    static final long COLD_PARK_TIME_SLOT = MIN_TIME_SLOT + 1;

    private WorkerScoreEncoding() {
    }

    record WorkerScoreState(String workerId, long score, WorkerScorePolarity polarity,
                            long timeMillis, int mark) {}

    static int polarityValue(WorkerScorePolarity polarity) {
        return switch (polarity) {
            case HOT_ACQUIRE -> 1;
            case RECOVERY_RECHECK -> -1;
        };
    }

    static SchedulingState schedulingState(WorkerScoreState state, long readAtMillis) {
        if (state == null) return SchedulingState.MISSING;
        if (state.timeMillis() == PAUSE_TIME_MILLIS) return SchedulingState.PAUSED;
        if (state.polarity() == WorkerScorePolarity.RECOVERY_RECHECK) {
            return state.timeMillis() <= COLD_PARK_TIME_SLOT * SLOT_MILLIS
                    ? SchedulingState.COLD : SchedulingState.RECOVERY;
        }
        long currentSlotMillis = readAtMillis / SLOT_MILLIS * SLOT_MILLIS;
        return state.timeMillis() >= currentSlotMillis
                ? SchedulingState.HELD_HOT : SchedulingState.HOT_SCORE_OVERDUE;
    }

    static long replaceTime(long score, long targetSlot) {
        return Long.signum(score) * (Math.abs(score) / MARK_BASE * MARK_BASE + targetSlot);
    }

    static long absoluteScore(
            long timeSlot,
            int mark
    ) {
        return mark * MARK_BASE + timeSlot;
    }

    static boolean validTimeMillis(long timeMillis) {
        return timeMillis >= MIN_TIME_MILLIS
                && timeMillis <= MAX_TIME_MILLIS;
    }

    static WorkerScoreState decodeState(
            String workerId,
            double rawScore
    ) {
        long score = scoreToLong(rawScore);
        if (score == ZERO_SCORE || score == Long.MIN_VALUE) {
            throw new IllegalStateException("Worker score is invalid");
        }
        long absolute = Math.abs(score);
        long timeSlot = absolute % MARK_BASE;
        long mark = absolute / MARK_BASE;
        if (mark > CANDIDATE_MARK) {
            throw new IllegalStateException("Worker score is invalid");
        }
        return new WorkerScoreState(
                workerId,
                score,
                score > 0
                        ? WorkerScorePolarity.HOT_ACQUIRE
                        : WorkerScorePolarity.RECOVERY_RECHECK,
                timeSlot * SLOT_MILLIS,
                (int) mark
        );
    }

    static long scoreToLong(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            long converted = number.longValue();
            if (!Double.isFinite(value)
                    || value != (double) converted) {
                throw new IllegalStateException(
                        "Worker score must be an integer"
                );
            }
            return converted;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "Worker score must be an integer",
                    error
            );
        }
    }

}
