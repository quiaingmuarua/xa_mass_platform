package com.xa.mass.engine.watchdog;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local admission owner for polling resources that repeatedly make no progress.
 */
public final class PollingIdleAdmissionTracker {

    private static final long DEFAULT_RETENTION_MILLIS = 60_000L;

    private final long intervalMillis;
    private final long baseIdleBackoffMillis;
    private final long maxIdleBackoffMillis;
    private final PollingIdleBackoffPolicy idleBackoffPolicy;
    private final ConcurrentHashMap<PollingResourceKey, IdleState> idleAdmissions = new ConcurrentHashMap<>();

    public PollingIdleAdmissionTracker(long intervalMillis,
                                       long baseIdleBackoffMillis,
                                       long maxIdleBackoffMillis,
                                       PollingIdleBackoffPolicy idleBackoffPolicy) {
        this.intervalMillis = Math.max(intervalMillis, 1L);
        this.baseIdleBackoffMillis = Math.max(baseIdleBackoffMillis, this.intervalMillis);
        this.maxIdleBackoffMillis = Math.max(maxIdleBackoffMillis, this.baseIdleBackoffMillis);
        this.idleBackoffPolicy = idleBackoffPolicy != null
                ? idleBackoffPolicy
                : ExponentialPollingIdleBackoffPolicy.INSTANCE;
    }

    public boolean admit(PollingResourceKey resourceKey, long nowMillis) {
        Objects.requireNonNull(resourceKey, "resourceKey");
        IdleState state = idleAdmissions.get(resourceKey);
        return state == null || state.nextAttemptAfterMillis() <= nowMillis;
    }

    public void recordIdle(PollingResourceKey resourceKey, long nowMillis) {
        Objects.requireNonNull(resourceKey, "resourceKey");
        idleAdmissions.compute(resourceKey, (key, previous) -> {
            int nextCount = 1;
            if (previous != null) {
                nextCount = previous.consecutiveIdleCount() == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : previous.consecutiveIdleCount() + 1;
            }
            long nextAttemptAfterMillis = nowMillis + calculatePolicyBackoffMillis(key, nextCount);
            return new IdleState(nextCount, nextAttemptAfterMillis, nowMillis);
        });
    }

    public void recordProgress(PollingResourceKey resourceKey) {
        if (resourceKey != null) {
            idleAdmissions.remove(resourceKey);
        }
    }

    public void wakeAll() {
        idleAdmissions.clear();
    }

    public void pruneStale(long nowMillis) {
        long retentionMillis = Math.max(saturatingMultiply(maxIdleBackoffMillis, 4L), DEFAULT_RETENTION_MILLIS);
        idleAdmissions.entrySet().removeIf(entry ->
                nowMillis - entry.getValue().lastUpdatedAtMillis() > retentionMillis);
    }

    int idleAdmissionCount() {
        return idleAdmissions.size();
    }

    private long calculatePolicyBackoffMillis(PollingResourceKey resourceKey, int consecutiveIdleCount) {
        long policyBackoffMillis = idleBackoffPolicy.nextBackoffMillis(
                new PollingIdleBackoffPolicy.Decision(
                        resourceKey,
                        consecutiveIdleCount,
                        baseIdleBackoffMillis,
                        maxIdleBackoffMillis
                )
        );
        return Math.max(policyBackoffMillis, intervalMillis);
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0L || multiplier <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private record IdleState(int consecutiveIdleCount,
                             long nextAttemptAfterMillis,
                             long lastUpdatedAtMillis) {
    }
}
