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

    static long replaceRank(long score, int targetRank) {
        long absolute = Math.abs(score);
        return Long.signum(score) * (absolute - absolute % SLOT_FACTOR
                + (long) targetRank * DIRTY_FACTOR + absolute % DIRTY_FACTOR);
    }

    static long replaceDirty(long score, int targetDirty) {
        long absolute = Math.abs(score);
        return Long.signum(score) * (absolute - absolute % DIRTY_FACTOR + targetDirty);
    }

    static long replacePolarity(long score, WorkerScorePolarity targetPolarity) {
        return targetPolarity.value() * Math.abs(score);
    }

    static long absoluteScore(
            long timeSlot,
            int laneRank,
            int dirty
    ) {
        return timeSlot * SLOT_FACTOR
                + (long) laneRank * DIRTY_FACTOR
                + dirty;
    }

    static boolean validTimeMillis(long timeMillis) {
        return timeMillis >= MIN_TIME_MILLIS
                && timeMillis <= MAX_TIME_MILLIS;
    }

    static boolean validLaneRank(int laneRank) {
        return laneRank >= MIN_LANE_RANK
                && laneRank <= MAX_LANE_RANK;
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
        long slotRemainder = absolute % SLOT_FACTOR;
        int laneRank = Math.toIntExact(
                slotRemainder / DIRTY_FACTOR
        );
        int dirty = Math.toIntExact(slotRemainder % DIRTY_FACTOR);
        if (timeSlot < MIN_TIME_SLOT
                || timeSlot > MAX_TIME_SLOT
                || laneRank < MIN_LANE_RANK
                || laneRank > MAX_LANE_RANK
                || dirty < MIN_DIRTY
                || dirty > MAX_DIRTY) {
            throw new IllegalStateException("Worker score is invalid");
        }
        return new WorkerScoreState(
                workerId,
                score,
                score > 0
                        ? WorkerScorePolarity.HOT_ACQUIRE
                        : WorkerScorePolarity.RECOVERY_RECHECK,
                timeSlot * SLOT_MILLIS,
                laneRank,
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
