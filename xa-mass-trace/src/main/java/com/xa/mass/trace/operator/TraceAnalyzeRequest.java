package com.xa.mass.trace.operator;

public record TraceAnalyzeRequest(
        String path,
        String scenarioId,
        String taskId,
        Long droppedCount
) {
    public TraceAnalyzeRequest(String path, String scenarioId, String taskId) {
        this(path, scenarioId, taskId, null);
    }
}
