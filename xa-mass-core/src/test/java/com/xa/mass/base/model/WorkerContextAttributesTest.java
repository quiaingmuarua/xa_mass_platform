package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerContextAttributesTest {

    @Test
    void setAttributesCopiesInputAndExposesReadOnlyView() {
        WorkerContext wc = new WorkerContext();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("country", "us");

        wc.setAttributes(input);
        input.put("country", "gb");

        assertEquals("us", wc.getAttributes().get("country"));
        assertThrows(UnsupportedOperationException.class,
                () -> wc.getAttributes().put("carrier", "tmo"));
    }

    @Test
    void nullAttributesReturnsEmptyMap() {
        WorkerContext wc = new WorkerContext();

        wc.setAttributes(null);

        assertTrue(wc.getAttributes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> wc.getAttributes().put("country", "us"));
    }
}
