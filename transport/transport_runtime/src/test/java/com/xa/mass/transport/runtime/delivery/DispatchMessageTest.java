package com.xa.mass.transport.runtime.delivery;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatchMessageTest {

    @Test
    void exposesOnlyFlatSelectedWorkerDispatchFields() {
        List<String> componentNames = Arrays.stream(DispatchMessage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of(
                "deliveryId",
                "selectedWorkerId",
                "payload",
                "correlationRef",
                "deadlineEpochMillis",
                "createdAtEpochMillis"
        ), componentNames);
    }

    @Test
    void normalizesAddressingFactsAndPreservesOpaquePayload() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DispatchMessage("delivery-1", " ", "{}", "corr-1", -1L, -2L)
        );
        assertEquals("selectedWorkerId must not be blank", error.getMessage());

        DispatchMessage item = new DispatchMessage(" delivery-1 ", " worker-1 ", " payload ", " corr-1 ", -1L, -2L);
        assertEquals("delivery-1", item.deliveryId());
        assertEquals("worker-1", item.selectedWorkerId());
        assertEquals(" payload ", item.payload());
        assertEquals("corr-1", item.correlationRef());
        assertEquals(0L, item.deadlineEpochMillis());
        assertEquals(0L, item.createdAtEpochMillis());
    }
}
