package com.xa.mass.server.api.v1.scenariorpc.model;

public record ScenarioRpcRunRequest(
        String inputFile,
        Long loadIntervalMillis,
        Integer maximumLoadRounds
) {
}
