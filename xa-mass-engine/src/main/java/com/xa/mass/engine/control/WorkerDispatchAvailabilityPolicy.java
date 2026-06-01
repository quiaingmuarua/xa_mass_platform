package com.xa.mass.engine.control;

import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;

/**
 * Strategy seam that translates worker-control owner truth into dispatch gate
 * mutations.
 *
 * <p>The scheduling kernel continues to consume only dispatch availability
 * truth. Policy implementations decide which worker state reports or command
 * lifecycle transitions should mutate that truth.</p>
 */
public interface WorkerDispatchAvailabilityPolicy {

    void applyWorkerStateProjection(WorkerStateProjection projection,
                                    WorkerDispatchGateRuntime dispatchGateRuntime);

    void applyWorkerCommandLifecycleResult(WorkerCommandLifecycleResult result,
                                           WorkerDispatchGateRuntime dispatchGateRuntime);
}
