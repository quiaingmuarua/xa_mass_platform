package com.xa.mass.testing.workerfault;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkerFaultReportMetadata {

    private WorkerFaultReportMetadata() {
    }

    public static Map<String, Object> topLevel(WorkerFaultScenarioIndex.Scenario scenario) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenarioId", scenario.scenarioId());
        metadata.put("transport", scenario.transport());
        metadata.put("runtimeBackend", scenario.runtimeBackend());
        metadata.put("workerProfile", scenario.workerProfile());
        metadata.put("faultShape", scenario.faultShape());
        return Map.copyOf(metadata);
    }

    public static Map<String, Object> merge(WorkerFaultScenarioIndex.Scenario scenario,
                                            Map<String, Object> report) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(topLevel(scenario));
        if (report != null) {
            merged.putAll(report);
        }
        return Map.copyOf(merged);
    }
}
