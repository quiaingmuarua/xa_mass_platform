package com.xa.mass.task.runtime;

public record ClaimLeasePolicy(int maxItems, long leaseMillis, long attemptPolicyVersion, RuntimeEpoch expectedRuntimeEpoch) {

    public ClaimLeasePolicy {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        if (leaseMillis <= 0) {
            throw new IllegalArgumentException("leaseMillis must be positive");
        }
        attemptPolicyVersion = Math.max(0L, attemptPolicyVersion);
        expectedRuntimeEpoch = expectedRuntimeEpoch == null ? RuntimeEpoch.unspecified() : expectedRuntimeEpoch;
    }
}
