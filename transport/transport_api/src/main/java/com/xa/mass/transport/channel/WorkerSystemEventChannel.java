package com.xa.mass.transport.channel;

/**
 * Transport-neutral system-event channel for worker presence signals.
 *
 * <p>This channel is an ingress seam, not a lifecycle owner. Future worker
 * command, state-report, or capability-report flows must introduce a narrow
 * owner instead of treating this presence channel as a generic control-plane
 * router.</p>
 */
public interface WorkerSystemEventChannel {

    void publishWorkerOnline(String workerId, String reason, String traceId);

    void publishWorkerOffline(String workerId, String reason, String traceId);

    default void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
        // Default no-op until a concrete runtime wants to persist or route heartbeats explicitly.
    }
}
