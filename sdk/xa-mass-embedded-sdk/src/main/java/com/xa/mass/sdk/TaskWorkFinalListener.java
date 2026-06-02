package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskWorkFinalNotification;

/**
 * Listener for a task work item reaching a stable final state.
 */
@FunctionalInterface
public interface TaskWorkFinalListener {

    void onTaskWorkFinal(TaskWorkFinalNotification notification);
}
