package com.xa.mass.transport;

/**
 * Transport-neutral registry for addressing online worker endpoints.
 *
 * <p>The current WebSocket implementation keeps channel/session details behind
 * this interface. Future polling, gRPC, or custom socket adapters should be
 * able to provide the same semantics without leaking protocol-specific types
 * into the scheduler/runtime composition layer.
 *
 * <p>Route-only send is reserved for raw/manual side channels. Task dispatch
 * must use selected-worker addressing so an assigned item cannot silently
 * fallback to an arbitrary route endpoint.
 */
public interface WorkerEndpointRegistry {

    boolean sendToAdapterRoute(String adapterId, String routeKey, String message);

    boolean sendToSelectedWorker(String adapterId, String selectedWorkerId, String message);

    boolean isAdapterRouteOnline(String adapterId, String routeKey);

    int getActiveConnectionCount();

    void shutdown();
}
