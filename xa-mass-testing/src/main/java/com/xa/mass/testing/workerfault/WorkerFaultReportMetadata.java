package com.xa.mass.testing.workerfault;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkerFaultReportMetadata {

    private static final String SCENARIO_ID = "scenarioId";
    private static final String TRANSPORT = "transport";
    private static final String RUNTIME_BACKEND = "runtimeBackend";
    private static final String WORKER_PROFILE = "workerProfile";
    private static final String FAULT_SHAPE = "faultShape";

    private WorkerFaultReportMetadata() {
    }

    public static Map<String, Object> topLevel(WorkerFaultScenarioIndex.Scenario scenario) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SCENARIO_ID, scenario.scenarioId());
        metadata.put(TRANSPORT, scenario.transport());
        metadata.put(RUNTIME_BACKEND, scenario.runtimeBackend());
        metadata.put(WORKER_PROFILE, scenario.workerProfile());
        metadata.put(FAULT_SHAPE, scenario.faultShape());
        return Map.copyOf(metadata);
    }

    public static Map<String, Object> merge(WorkerFaultScenarioIndex.Scenario scenario,
                                            Map<String, Object> report) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(topLevel(scenario));
        if (report != null) {
            report.forEach((key, value) -> putReportField(merged, key, value));
        }
        return Map.copyOf(merged);
    }

    private static void putReportField(LinkedHashMap<String, Object> merged,
                                       String key,
                                       Object value) {
        if (merged.containsKey(key)) {
            Object existing = merged.get(key);
            if (!existing.equals(value)) {
                throw new IllegalArgumentException("worker fault report field " + key
                        + " conflicts with matrix metadata: " + value + " != " + existing);
            }
            return;
        }
        merged.put(key, value);
    }
}
