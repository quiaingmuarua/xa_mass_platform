package com.xa.mass.kernel.pacer.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

record WorkerCandidateRequest(
        int priority,
        int requestedCount,
        Map<String, Object> allocationRule
) {
    public WorkerCandidateRequest {
        if (priority < 0 || priority > 99) {
            throw new IllegalArgumentException(
                    "candidate priority must be in 0..99"
            );
        }
        if (requestedCount <= 0) {
            throw new IllegalArgumentException(
                    "requested candidate count must be positive"
            );
        }
        if (allocationRule == null) {
            throw new IllegalArgumentException(
                    "candidate allocation rule must be present"
            );
        }
        allocationRule = Map.copyOf(new LinkedHashMap<>(allocationRule));
    }
}
