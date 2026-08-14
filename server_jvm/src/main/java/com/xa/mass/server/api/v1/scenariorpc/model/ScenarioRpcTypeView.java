package com.xa.mass.server.api.v1.scenariorpc.model;

public record ScenarioRpcTypeView(
        String scenarioType,
        String workerGroupId,
        String eventCode
) {
}
