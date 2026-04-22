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

    boolean sendMessage(String workerId, String endpointRole, String message);

    boolean isWorkerOnline(String workerId, String endpointRole);

    int getActiveConnectionCount();

    void shutdown();
}
