package com.xa.mass.base.runtime.dispatch;

import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.Objects;

/**
 * Immutable dispatch-ready binding between a logical task message and the
 * concrete attempt selected for delivery.
 */
public record TaskDispatchBinding(TaskMsg taskMsg, TaskMsgAttempt attempt) {

    public TaskDispatchBinding {
        Objects.requireNonNull(taskMsg, "taskMsg");
        Objects.requireNonNull(attempt, "attempt");
        if (taskMsg.getTaskId() == null || !taskMsg.getTaskId().equals(attempt.getTaskId())) {
            throw new IllegalArgumentException("taskMsg and attempt must belong to the same taskId");
        }
        if (taskMsg.getMessageId() == null || !taskMsg.getMessageId().equals(attempt.getMessageId())) {
            throw new IllegalArgumentException("taskMsg and attempt must belong to the same messageId");
        }
    }
}
