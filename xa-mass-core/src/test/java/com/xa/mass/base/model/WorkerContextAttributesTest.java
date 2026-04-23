package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void projectBindingIsCanonicalizedOnWorkerContext() {
        WorkerContext workerContext = new WorkerContext();

        workerContext.setProject("demoApp");

        assertEquals("demoApp", workerContext.getProject());
        assertNotNull(workerContext.getProjectRef());
        assertEquals("demoApp", workerContext.getProjectRef().getCode());
    }
}
