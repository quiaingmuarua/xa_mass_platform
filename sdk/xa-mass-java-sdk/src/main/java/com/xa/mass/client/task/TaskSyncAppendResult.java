package com.xa.mass.client.task;

import java.util.Map;

public record TaskSyncAppendResult(
        String taskId,
        String messageId,
        boolean synced,
        boolean timedOut,
        long timeoutMs,
        String status,
        String finalReason,
        Map<String, Object> output,
        String errorCode,
        String errorMessage
) {
}
