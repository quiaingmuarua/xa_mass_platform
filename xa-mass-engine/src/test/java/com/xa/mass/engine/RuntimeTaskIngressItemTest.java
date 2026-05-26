package com.xa.mass.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeTaskIngressItemTest {

    @Test
    void projectedInputKeepsEventCodeAndInlinePayloadForReviewReadModel() {
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

        assertEquals("probe.phone.metadata", projected.get("eventCode"));
        assertEquals("+447700900123", projected.get("phoneNumber"));
        assertEquals("GB", projected.get("countryIso2"));
        assertEquals("fp-android-sg-b", projected.get("requiredFingerprintProfile"));
        assertEquals("VALID_E164", projected.get("expectedOutcome"));
    }
}
