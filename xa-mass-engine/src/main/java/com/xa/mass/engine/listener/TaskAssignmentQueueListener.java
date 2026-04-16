package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Task;

/**
 * Listener for assignment-queue progress inside {@link TaskAssignWorker}.
 *
 * <p>These callbacks describe local queue processing only. They do not mean the
 * task business lifecycle is complete.
 */
public interface TaskAssignmentQueueListener {
    void onTaskAssignmentProcessed(Task task);

    void onAssignmentQueueDrained();
}
