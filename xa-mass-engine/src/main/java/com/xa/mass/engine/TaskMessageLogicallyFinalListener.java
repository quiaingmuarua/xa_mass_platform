package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Listener for a logical task message reaching a stable final state.
 */
@FunctionalInterface
public interface TaskMessageLogicallyFinalListener {

    void onTaskMessageLogicallyFinal(Task task, TaskMessageLogicallyFinalEvent event);
}
