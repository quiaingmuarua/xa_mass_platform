package com.xa.mass.scenariorpc;

public record ScenarioRpcDescriptor(
        String scenarioType,
        String workerGroupId,
        String eventCode
) {
}
