package com.xa.mass.worker.runtime.evidence;

import java.util.Optional;

/**
 * Worker-runtime read seam for resolving the delivery target of an already
 * selected worker.
 */
@FunctionalInterface
public interface WorkerDeliveryTargetView {

    Optional<SelectedWorkerDeliveryTargetEvidence> resolveDeliveryTarget(String selectedWorkerId);

    static WorkerDeliveryTargetView unavailable() {
        return selectedWorkerId -> Optional.empty();
    }
}
