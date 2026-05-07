package com.xa.mass.sdk;

import com.xa.mass.engine.TaskMessageView;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK-owned bounded read model for one logical task message view.
 *
 * <p>This is intentionally separate from engine compatibility residue so SDK
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

    public static SdkTaskMessageView from(TaskMessageView taskMsg) {
        if (taskMsg == null) {
            return null;
        }
        return new SdkTaskMessageView(
                taskMsg.messageId(),
                taskMsg.taskId(),
                taskMsg.status(),
                taskMsg.latestAttemptId(),
                taskMsg.latestAttemptWorkerId(),
                taskMsg.latestAttemptWorkerContextId(),
                taskMsg.latestAttemptBatchId(),
                taskMsg.retryCount(),
                taskMsg.maxRetryCount(),
                taskMsg.errorMessage(),
                taskMsg.errorCode(),
                taskMsg.finalReason(),
                taskMsg.payloadRef(),
                taskMsg.input(),
                taskMsg.output(),
                taskMsg.assignedTime(),
                taskMsg.createTime(),
                taskMsg.updateTime(),
                taskMsg.startTime(),
                taskMsg.completeTime()
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
