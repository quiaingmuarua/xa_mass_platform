package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsgAttempt;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-owned bounded audit/trace read view for one concrete task-message attempt.
 */
public record TaskMessageAttemptView(
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

    public TaskMessageAttemptView {
        output = immutableCopy(output);
    }

    static TaskMessageAttemptView from(TaskMsgAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        return new TaskMessageAttemptView(
                attempt.getAttemptId(),
                attempt.getTaskId(),
                attempt.getMessageId(),
                attempt.getAttemptNo(),
                attempt.getWorkerId(),
                attempt.getWorkerContextId(),
                attempt.getBatchId(),
                attempt.getStatus() != null ? attempt.getStatus().name() : null,
                attempt.getLeaseExpireTime(),
                attempt.getDispatchTime(),
                attempt.getAckTime(),
                attempt.getStartTime(),
                attempt.getFinishTime(),
                attempt.getFinalReason() != null ? attempt.getFinalReason().name() : null,
                attempt.getErrorMessage(),
                attempt.getErrorCode(),
                attempt.getOutput(),
                attempt.getCreateTime(),
                attempt.getUpdateTime()
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
