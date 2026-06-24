package com.xa.mass.worker.runtime.control;

import com.xa.mass.runtime.worker.DispatchAvailabilitySource;

/**
 * External negative evidence source accepted by worker-runtime.
 */
public enum WorkerDispatchBlockSource {
    TRANSPORT_DISCONNECTED(DispatchAvailabilitySource.TRANSPORT_DISCONNECTED);

    private final DispatchAvailabilitySource gateSource;

    WorkerDispatchBlockSource(DispatchAvailabilitySource gateSource) {
        this.gateSource = gateSource;
    }

    public DispatchAvailabilitySource gateSource() {
        return gateSource;
    }
}
