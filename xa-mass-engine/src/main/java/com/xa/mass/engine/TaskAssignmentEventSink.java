package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

/**
 * Narrow in-process event sink for assignment completion.
 */
public interface TaskAssignmentEventSink {

    void publishTaskAssigned(Task task);
}
