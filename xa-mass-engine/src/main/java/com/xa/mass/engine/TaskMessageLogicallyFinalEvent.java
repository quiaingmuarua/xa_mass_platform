package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;

import java.util.Map;

/**
 * Engine event payload for one logical task message reaching stable finality.
 */
public record TaskMessageLogicallyFinalEvent(
        String taskId,
        String messageId,
        TaskMsgStatus status,
        TaskMsgFinalReason finalReason,
        int retryCount,
        String errorCode,
        String errorMessage,
        String payloadRef,
        Map<String, Object> output
) {

    static TaskMessageLogicallyFinalEvent from(TaskMsg taskMsg) {
        if (taskMsg == null) {
            return null;
        }
        return new TaskMessageLogicallyFinalEvent(
                taskMsg.getTaskId(),
                taskMsg.getMessageId(),
                taskMsg.getStatus(),
                taskMsg.getFinalReason(),
                taskMsg.getRetryCount(),
                taskMsg.getErrorCode(),
                taskMsg.getErrorMessage(),
                taskMsg.getPayloadRef(),
                taskMsg.getOutput() == null ? null : Map.copyOf(taskMsg.getOutput())
        );
    }
}
