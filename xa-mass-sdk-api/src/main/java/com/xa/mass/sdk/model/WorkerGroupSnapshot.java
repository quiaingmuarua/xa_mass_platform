package com.xa.mass.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * SDK read snapshot for WorkerGroup capability truth.
 */
public record WorkerGroupSnapshot(
        String groupId,
        List<WorkerEventBinding> eventBindings,
        List<String> projectCodes,
        Map<String, String> defaultAttributes,
        int defaultMaxConcurrentWork
) {
}
