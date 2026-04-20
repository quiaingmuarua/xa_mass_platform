package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

/**
 * Listener for a logical task message reaching a stable final state.
 */
@FunctionalInterface
public interface TaskMessageLogicallyFinalListener {

    void onTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg);
}
