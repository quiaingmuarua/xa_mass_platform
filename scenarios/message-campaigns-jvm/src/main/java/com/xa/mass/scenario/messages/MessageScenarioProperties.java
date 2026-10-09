package com.xa.mass.scenario.messages;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit environment binding; no shared String bean or default Group. */
@ConfigurationProperties(prefix = "xa.mass.scenarios.messages", ignoreUnknownFields = false)
public record MessageScenarioProperties(String workerGroupId) {
    public MessageScenarioProperties {
        if (workerGroupId == null || workerGroupId.isBlank())
            throw new IllegalArgumentException("Messages worker-group-id must be explicitly configured");
    }
}
