package com.xa.mass.trace.operator;

public record TraceStatsRequest(
        String path,
        String taskId,
        String eventType,
        String severity,
        Integer limit
) {
}
