package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

/**
 * Listener for one concrete execution attempt reaching a final state.
 */
@FunctionalInterface
public interface TaskMessageAttemptClosedListener {

    void onTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt);
}
