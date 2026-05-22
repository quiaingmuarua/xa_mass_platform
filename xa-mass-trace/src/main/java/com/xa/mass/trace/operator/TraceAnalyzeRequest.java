package com.xa.mass.trace.operator;

public record TraceAnalyzeRequest(
        String path,
        String scenarioId,
        String taskId
) {
}
