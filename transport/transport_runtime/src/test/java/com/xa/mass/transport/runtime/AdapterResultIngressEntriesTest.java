package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterResultIngressEntriesTest {

    @Test
    void buildsResultIngressEntryWithGeneratedMessageFacts() {
        long before = System.currentTimeMillis();

        ResultIngressEntry entry = AdapterResultIngressEntries.from(
                " corr-1 ",
                " result body ",
                Map.of("adapterId", "websocket", "traceId", "trace-1")
        );

        assertEquals("corr-1", entry.partitionKey());
        assertEquals("corr-1", entry.message().resultCorrelationRef());
        assertEquals(" result body ", entry.message().payload());
        assertEquals(0L, entry.message().deadlineEpochMillis());
        assertTrue(entry.message().createdAtEpochMillis() >= before);
        assertNotNull(entry.message().resultMessageId());
        assertFalse(entry.message().resultMessageId().isBlank());
        assertEquals("websocket", entry.diagnostics().get("adapterId"));
        assertEquals("trace-1", entry.diagnostics().get("traceId"));
    }

    @Test
    void generatesDifferentResultMessageIds() {
        ResultIngressEntry first = AdapterResultIngressEntries.from("corr-1", "payload", Map.of());
        ResultIngressEntry second = AdapterResultIngressEntries.from("corr-1", "payload", Map.of());

        assertNotEquals(first.message().resultMessageId(), second.message().resultMessageId());
    }

    @Test
    void copiesDiagnostics() {
        LinkedHashMap<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("adapterId", "socket");

        ResultIngressEntry entry = AdapterResultIngressEntries.from("corr-1", "payload", diagnostics);
        diagnostics.put("adapterId", "changed");

        assertEquals("socket", entry.diagnostics().get("adapterId"));
    }

    @Test
    void validatesRequiredFacts() {
        assertThrows(IllegalArgumentException.class,
                () -> AdapterResultIngressEntries.from(" ", "payload", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AdapterResultIngressEntries.from("corr-1", null, Map.of()));
    }
}
