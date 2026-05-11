package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Listener for one concrete execution attempt reaching a final state.
 */
@FunctionalInterface
public interface TaskWorkAttemptClosedListener {

    void onTaskWorkAttemptClosed(Task task, TaskWorkAttemptClosedEvent event);
}
