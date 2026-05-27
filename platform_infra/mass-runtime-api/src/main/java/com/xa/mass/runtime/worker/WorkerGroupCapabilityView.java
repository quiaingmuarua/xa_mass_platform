package com.xa.mass.runtime.worker;

import java.util.List;
import java.util.Map;

/**
 * Runtime-neutral read view of WorkerGroup scheduling capability.
 */
public record WorkerGroupCapabilityView(
        String groupId,
        List<String> projectCodes,
        List<String> eventCodes,
        Map<String, String> defaultAttributes,
        int defaultMaxConcurrentWork) {

    public WorkerGroupCapabilityView {
        projectCodes = projectCodes == null ? List.of() : List.copyOf(projectCodes);
        eventCodes = eventCodes == null ? List.of() : List.copyOf(eventCodes);
        defaultAttributes = defaultAttributes == null ? Map.of() : Map.copyOf(defaultAttributes);
        defaultMaxConcurrentWork = Math.max(1, defaultMaxConcurrentWork);
    }
}
