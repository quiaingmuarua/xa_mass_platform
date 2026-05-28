package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Runtime dispatch readiness and wakeup surface.
 */
public interface TaskDispatchWakeupPort {

    boolean hasDispatchReadyWork(String taskId);

    void requestTaskDispatch(Task task);
}
