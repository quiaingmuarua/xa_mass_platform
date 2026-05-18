package com.xa.mass.testing.soak;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record SoakProofBundle(SoakInvariantReport runtimeInvariants,
                       Map<String, Object> resultSequentialRead,
                       Map<String, Object> workerMetrics,
                       Map<String, Object> workerLifecycle,
                       Map<String, Object> deliveryDiagnostics,
                       SoakTraceProof trace,
                       List<String> failureSamples) {

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runtimeInvariants", runtimeInvariants.toMap());
        values.put("resultSequentialRead", resultSequentialRead);
        values.put("workerMetrics", workerMetrics);
        values.put("workerLifecycle", workerLifecycle);
        values.put("deliveryDiagnostics", deliveryDiagnostics);
        values.put("trace", trace.toMap());
        values.put("failureSamples", List.copyOf(failureSamples));
        return values;
    }
}
