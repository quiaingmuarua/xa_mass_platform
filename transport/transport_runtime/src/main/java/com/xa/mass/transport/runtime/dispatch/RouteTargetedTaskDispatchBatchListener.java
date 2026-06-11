package com.xa.mass.transport.runtime.dispatch;

/**
 * Consumer-side listener for route-targeted dispatch handoff batches.
 */
@FunctionalInterface
public interface RouteTargetedTaskDispatchBatchListener {

    void onRouteTargetedTaskDispatchBatch(RouteTargetedTaskDispatchBatch batch);
}
