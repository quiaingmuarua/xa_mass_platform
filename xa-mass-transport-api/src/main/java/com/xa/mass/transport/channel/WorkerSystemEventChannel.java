package com.xa.mass.transport.channel;

/**
 * Transport-neutral system-event channel for worker presence and control-plane signals.
 */
public interface WorkerSystemEventChannel {

    void publishWorkerOnline(String workerId, String reason, String traceId);

    void publishWorkerOffline(String workerId, String reason, String traceId);

    default void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
        // Default no-op until a concrete runtime wants to persist or route heartbeats explicitly.
    }
}
