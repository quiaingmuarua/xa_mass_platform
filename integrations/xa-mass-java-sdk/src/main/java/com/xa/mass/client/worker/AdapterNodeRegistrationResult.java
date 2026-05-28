package com.xa.mass.client.worker;

import java.util.Map;

public record AdapterNodeRegistrationResult(
        String adapterNodeId,
        String adapterType,
        String adapterVersion,
        String endpointId,
        boolean enabled,
        boolean online,
        Map<String, String> attributes
) {
}
