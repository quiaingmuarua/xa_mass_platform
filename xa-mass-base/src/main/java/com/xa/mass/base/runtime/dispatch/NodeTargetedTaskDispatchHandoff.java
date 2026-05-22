package com.xa.mass.base.runtime.dispatch;

/**
 * Dispatch handoff variant with per-transport-node inboxes.
 */
public interface NodeTargetedTaskDispatchHandoff extends TaskDispatchHandoff {

    void submit(String transportNodeId, TaskDispatchBatch batch);

    TaskDispatchBatch poll(String transportNodeId, long timeoutMillis) throws InterruptedException;
}
