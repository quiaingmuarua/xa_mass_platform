package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportResultIngressEnvelopeTest {

    @Test
    void envelopeNormalizesOpaqueTransportFieldsOnly() {
        TransportResultIngressEnvelope envelope = new TransportResultIngressEnvelope(
                " ingress-1 ",
                " {\"taskId\":\"task-1\"} ",
                " {\"attemptId\":\"attempt-1\"} ",
                " message-1 ",
                diagnostics(),
                123L
        );

        assertEquals("ingress-1", envelope.getIngressId());
        assertEquals("{\"taskId\":\"task-1\"}", envelope.getPayload());
        assertEquals("{\"attemptId\":\"attempt-1\"}", envelope.getCorrelation());
        assertEquals("message-1", envelope.getPartitionKey());
        assertEquals("websocket", envelope.diagnostic(" adapterId "));
        assertEquals("trace-1", envelope.diagnostic("traceId"));
        assertNull(envelope.diagnostic("blank"));
        assertEquals(123L, envelope.getReceivedAtEpochMillis());
    }

    @Test
    void generatedEnvelopeGetsIngressIdAndTimestamp() {
        TransportResultIngressEnvelope envelope =
                TransportResultIngressEnvelope.received("{\"ok\":true}", null, null, null);

        assertNotNull(envelope.getIngressId());
        assertEquals("{\"ok\":true}", envelope.getPayload());
        assertNull(envelope.getCorrelation());
        assertNull(envelope.getPartitionKey());
        assertFalse(envelope.getReceivedAtEpochMillis() <= 0L);
    }

    @Test
    void rejectsBlankPayload() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TransportResultIngressEnvelope.received(" ", null, null, null)
        );

        assertEquals("payload must not be blank", error.getMessage());
    }

    @Test
    void transportEnvelopeDoesNotExposeTaskResultSchema() {
        String methodNames = Arrays.stream(TransportResultIngressEnvelope.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.joining("\n"));

        assertFalse(methodNames.contains("getTaskId"));
        assertFalse(methodNames.contains("getMessageId"));
        assertFalse(methodNames.contains("isSuccess"));
        assertFalse(methodNames.contains("getOutput"));
        assertFalse(methodNames.contains("getAdapterId"));
        assertFalse(methodNames.contains("getRouteKey"));
    }

    private static Map<String, String> diagnostics() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(" adapterId ", " websocket ");
        values.put("traceId", " trace-1 ");
        values.put("blank", " ");
        values.put(" ", "ignored");
        return values;
    }
}
