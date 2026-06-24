package com.xa.mass.worker.runtime.control;

/**
 * Negative-only worker-runtime dispatch block port.
 *
 * <p>External producers such as transport may make a worker less schedulable
 * through this port. They cannot clear block sources or make a worker
 * schedulable.</p>
 */
public interface WorkerDispatchBlockRuntime {

    boolean blockWorkerDispatch(String workerId, WorkerDispatchBlockSignal signal);

    boolean blockWorkerDispatch(String workerGroupId, String workerId, WorkerDispatchBlockSignal signal);
}
