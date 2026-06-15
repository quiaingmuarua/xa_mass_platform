package com.xa.mass.client.worker;

import java.util.Map;

public record WorkerDispatchItem(
        String taskId,
        String messageId,
        String eventCode,
        String taskName,
        String project,
        String userId,
        int retryCount,
        String workerId,
        String batchId,
        Map<String, Object> input,
        Map<String, Object> sharedConfig
) {
}
