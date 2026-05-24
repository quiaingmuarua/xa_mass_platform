package com.xa.mass.runtime.worker;

/**
 * Source-scoped reason that prevents a worker from receiving new dispatches.
 */
public enum DispatchAvailabilitySource {
    WORKER_STATE,
    WORKER_COMMAND,
    NODE_GROUP_BINDING
}
