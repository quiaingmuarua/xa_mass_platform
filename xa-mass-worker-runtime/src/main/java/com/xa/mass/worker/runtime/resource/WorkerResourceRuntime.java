package com.xa.mass.worker.runtime.resource;

/**
 * Worker resource declaration, slot heartbeat, and dispatch-gate mutation surface.
 */
public interface WorkerResourceRuntime extends WorkerResourceQueryRuntime,
        WorkerResourceDeclarationRuntime,
        WorkerNodeBindingRuntime {

    boolean refreshWorkerHeartbeat(String workerId, long observedAtMillis);
}
