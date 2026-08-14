package com.xa.mass.scenariorpc;

import java.util.List;
import java.util.Objects;

public record ScenarioRpcRunOutcome(
        ScenarioRpcRunStatus status,
        List<ScenarioRpcResult> results,
        int remainingCount,
        int loadRounds
) {
    public ScenarioRpcRunOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(results, "results");
        results = List.copyOf(results);
        if (remainingCount < 0 || loadRounds < 0) {
            throw new IllegalArgumentException(
                    "run counts must be non-negative"
            );
        }
    }
}
