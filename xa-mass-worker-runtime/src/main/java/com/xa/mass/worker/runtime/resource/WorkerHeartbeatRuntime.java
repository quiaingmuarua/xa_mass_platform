package com.xa.mass.worker.runtime.resource;

/**
 * Narrow runtime heartbeat mutation surface for presence ingress.
 */
public interface WorkerHeartbeatRuntime {

    boolean refreshWorkerHeartbeat(String workerId, long observedAtMillis);
}
