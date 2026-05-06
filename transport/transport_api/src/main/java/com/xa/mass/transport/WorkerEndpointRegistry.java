package com.xa.mass.transport;

/**
 * Transport-neutral registry for addressing online worker endpoints.
 *
 * <p>The current WebSocket implementation keeps channel/session details behind
 * this interface. Future polling, gRPC, or custom socket adapters should be
 * able to provide the same semantics without leaking protocol-specific types
 * into the scheduler/runtime composition layer.
 *
 * <p>{@link #sendToRoute(String, String)} and {@link #isRouteOnline(String)}
 * are adapter-local operations. They are safe when the caller already owns the
 * concrete adapter registry or when only one adapter registry is present in a
 * composite runtime. Callers operating against a multi-adapter aggregate must
 * prefer the adapter-scoped methods.
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
