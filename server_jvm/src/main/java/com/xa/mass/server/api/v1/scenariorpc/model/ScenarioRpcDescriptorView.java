package com.xa.mass.server.api.v1.scenariorpc.model;

public record ScenarioRpcDescriptorView(
        String scenarioId,
        String workerGroupId,
        String eventCode
) {
}
