package com.xa.mass.worker.runtime.resource;

/**
 * Narrow worker-runtime heartbeat freshness mutation surface.
 */
public interface WorkerHeartbeatRuntime {

    boolean refreshWorkerHeartbeat(String workerId, long observedAtMillis);
}
