package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceAttributesTest {

    @Test
    void setAttributesCopiesInputAndExposesReadOnlyView() {
        Device device = new Device();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("pool", "warmup");

        device.setAttributes(input);
        input.put("pool", "mutated");

        assertEquals("warmup", device.getAttributes().get("pool"));
        assertThrows(UnsupportedOperationException.class,
                () -> device.getAttributes().put("risk", "low"));
    }

    @Test
    void nullAttributesReturnsEmptyMap() {
        Device device = new Device();

        device.setAttributes(null);

        assertTrue(device.getAttributes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> device.getAttributes().put("pool", "warmup"));
    }
}
