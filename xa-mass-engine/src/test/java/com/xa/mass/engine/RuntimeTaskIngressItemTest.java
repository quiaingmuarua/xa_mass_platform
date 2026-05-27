package com.xa.mass.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTaskIngressItemTest {

    @Test
    void projectedInputDoesNotPersistRuntimePayloadInCompatibilityReadModel() {
        RuntimeTaskIngressItem item = RuntimeTaskIngressItem.fromInput(
                "task-1",
                "message-1",
                Map.of(
                        "eventCode", "probe.phone.metadata",
                        "phoneNumber", "+447700900123",
                        "countryIso2", "GB",
                        "requiredFingerprintProfile", "fp-android-sg-b",
                        "expectedOutcome", "VALID_E164"
                ),
                1
        );

        Map<String, Object> projected = item.projectedInput();

        assertTrue(projected.isEmpty());
        assertEquals("probe.phone.metadata", item.eventCode());
        assertEquals("+447700900123", item.inlinePayload().get("phoneNumber"));
        assertEquals("GB", item.inlinePayload().get("countryIso2"));
        assertEquals("fp-android-sg-b", item.inlinePayload().get("requiredFingerprintProfile"));
        assertEquals("VALID_E164", item.inlinePayload().get("expectedOutcome"));
    }
}
