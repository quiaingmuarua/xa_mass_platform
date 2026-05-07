package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Listener for one concrete execution attempt reaching a final state.
 */
@FunctionalInterface
public interface TaskMessageAttemptClosedListener {

    void onTaskMessageAttemptClosed(Task task, TaskMessageAttemptClosedEvent event);
}
