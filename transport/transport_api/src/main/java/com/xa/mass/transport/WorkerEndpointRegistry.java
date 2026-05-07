package com.xa.mass.transport;

/**
 * Transport-neutral registry for addressing online worker endpoints.
 *
 * <p>The current WebSocket implementation keeps channel/session details behind
 * this interface. Future polling, gRPC, or custom socket adapters should be
 * able to provide the same semantics without leaking protocol-specific types
 * into the scheduler/runtime composition layer.
 *
 * <p>The transport-neutral mainline is adapter-scoped route addressing. Callers
 * must provide the concrete {@code adapterId + routeKey} pair rather than
 * depending on route-only lookup convenience.
 */
public interface WorkerEndpointRegistry {

    boolean sendToAdapterRoute(String adapterId, String routeKey, String message);

    boolean isAdapterRouteOnline(String adapterId, String routeKey);

    int getActiveConnectionCount();

    void shutdown();
}
