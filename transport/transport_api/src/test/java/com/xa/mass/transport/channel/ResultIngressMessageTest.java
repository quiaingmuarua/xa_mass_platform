package com.xa.mass.transport.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultIngressMessageTest {

    @Test
    void exposesOnlyOpaqueResultIngressMessageFields() {
        List<String> componentNames = Arrays.stream(ResultIngressMessage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of(
                "resultMessageId",
                "resultCorrelationRef",
                "payload",
                "deadlineEpochMillis",
                "createdAtEpochMillis"
        ), componentNames);
    }

    @Test
    void normalizesAddressingFactsAndPreservesOpaquePayload() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ResultIngressMessage("result-msg-1", " ", "{}", -1L, -2L)
        );
        assertEquals("resultCorrelationRef must not be blank", error.getMessage());

        ResultIngressMessage message = new ResultIngressMessage(" result-msg-1 ", " corr-1 ", " payload ", -1L, -2L);
        assertEquals("result-msg-1", message.resultMessageId());
        assertEquals("corr-1", message.resultCorrelationRef());
        assertEquals(" payload ", message.payload());
        assertEquals(0L, message.deadlineEpochMillis());
        assertEquals(0L, message.createdAtEpochMillis());
    }
}
