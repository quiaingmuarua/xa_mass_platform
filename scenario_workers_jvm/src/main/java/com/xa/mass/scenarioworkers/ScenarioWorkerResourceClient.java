package com.xa.mass.scenarioworkers;

import java.time.Duration;
import java.util.Map;

interface ScenarioWorkerResourceClient {

    void registerWorker(
            String workerGroupId,
            String workerId,
            String endpointManagerId,
            Map<String, Object> workerProperties,
            Duration timeout
    );

    void updateWorkerProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> workerProperties,
            Duration timeout
    );

    Map<String, ScenarioWorkerResourceResult> updateIndexedProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> updates,
            Duration timeout
    );
}

record ScenarioWorkerResourceResult(String status, String reason) {

    ScenarioWorkerResourceResult {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must be non-blank");
        }
    }

    boolean accepted() {
        return "ok".equals(status) || "noop".equals(status);
    }
}
