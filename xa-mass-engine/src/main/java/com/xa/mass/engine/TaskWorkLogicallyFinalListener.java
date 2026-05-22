package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Listener for a logical work item reaching a stable final state.
 */
@FunctionalInterface
public interface TaskWorkLogicallyFinalListener {

    void onTaskWorkLogicallyFinal(Task task, TaskWorkLogicallyFinalEvent event);
}
