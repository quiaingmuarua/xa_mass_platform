package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;

import java.util.List;

/**
 * Narrow assignment/runtime seam used by listeners, starter wiring, and tests.
 *
 * <p>This interface is a selective assignment hot-path surface, not a license
 * to add a second engine-internal adapter track when the owning engine object
 * already implements it directly.</p>
 */
public interface TaskAssignmentRuntimePort {

    int countPendingDispatchableMessages(String taskId);

    long getTaskMessageLeaseSeconds();

    void addTaskMessageAttemptAuditProjection(String taskId, String messageId, TaskMsgAttempt attempt);

    boolean updateTask(Task task);

    List<ClaimedTaskWork> claimReady(String taskId,
                                     List<WorkerClaimTarget> claimTargets,
                                     TaskWorkClaimOptions claimOptions);

    boolean compensateDispatchSubmitFailure(Task task,
                                            List<TaskDispatchBinding> dispatchBindings,
                                            String detail);
}

