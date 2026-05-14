package com.xa.mass.trace.operator;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;

public record TraceTimelineResponse(
        String source,
        String taskId,
        String messageId,
        int count,
        List<TraceTimelineRow> events
) {
}
