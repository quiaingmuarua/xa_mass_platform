package com.xa.mass.worker.runtime.control;

import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;

/**
 * Worker-runtime owner for interpreting control evidence into dispatch
 * eligibility.
 */
public interface WorkerDispatchEligibilityRuntime {

    boolean isWorkerDispatchEnabled(String workerId);

    void applyWorkerStateProjection(WorkerStateProjection projection);

    void applyWorkerCommandLifecycleResult(WorkerCommandLifecycleResult result);
}
