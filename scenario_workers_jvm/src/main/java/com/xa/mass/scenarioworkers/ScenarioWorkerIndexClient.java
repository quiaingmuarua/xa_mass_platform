package com.xa.mass.scenarioworkers;

import java.time.Duration;
import java.util.Map;

interface ScenarioWorkerIndexClient {

    Map<String, ScenarioWorkerIndexResult> updateIndexedProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> updates,
            Duration timeout
    );
}

record ScenarioWorkerIndexResult(String status, String reason) {

    ScenarioWorkerIndexResult {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must be non-blank");
        }
    }

    boolean accepted() {
        return "ok".equals(status) || "noop".equals(status);
    }

    boolean notFound() {
        return "not_found".equals(status);
    }
}
