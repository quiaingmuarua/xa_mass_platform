package com.xa.mass.engine;

/**
 * Default exponential idle policy for polling fallback loops.
 */
public final class ExponentialPollingIdleBackoffPolicy implements PollingIdleBackoffPolicy {

    public static final ExponentialPollingIdleBackoffPolicy INSTANCE = new ExponentialPollingIdleBackoffPolicy();

    private static final int MAX_BACKOFF_EXPONENT = 10;

    private ExponentialPollingIdleBackoffPolicy() {
    }

    @Override
    public long nextBackoffMillis(Decision decision) {
        if (decision == null) {
            return 0L;
        }
        int exponent = Math.min(
                Math.max(decision.consecutiveIdleCount() - 1, 0),
                MAX_BACKOFF_EXPONENT
        );
        long multiplier = 1L << exponent;
        long baseBackoffMillis = Math.max(decision.baseBackoffMillis(), 0L);
        long maxBackoffMillis = Math.max(decision.maxBackoffMillis(), baseBackoffMillis);
        long backoff;
        if (baseBackoffMillis > Long.MAX_VALUE / multiplier) {
            backoff = maxBackoffMillis;
        } else {
            backoff = baseBackoffMillis * multiplier;
        }
        return Math.min(backoff, maxBackoffMillis);
    }
}
