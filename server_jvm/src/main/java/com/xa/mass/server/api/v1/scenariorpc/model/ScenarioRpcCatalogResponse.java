package com.xa.mass.server.api.v1.scenariorpc.model;

import java.util.List;

public record ScenarioRpcCatalogResponse(
        List<ScenarioRpcDescriptorView> scenarios
) {
}
