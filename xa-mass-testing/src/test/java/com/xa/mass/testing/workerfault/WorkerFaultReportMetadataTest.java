package com.xa.mass.testing.workerfault;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerFaultReportMetadataTest {

    @Test
    void emitsStableTopLevelMatrixFields() {
        Map<String, Object> metadata = WorkerFaultReportMetadata.topLevel(
                WorkerFaultScenarioIndex.Scenario.POLLING_LEASE_EXPIRY_REDISPATCH);

        assertEquals("polling-lease-expiry-redispatch", metadata.get("scenarioId"));
        assertEquals("polling", metadata.get("transport"));
        assertEquals("memory", metadata.get("runtimeBackend"));
        assertEquals("STALL_LEASE_TAKEOVER", metadata.get("workerProfile"));
        assertEquals("lease-expiry-redispatch", metadata.get("faultShape"));
    }

    @Test
    void mergesMetadataWithoutDroppingExistingReportSections() {
        Map<String, Object> report = WorkerFaultReportMetadata.merge(
                WorkerFaultScenarioIndex.Scenario.WEBSOCKET_DISCONNECT_RECONNECT,
                Map.of("runtime", Map.of("transport", "websocket")));

        assertEquals("websocket-disconnect-reconnect", report.get("scenarioId"));
        assertEquals("websocket", report.get("transport"));
        assertEquals(Map.of("transport", "websocket"), report.get("runtime"));
    }
}
