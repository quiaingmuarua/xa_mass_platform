package com.xa.mass.engine.watchdog;

/**
 * Policy seam for delaying polling resources that repeatedly make no progress.
 *
 * <p>The admission tracker owns runtime state. This policy owns only the delay
 * calculation for a consecutive idle count.</p>
 */
@FunctionalInterface
public interface PollingIdleBackoffPolicy {

    long nextBackoffMillis(Decision decision);

    record Decision(PollingResourceKey resourceKey,
                    int consecutiveIdleCount,
                    long baseBackoffMillis,
                    long maxBackoffMillis) {
    }
}
