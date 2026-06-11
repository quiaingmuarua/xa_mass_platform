package com.xa.mass.transport.runtime.dispatch;

/**
 * Engine -> transport dispatch handoff keyed by opaque routeKey.
 */
public interface RouteTargetedTaskDispatchHandoff {

    void submit(RouteTargetedTaskDispatchBatch batch);

    RouteTargetedTaskDispatchBatch poll(long timeoutMillis) throws InterruptedException;

    void shutdown();
}
