package com.xa.mass.server.api.v1.scenariorpc.model;

import java.time.Instant;

public record ScenarioRpcInstanceResponse(
        String scenarioId,
        String scenarioType,
        String status,
        Instant createdAt,
        String inputFile,
        int inputCount,
        int resultCount,
        int remainingCount,
        int loadRounds,
        long durationMillis,
        String outputFile
) {
}
