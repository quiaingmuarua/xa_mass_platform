package com.xa.mass.server.api.v1.taskbatch.model;

public record TaskBatchRunResponse(
        String runId,
        String workerGroupId,
        String eventCode,
        String payloadKey,
        String status,
        String inputFile,
        int inputCount,
        int resultCount,
        int remainingCount,
        int loadRounds,
        long durationMillis,
        String outputFile
) {
}
