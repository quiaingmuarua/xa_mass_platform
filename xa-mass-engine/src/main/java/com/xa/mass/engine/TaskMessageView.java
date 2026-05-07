package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsg;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-owned bounded read view for one logical task message.
 *
 * <p>This is a compatibility/trace-facing read model. The execution truth
 * remains in task aggregate plus {@code TaskWorkRuntime}.</p>
 */
public record TaskMessageView(
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

    public TaskMessageView {
        input = immutableCopy(input);
        output = immutableCopy(output);
    }

    static TaskMessageView from(TaskMsg taskMsg) {
        if (taskMsg == null) {
            return null;
        }
        return new TaskMessageView(
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
