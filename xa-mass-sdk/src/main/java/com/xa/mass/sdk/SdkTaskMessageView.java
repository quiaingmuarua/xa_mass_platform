package com.xa.mass.sdk;

import com.xa.mass.base.model.TaskMsg;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK-owned bounded read model for one logical task message view.
 *
 * <p>This is intentionally separate from engine/base {@code TaskMsg} so SDK
 * callers do not depend on compatibility projection internals as a public
 * platform model.</p>
 */
public record SdkTaskMessageView(
        String messageId,
        String taskId,
        String status,
        String latestAttemptId,
        String latestAttemptWorkerId,
        String latestAttemptWorkerContextId,
        String latestAttemptBatchId,
        int retryCount,
        int maxRetryCount,
        String errorMessage,
        String errorCode,
        String finalReason,
        String payloadRef,
        Map<String, Object> input,
        Map<String, Object> output,
        LocalDateTime assignedTime,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime startTime,
        LocalDateTime completeTime
) {

    public SdkTaskMessageView {
        input = immutableCopy(input);
        output = immutableCopy(output);
    }

    public static SdkTaskMessageView from(TaskMsg taskMsg) {
        if (taskMsg == null) {
            return null;
        }
        return new SdkTaskMessageView(
                taskMsg.getMessageId(),
                taskMsg.getTaskId(),
                taskMsg.getStatus() != null ? taskMsg.getStatus().name() : null,
                taskMsg.latestAttemptId(),
                taskMsg.getLatestAttemptWorkerId(),
                taskMsg.getLatestAttemptWorkerContextId(),
                taskMsg.getLatestAttemptBatchId(),
                taskMsg.getRetryCount(),
                taskMsg.getMaxRetryCount(),
                taskMsg.getErrorMessage(),
                taskMsg.getErrorCode(),
                taskMsg.getFinalReason() != null ? taskMsg.getFinalReason().name() : null,
                taskMsg.getPayloadRef(),
                taskMsg.getInput(),
                taskMsg.getOutput(),
                taskMsg.getAssignedTime(),
                taskMsg.getCreateTime(),
                taskMsg.getUpdateTime(),
                taskMsg.getStartTime(),
                taskMsg.getCompleteTime()
        );
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
