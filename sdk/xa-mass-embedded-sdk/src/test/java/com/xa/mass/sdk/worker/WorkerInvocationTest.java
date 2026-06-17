package com.xa.mass.sdk.worker;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerInvocationTest {

    @Test
    void exposesOnlySdkWorkerPullFields() {
        Set<String> fields = Arrays.stream(WorkerInvocation.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "resultCorrelationRef",
                "eventCode",
                "input",
                "sharedConfig"
        ), fields);
        assertFalse(fields.contains("routeKey"));
        assertFalse(fields.contains("transportPayload"));
        assertFalse(fields.contains("TransportPacket"));
        assertFalse(fields.contains("workerId"));
    }

    @Test
    void normalizesMapsAndCounters() {
        WorkerInvocation item = new WorkerInvocation(
                " corr-1 ",
                " event-1 ",
                Map.of("target", "a"),
                null
        );

        assertEquals("corr-1", item.getResultCorrelationRef());
        assertEquals("event-1", item.getEventCode());
        assertEquals(Map.of("target", "a"), item.getInput());
        assertEquals(Map.of(), item.getSharedConfig());
    }

    @Test
    void requiresResultCorrelationRef() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerInvocation(
                " ",
                null,
                Map.of(),
                Map.of()
        ));
    }
}
