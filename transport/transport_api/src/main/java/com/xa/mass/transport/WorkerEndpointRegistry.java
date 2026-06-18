package com.xa.mass.transport;

/**
 * Transport-neutral registry for assigned worker endpoints.
 *
 * <p>This surface is selected-worker only. Assigned task delivery must address
 * the engine-selected worker identity and must not fallback to route-only raw
 * sends.
 */
public interface WorkerEndpointRegistry {

    boolean sendToSelectedWorker(String selectedWorkerId, String message);

    int getActiveConnectionCount();

    void shutdown();
}
