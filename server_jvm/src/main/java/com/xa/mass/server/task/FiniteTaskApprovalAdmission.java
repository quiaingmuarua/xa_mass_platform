package com.xa.mass.server.task;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.credit.TaskCreditConfirmation;
import org.jspecify.annotations.Nullable;

/** Startup-bound business admission; the common service alone invokes the lifecycle Owner. */
public interface FiniteTaskApprovalAdmission {
    String inputOwner();
    void beforeApprove(TaskCaller caller, TaskDescriptor task, long expectedItemCount, @Nullable TaskCreditConfirmation credits);
    void beforeClose(TaskCaller caller, TaskDescriptor task);
}
