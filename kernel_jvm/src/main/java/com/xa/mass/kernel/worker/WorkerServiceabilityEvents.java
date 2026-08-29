package com.xa.mass.kernel.worker;

import java.util.Map;

/** Semantic Mechanism port for bounded Worker serviceability evidence events. */
public interface WorkerServiceabilityEvents {

    void onConnected(Map<String, Long> observedAtByWorkerId);

    void onRouteUnavailable(Map<String, Long> observedAtByWorkerId);

    void onProbeUnavailable(Map<String, Long> observedAtByWorkerId);
}
