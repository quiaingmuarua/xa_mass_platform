package com.xa.mass.trace.operator;

import com.xa.mass.trace.query.TraceStatsRow;

import java.util.List;

public record TraceStatsResponse(
        String source,
        String taskId,
        String eventType,
        String severity,
        int count,
        List<TraceStatsRow> rows
) {
}
