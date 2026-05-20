package com.xa.mass.engine.worker;

import com.xa.mass.engine.command.WorkerCommandLifecycleResult;

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
                                    WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner);

    void applyWorkerCommandLifecycleResult(WorkerCommandLifecycleResult result,
                                           WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner);
}
