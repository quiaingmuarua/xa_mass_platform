package com.xa.mass.trace.operator;

public record TraceTimelineRequest(
        String path,
        String taskId,
        String messageId,
        Integer limit
) {
}
