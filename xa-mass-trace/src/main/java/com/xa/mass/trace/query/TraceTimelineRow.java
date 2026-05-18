package com.xa.mass.trace.query;

public record TraceTimelineRow(
        long ts,
        String tsIso,
        String eventId,
        String eventType,
        String severity,
        String traceId,
        String spanId,
        String parentSpanId,
        String taskId,
        String messageId,
        String attemptId,
        String workerId,
        String commandId,
        String src,
        String dst,
        String transitionReason,
        Boolean outcomeSuccess,
        String outcomeErrorCode,
        String outcomeDetail,
        String trigger,
        String source,
        String reason,
        String terminalReason
) {
}
