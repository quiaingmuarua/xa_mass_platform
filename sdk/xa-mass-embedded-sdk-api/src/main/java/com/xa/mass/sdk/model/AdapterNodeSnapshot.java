package com.xa.mass.sdk.model;

import java.util.Map;

/**
 * SDK read snapshot for AdapterNode endpoint identity.
 */
public record AdapterNodeSnapshot(
        String adapterNodeId,
        String adapterType,
        String adapterVersion,
        String endpointId,
        boolean enabled,
        boolean online,
        String registeredAt,
        String lastSeenAt,
        Map<String, String> attributes
) {
}
