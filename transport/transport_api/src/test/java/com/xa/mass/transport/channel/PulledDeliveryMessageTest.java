package com.xa.mass.transport.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulledDeliveryMessageTest {

    @Test
    void exposesOnlyOpaqueDeliveryFields() {
        Set<String> fields = Arrays.stream(PulledDeliveryMessage.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "deliveryId",
                "selectedWorkerId",
                "payload",
                "correlationRef",
                "createdAtEpochMillis"
        ), fields);
        assertFalse(fields.contains("taskId"));
        assertFalse(fields.contains("messageId"));
        assertFalse(fields.contains("eventCode"));
        assertFalse(fields.contains("attemptId"));
        assertFalse(fields.contains("routeKey"));
        assertFalse(fields.contains("transportPayload"));
        assertFalse(fields.contains("workerId"));
    }

    @Test
    void normalizesRequiredText() {
        PulledDeliveryMessage message = new PulledDeliveryMessage(
                " delivery-1 ",
                " worker-1 ",
                " {\"messageId\":\"msg-1\"} ",
                " corr-1 ",
                -1L
        );

        assertEquals("delivery-1", message.getDeliveryId());
        assertEquals("worker-1", message.getSelectedWorkerId());
        assertEquals("{\"messageId\":\"msg-1\"}", message.getPayload());
        assertEquals("corr-1", message.getCorrelationRef());
        assertEquals(0L, message.getCreatedAtEpochMillis());
    }

    @Test
    void requiresDeliveryWorkerPayloadAndCorrelation() {
        assertThrows(IllegalArgumentException.class, () -> message(" ", "worker-1", "payload", "corr"));
        assertThrows(IllegalArgumentException.class, () -> message("delivery-1", " ", "payload", "corr"));
        assertThrows(IllegalArgumentException.class, () -> message("delivery-1", "worker-1", " ", "corr"));
        assertThrows(IllegalArgumentException.class, () -> message("delivery-1", "worker-1", "payload", " "));
    }

    private static PulledDeliveryMessage message(String deliveryId,
                                                 String selectedWorkerId,
                                                 String payload,
                                                 String correlationRef) {
        return new PulledDeliveryMessage(deliveryId, selectedWorkerId, payload, correlationRef, 0L);
    }
}
