package com.xa.mass.trace.operator;

public record TraceQueryRequest(
        String path,
        String taskId,
        String messageId,
        String workerId,
        String commandId,
        String traceId,
        String eventType,
        Integer limit
) {
}
