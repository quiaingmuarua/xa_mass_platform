package com.xa.mass.scenariorpc;

public record ScenarioRpcPollingPolicy(
        long loadIntervalMillis,
        int maximumLoadRounds
) {
    public static final long MAX_WAIT_MILLIS = 300_000;

    public ScenarioRpcPollingPolicy {
        if (loadIntervalMillis < 1) {
            throw new IllegalArgumentException(
                    "loadIntervalMillis must be positive"
            );
        }
        if (maximumLoadRounds < 1) {
            throw new IllegalArgumentException(
                    "maximumLoadRounds must be positive"
            );
        }
        long waits;
        try {
            waits = Math.multiplyExact(
                    loadIntervalMillis,
                    maximumLoadRounds
            );
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    "Scenario RPC polling budget is too large",
                    error
            );
        }
        if (waits > MAX_WAIT_MILLIS) {
            throw new IllegalArgumentException(
                    "Scenario RPC polling budget exceeds 5 minutes"
            );
        }
    }
}
