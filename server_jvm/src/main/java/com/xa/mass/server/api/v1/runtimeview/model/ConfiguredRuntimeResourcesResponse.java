package com.xa.mass.server.api.v1.runtimeview.model;

import java.util.List;

public record ConfiguredRuntimeResourcesResponse(
        List<ConfiguredRuntimeResourceEntry> entries
) {
}
