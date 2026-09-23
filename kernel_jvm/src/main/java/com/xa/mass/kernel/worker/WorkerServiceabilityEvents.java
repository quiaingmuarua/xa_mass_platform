package com.xa.mass.kernel.worker;

import java.util.Map;

/** Semantic Mechanism port for bounded Worker serviceability evidence events. */
public interface WorkerServiceabilityEvents {

    void onAvailable(Map<String, NetworkObservation> observedAtByWorkerId);

    void onRouteUnavailable(Map<String, NetworkObservation> observedAtByWorkerId);

    void onProbeUnavailable(Map<String, NetworkObservation> observedAtByWorkerId);

    /** Address-bearing evidence, not Route or lease truth. */
    record NetworkObservation(String endpointManagerId, long observedAtMillis) {
        public NetworkObservation {
            if (endpointManagerId == null || endpointManagerId.isBlank() || observedAtMillis <= 0) {
                throw new IllegalArgumentException("Network observation requires Endpoint and positive time");
            }
        }
    }
}
