package com.xa.mass.scenariorpc;

public record ScenarioRpcDescriptor(
        String scenarioId,
        String workerGroupId,
        String eventCode
) {
}
