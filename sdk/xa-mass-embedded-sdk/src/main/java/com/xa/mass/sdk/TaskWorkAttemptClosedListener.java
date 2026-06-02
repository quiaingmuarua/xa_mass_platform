package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskWorkAttemptClosedNotification;

/**
 * Listener that fires when one concrete task work attempt reaches a final state.
 */
@FunctionalInterface
public interface TaskWorkAttemptClosedListener {

    void onTaskWorkAttemptClosed(TaskWorkAttemptClosedNotification notification);
}
