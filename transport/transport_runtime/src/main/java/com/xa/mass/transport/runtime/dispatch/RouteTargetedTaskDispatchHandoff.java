package com.xa.mass.transport.runtime.dispatch;

/**
 * Engine -> transport dispatch handoff for already assigned delivery batches.
 *
 * <p>The batch may carry an opaque routeKey for adapter correlation, but the
 * physical handoff queue should be keyed by the resolved adapter dispatch lane.
 */
public interface RouteTargetedTaskDispatchHandoff {

    void submit(RouteTargetedTaskDispatchBatch batch);

    RouteTargetedTaskDispatchBatch poll(long timeoutMillis) throws InterruptedException;

    void shutdown();
}
