package com.xa.mass.server.api.v1.scenariorpc.model;

import java.util.List;
import java.util.Objects;

public record ScenarioRpcTypeCatalogResponse(
        List<ScenarioRpcTypeView> scenarioTypes
) {
    public ScenarioRpcTypeCatalogResponse {
        Objects.requireNonNull(scenarioTypes, "scenarioTypes");
        scenarioTypes = List.copyOf(scenarioTypes);
    }
}
