package com.xa.mass.sdk.model;

import java.util.List;
import java.util.Map;

public record WorkerCapabilityReportRequest(
        String workerId,
        long capabilityVersion,
        List<String> availableEventCodes,
        Map<String, String> schedulingAttributes,
        String agentVersion
) {
    public WorkerCapabilityReportRequest {
        availableEventCodes = availableEventCodes == null ? List.of() : List.copyOf(availableEventCodes);
        schedulingAttributes = schedulingAttributes == null ? Map.of() : Map.copyOf(schedulingAttributes);
    }
}
