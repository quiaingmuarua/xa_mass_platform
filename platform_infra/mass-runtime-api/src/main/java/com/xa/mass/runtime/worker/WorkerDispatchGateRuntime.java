package com.xa.mass.runtime.worker;

/**
 * Runtime dispatch-gate mutation/read surface.
 *
 * <p>This is the narrow contract used by worker-control policies that translate
 * state or command lifecycle evidence into dispatch eligibility. It does not own
 * worker registration, reporting, candidate source, or matching policy.</p>
 */
public interface WorkerDispatchGateRuntime {

    boolean isWorkerDispatchEnabled(String workerId);

    boolean disableWorkerDispatch(String workerId, DispatchAvailabilitySource source, String reason);

    boolean clearWorkerDispatchDisable(String workerId, DispatchAvailabilitySource source, String reason);
}
