package com.xa.mass.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.client.worker.WorkerEventBindingSpec;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
record WorkerScenarioSpec(
        String workerId,
        String workerKey,
        String workerGroupId,
        String adapterType,
        String transportHint,
        String startMode,
        Map<String, String> attributes,
        List<WorkerEventBindingSpec> eventBindings
) {
}
