package com.xa.mass.client.worker;

import java.util.Map;

public record NodeGroupBindingResult(
        String adapterNodeId,
        String workerGroupId,
        String pluginVersion,
        String deploymentVersion,
        boolean enabled,
        boolean draining,
        Map<String, String> attributes
) {
}
