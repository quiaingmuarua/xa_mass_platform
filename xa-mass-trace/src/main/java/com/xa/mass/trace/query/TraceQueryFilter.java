package com.xa.mass.trace.query;

public record TraceQueryFilter(
        String taskId,
        String messageId,
        String workerId,
        String commandId,
        String traceId,
        String eventType
) {

    public boolean hasAnyFilter() {
        return hasText(taskId)
                || hasText(messageId)
                || hasText(workerId)
                || hasText(commandId)
                || hasText(traceId)
                || hasText(eventType);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
