package com.xa.mass.sdk;

import com.xa.mass.engine.TaskMessageAttemptView;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK-owned bounded read model for one concrete task-message attempt view.
 */
public record SdkTaskMessageAttemptView(
        String attemptId,
        String taskId,
        String messageId,
        int attemptNo,
        String workerId,
        String workerContextId,
        String batchId,
        String status,
        LocalDateTime leaseExpireTime,
        LocalDateTime dispatchTime,
        LocalDateTime ackTime,
        LocalDateTime startTime,
        LocalDateTime finishTime,
        String finalReason,
        String errorMessage,
        String errorCode,
        Map<String, Object> output,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public SdkTaskMessageAttemptView {
        output = immutableCopy(output);
    }

    public static SdkTaskMessageAttemptView from(TaskMessageAttemptView attempt) {
        if (attempt == null) {
            return null;
        }
        return new SdkTaskMessageAttemptView(
                attempt.attemptId(),
                attempt.taskId(),
                attempt.messageId(),
                attempt.attemptNo(),
                attempt.workerId(),
                attempt.workerContextId(),
                attempt.batchId(),
                attempt.status(),
                attempt.leaseExpireTime(),
                attempt.dispatchTime(),
                attempt.ackTime(),
                attempt.startTime(),
                attempt.finishTime(),
                attempt.finalReason(),
                attempt.errorMessage(),
                attempt.errorCode(),
                attempt.output(),
                attempt.createTime(),
                attempt.updateTime()
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
