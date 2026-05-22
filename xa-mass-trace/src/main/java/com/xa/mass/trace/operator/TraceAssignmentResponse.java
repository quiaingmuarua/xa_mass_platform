package com.xa.mass.trace.operator;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;

public record TraceAssignmentResponse(
        String source,
        String taskId,
        int count,
        List<TraceAssignmentRow> events
) {
}
