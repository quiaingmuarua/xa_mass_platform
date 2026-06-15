package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryCommandTest {

    @Test
    void carriesOnlyDeliveryIdentityOpaquePayloadAndCorrelation() {
        DeliveryCommand command = new DeliveryCommand(
                " command-1 ",
                " bucket-1 ",
                " worker-1 ",
                " {\"messageId\":\"msg-1\"} ",
                " corr-1 ",
                100L,
                10L
        );

        assertEquals("command-1", command.getCommandId());
        assertEquals("bucket-1", command.getDeliveryBucketId());
        assertEquals("worker-1", command.getSelectedWorkerId());
        assertEquals("{\"messageId\":\"msg-1\"}", command.getPayload());
        assertEquals("corr-1", command.getCorrelationRef());
        assertEquals(100L, command.getDeadlineEpochMillis());
        assertEquals(10L, command.getCreatedAtEpochMillis());
    }

    @Test
    void rejectsMissingRequiredItemFields() {
        assertThrows(IllegalArgumentException.class, () -> command(" ", "bucket-1", "worker-1", "payload", "corr"));
        assertThrows(IllegalArgumentException.class, () -> command("command-1", " ", "worker-1", "payload", "corr"));
        assertThrows(IllegalArgumentException.class, () -> command("command-1", "bucket-1", " ", "payload", "corr"));
        assertThrows(IllegalArgumentException.class, () -> command("command-1", "bucket-1", "worker-1", " ", "corr"));
        assertThrows(IllegalArgumentException.class, () -> command("command-1", "bucket-1", "worker-1", "payload", " "));
    }

    @Test
    void doesNotExposeTaskOrEndpointFacts() {
        Set<String> fields = Arrays.stream(DeliveryCommand.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "commandId",
                "deliveryBucketId",
                "selectedWorkerId",
                "payload",
                "correlationRef",
                "deadlineEpochMillis",
                "createdAtEpochMillis"
        ), fields);
        assertFalse(fields.contains("taskId"));
        assertFalse(fields.contains("messageId"));
        assertFalse(fields.contains("eventCode"));
        assertFalse(fields.contains("attemptId"));
        assertFalse(fields.contains("adapterId"));
        assertFalse(fields.contains("routeKey"));
        assertFalse(fields.contains("connectionId"));
    }

    private static DeliveryCommand command(String commandId,
                                           String deliveryBucketId,
                                           String selectedWorkerId,
                                           String payload,
                                           String correlationRef) {
        return new DeliveryCommand(commandId, deliveryBucketId, selectedWorkerId, payload, correlationRef, 0L, 0L);
    }
}
