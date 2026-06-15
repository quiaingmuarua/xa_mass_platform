package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenarioExpanderTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void expandsCountedWorkersAndPlaceholders() {
        List<WorkerScenarioSpec> workers = ScenarioExpander.expandWorkerSpecs(List.of(Map.of(
                "count", 2,
                "workerId", "worker-${PAD3}",
                "workerKey", "worker-${PAD3}-key",
                "workerGroupId", "group",
                "adapterType", "polling",
                "attributes", Map.of(
                        "region", "${REGION}",
                        "fingerprintProfile", "${FINGERPRINT}"
                ),
                "eventBindings", List.of(Map.of(
                        "eventCode", "probe.phone.metadata",
                        "projectCodes", List.of("deviceProbe")
                ))
        )), objectMapper);

        assertEquals(2, workers.size());
        assertEquals("worker-001", workers.get(0).workerId());
        assertEquals("worker-002", workers.get(1).workerId());
        assertEquals("fp-sg-alpha", workers.get(0).attributes().get("fingerprintProfile"));
        assertEquals("fp-sg-beta", workers.get(1).attributes().get("fingerprintProfile"));
        assertEquals("probe.phone.metadata", workers.get(0).eventBindings().getFirst().eventCode());
    }

    @Test
    void expandsGeneratedTaskItems() {
        List<TaskScenarioSpec> tasks = ScenarioExpander.expandTaskSpecs(List.of(Map.of(
                "body", Map.of(
                        "project", "deviceProbe",
                        "userId", "sample",
                        "eventCode", "probe.phone.metadata"
                ),
                "generatedItems", Map.of(
                        "count", 2,
                        "template", Map.of(
                                "caseId", "phone-${PAD5}",
                                "sequence", "${INDEX1}",
                                "mccMnc", "${MCC_MNC}"
                        )
                )
        )), objectMapper);

        assertEquals(1, tasks.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) tasks.getFirst().body().get("items");
        assertEquals(2, items.size());
        assertEquals("phone-00001", items.getFirst().get("caseId"));
        assertEquals("1", items.getFirst().get("sequence"));
        assertEquals("52505", items.get(1).get("mccMnc"));
    }
}
