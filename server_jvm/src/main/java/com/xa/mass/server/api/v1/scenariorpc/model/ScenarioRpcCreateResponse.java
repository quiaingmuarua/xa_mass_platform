package com.xa.mass.server.api.v1.scenariorpc.model;

public record ScenarioRpcCreateResponse(
        String scenarioId,
        String scenarioType,
        String status
) {
}
