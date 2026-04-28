package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Narrow engine-internal runtime surface for task-level redispatch signaling.
 */
interface TaskDispatchRequestRuntimePort {

    Task getTask(String taskId);

    boolean hasPendingDispatchableMessages(String taskId);

    void publishTaskDispatchRequested(Task task);
}
