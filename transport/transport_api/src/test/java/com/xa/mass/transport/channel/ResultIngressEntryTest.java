package com.xa.mass.transport.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultIngressEntryTest {

    @Test
    void exposesOnlyExplicitResultIngressEntryFields() {
        List<String> componentNames = Arrays.stream(ResultIngressEntry.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("partitionKey", "message", "diagnostics"), componentNames);
    }

    @Test
    void keepsDiagnosticsOutOfMessagePayload() {
        ResultIngressEntry entry = new ResultIngressEntry(
                " corr-1 ",
                new ResultIngressMessage("result-msg-1", "corr-1", "{}", 0L, 1L),
                new ResultIngressDiagnostics(Map.of("adapterId", "websocket"))
        );

        assertEquals("corr-1", entry.partitionKey());
        assertEquals("corr-1", entry.message().resultCorrelationRef());
        assertEquals("websocket", entry.diagnostics().get("adapterId"));
    }

    @Test
    void rejectsBlankPartitionKey() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ResultIngressEntry(" ", new ResultIngressMessage("result-msg-1", "corr-1", "{}", 0L, 1L), null)
        );
        assertEquals("partitionKey must not be blank", error.getMessage());
    }
}
