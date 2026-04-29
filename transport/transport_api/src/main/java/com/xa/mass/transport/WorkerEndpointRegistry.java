package com.xa.mass.transport;

/**
 * Transport-neutral registry for addressing online worker endpoints.
 *
 * <p>The current WebSocket implementation keeps channel/session details behind
 * this interface. Future polling, gRPC, or custom socket adapters should be
 * able to provide the same semantics without leaking protocol-specific types
 * into the scheduler/runtime composition layer.
 */
public interface WorkerEndpointRegistry {

    boolean sendToRoute(String routeKey, String message);

    boolean isRouteOnline(String routeKey);

    default boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        return sendToRoute(routeKey, message);
    }

    default boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        return isRouteOnline(routeKey);
    }

    int getActiveConnectionCount();

    void shutdown();
}
