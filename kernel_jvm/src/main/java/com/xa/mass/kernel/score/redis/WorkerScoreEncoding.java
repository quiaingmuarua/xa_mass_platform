package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.*;

/** Compact score arithmetic. Callers retain their operation-specific validation. */
final class WorkerScoreEncoding {
    static final long COLD_PARK_TIME_SLOT = MIN_TIME_SLOT + 1;

    private WorkerScoreEncoding() {
    }

    static long replaceTime(long score, long targetSlot) {
        return Long.signum(score) * (targetSlot * SLOT_FACTOR + Math.abs(score) % SLOT_FACTOR);
    }

    static long absoluteScore(
            long timeSlot,
            int dirty
    ) {
        return timeSlot * SLOT_FACTOR + dirty;
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
        long timeSlot = absolute / SLOT_FACTOR;
        int dirty = Math.toIntExact(absolute % SLOT_FACTOR);
        if (timeSlot > MAX_TIME_SLOT) {
            throw new IllegalStateException("Worker score is invalid");
        }
        return new WorkerScoreState(
                workerId,
                score,
                score > 0
                        ? WorkerScorePolarity.HOT_ACQUIRE
                        : WorkerScorePolarity.RECOVERY_RECHECK,
                timeSlot * SLOT_MILLIS,
                dirty
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
