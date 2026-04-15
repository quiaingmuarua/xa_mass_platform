package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerAttributesTest {

    @Test
    void setAttributesCopiesInputAndExposesReadOnlyView() {
        Worker worker = new Worker();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("pool", "warmup");

        worker.setAttributes(input);
        input.put("pool", "mutated");

        assertEquals("warmup", worker.getAttributes().get("pool"));
        assertThrows(UnsupportedOperationException.class,
                () -> worker.getAttributes().put("risk", "low"));
    }

    @Test
    void nullAttributesReturnsEmptyMap() {
        Worker worker = new Worker();

        worker.setAttributes(null);

        assertTrue(worker.getAttributes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.getAttributes().put("pool", "warmup"));
    }
}
