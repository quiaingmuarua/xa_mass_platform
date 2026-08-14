package com.xa.mass.server.api.v1.scenariorpc.model;

import java.time.Instant;

public record ScenarioRpcRunResponse(
        String scenarioId,
        String workerGroupId,
        String eventCode,
        String inputFile,
        String outputFile,
        int inputCount,
        int resultCount,
        long durationMillis,
        Instant generatedAt
) {
}
