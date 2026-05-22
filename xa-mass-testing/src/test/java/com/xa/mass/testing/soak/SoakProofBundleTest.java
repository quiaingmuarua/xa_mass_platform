package com.xa.mass.testing.soak;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakProofBundleTest {

    @Test
    void exposesStableProofSections() {
        SoakProofBundle bundle = new SoakProofBundle(
                new SoakInvariantReport(true, List.of()),
                Map.of("totalResults", 10),
                Map.of("receivedItems", 10),
                Map.of("lateWorkerCount", 1),
                Map.of("available", true),
                new SoakTraceProof(true, "trace-dir", Map.of("valid", true), Map.of("count", 3), 0, List.of()),
                Map.of("scenarioId", "polling-scheduling-soak"),
                List.of()
        );

        Map<String, Object> values = bundle.toMap();

        assertTrue(values.containsKey("runtimeInvariants"));
        assertTrue(values.containsKey("resultSequentialRead"));
        assertTrue(values.containsKey("workerMetrics"));
        assertTrue(values.containsKey("workerLifecycle"));
        assertTrue(values.containsKey("deliveryDiagnostics"));
        assertTrue(values.containsKey("trace"));
        assertTrue(values.containsKey("matrixProfile"));
        assertTrue(values.containsKey("failureSamples"));
        assertEquals("polling-scheduling-soak", ((Map<?, ?>) values.get("matrixProfile")).get("scenarioId"));
        assertEquals(0, ((List<?>) values.get("failureSamples")).size());
        assertTrue(((Map<?, ?>) values.get("trace")).containsKey("analyses"));
    }
}
