package com.xa.mass.trace.operator;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;

public record TraceQueryResponse(
        String source,
        String taskId,
        String messageId,
        String workerId,
        String commandId,
        String traceId,
        String eventType,
        int count,
        List<TraceTimelineRow> events
) {
}
