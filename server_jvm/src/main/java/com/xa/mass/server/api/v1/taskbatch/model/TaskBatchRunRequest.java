package com.xa.mass.server.api.v1.taskbatch.model;

public record TaskBatchRunRequest(
        String workerGroupId,
        String eventCode,
        String payloadKey,
        String inputFile,
        Long maximumWaitMillis
) {
}
