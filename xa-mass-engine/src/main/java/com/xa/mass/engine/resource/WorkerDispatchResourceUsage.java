package com.xa.mass.engine.resource;

/**
 * Resource usage shape consumed by scheduling mechanisms.
 */
public record WorkerDispatchResourceUsage(boolean exclusiveWorkerLock) {
}
