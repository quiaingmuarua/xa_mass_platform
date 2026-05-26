package com.xa.mass.sdk.model;

import java.util.Map;

/**
 * SDK read snapshot for the relation that an AdapterNode hosts a WorkerGroup.
 */
public record NodeGroupBindingSnapshot(
        String adapterNodeId,
        String workerGroupId,
        String pluginVersion,
        String deploymentVersion,
        boolean enabled,
        boolean draining,
        String registeredAt,
        String updatedAt,
        Map<String, String> attributes
) {
}
