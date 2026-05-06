package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkResult;

import java.util.Optional;

/**
 * Narrow runtime/result surface consumed by result handling and retry logic.
 */
public interface TaskResultRuntimePort extends TaskLeaseProjectionPort {

    Task getTask(String taskId);

    TaskMsg getTaskMessage(String taskId, String messageId);

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId);

    ResultApplyOutcome applyTaskWorkResult(TaskWorkResult result);

    long getTaskMessageLeaseSeconds();

    void requestTaskRetryDispatch(Task task, long delayMillis);

    void publishTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt);

    void publishTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg);
}

