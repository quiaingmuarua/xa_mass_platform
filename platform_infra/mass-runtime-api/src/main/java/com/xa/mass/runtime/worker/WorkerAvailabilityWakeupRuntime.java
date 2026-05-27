package com.xa.mass.runtime.worker;

/**
 * Lifecycle hook for worker-runtime evidence that can make waiting tasks
 * eligible for assignment.
 *
 * <p>This is assembly wiring only. It is not worker scheduling truth and must
 * not become a candidate-source or dispatch-gate contract.</p>
 */
public interface WorkerAvailabilityWakeupRuntime {

    void setDispatchWakeupCallback(Runnable dispatchWakeupCallback);
}
