package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchDeliveryFailure;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.ClaimReadyOutcome;

import java.util.List;

/**
 * Narrow assignment/runtime seam used by listeners, starter wiring, and tests.
 *
 * <p>This interface is a selective assignment hot-path surface, not a license
 * to add a second engine-internal adapter track when the owning engine object
 * already implements it directly.</p>
 */
public interface TaskAssignmentRuntimePort {

    int countDispatchReadyWork(String taskId);

    int countActiveDispatchWorkers(String taskId);

    boolean updateTask(Task task);

    ClaimReadyOutcome claimReady(ClaimReadyCommand command);

    boolean compensateDispatchSubmitFailure(Task task,
                                            List<TaskDispatchBinding> dispatchBindings,
                                            String detail);

    boolean compensateDispatchDeliveryFailure(Task task,
                                              List<TaskDispatchDeliveryFailure> failures);
}
