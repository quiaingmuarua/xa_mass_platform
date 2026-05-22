package com.xa.mass.base.runtime.dispatch;

import java.util.List;

/**
 * Consumer-side listener for dispatch batches drained from a handoff seam.
 */
@FunctionalInterface
public interface TaskDispatchBatchListener {
    void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings);
}
