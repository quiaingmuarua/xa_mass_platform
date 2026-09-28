package com.xa.mass.kernel.worker;

import java.util.Map;

/** Semantic Mechanism port for source-validated, Group-local serviceability evidence. */
public interface WorkerServiceabilityEvents {

    void onAvailable(String workerGroupId, Map<String, Long> observedAtByWorkerId);

    void onRouteUnavailable(String workerGroupId, Map<String, Long> observedAtByWorkerId);

    void onProbeUnavailable(String workerGroupId, Map<String, Long> observedAtByWorkerId);
}
