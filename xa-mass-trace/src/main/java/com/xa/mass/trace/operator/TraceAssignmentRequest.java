package com.xa.mass.trace.operator;

public record TraceAssignmentRequest(
        String path,
        String taskId,
        Integer limit
) {
}
