package com.xa.mass.base.runtime.dispatch;

/**
 * Explicit engine -> transport handoff seam for dispatch-ready batches.
 *
 * <p>The default runtime may still use an in-memory implementation, but the
 * queue/store replacement point is now explicit instead of a direct in-process
 * listener call.</p>
 */
public interface TaskDispatchHandoff {

    void submit(TaskDispatchBatch batch);

    TaskDispatchBatch poll(long timeoutMillis) throws InterruptedException;

    void shutdown();
}
