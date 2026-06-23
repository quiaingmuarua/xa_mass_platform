package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdapterInboundResultProcessorTest {

    @Test
    void ingestsResultEntryUsingReaderAndDiagnosticsProvider() {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        AdapterInboundResultProcessor<String> processor = AdapterInboundResultProcessor.with(
                reading(new AdapterResultFrame("corr-1", " payload ", "trace-1", "frame-1")),
                entry -> {
                    captured.set(entry);
                    return true;
                },
                (frame, result) -> Map.of("adapterId", "websocket", "traceId", result.traceSeed())
        );

        AdapterResultProcessOutcome outcome = processor.processResult("frame");

        assertEquals(AdapterResultProcessOutcome.INGESTED, outcome);
        ResultIngressEntry entry = captured.get();
        assertNotNull(entry);
        assertEquals("corr-1", entry.partitionKey());
        assertEquals("corr-1", entry.message().resultCorrelationRef());
        assertEquals(" payload ", entry.message().payload());
        assertEquals("websocket", entry.diagnostics().get("adapterId"));
        assertEquals("trace-1", entry.diagnostics().get("traceId"));
    }

    @Test
    void rejectsWhenSinkIsUnavailable() {
        AdapterInboundResultProcessor<String> processor = AdapterInboundResultProcessor.with(
                reading(new AdapterResultFrame("corr-1", "payload", null, "frame-1")),
                null,
                (frame, result) -> Map.of()
        );

        assertEquals(AdapterResultProcessOutcome.REJECTED, processor.processResult("frame"));
    }

    @Test
    void rejectsInvalidReaderOutputWithoutCallingSink() {
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        AdapterInboundResultProcessor<String> processor = AdapterInboundResultProcessor.with(
                rejectingReader(),
                entry -> {
                    captured.set(entry);
                    return true;
                },
                (frame, result) -> Map.of()
        );

        assertEquals(AdapterResultProcessOutcome.REJECTED, processor.processResult("frame"));
        assertNull(captured.get());
    }

    @Test
    void failedWhenSinkRejectsOrThrows() {
        AdapterInboundResultProcessor<String> rejected = processorWithSink(entry -> false);
        AdapterInboundResultProcessor<String> throwing = processorWithSink(entry -> {
            throw new RuntimeException("boom");
        });

        assertEquals(AdapterResultProcessOutcome.FAILED, rejected.processResult("frame"));
        assertEquals(AdapterResultProcessOutcome.FAILED, throwing.processResult("frame"));
    }

    @Test
    void keepsProcessorOutcomeVocabularyNarrow() {
        assertEquals(3, AdapterResultProcessOutcome.values().length);
        assertEquals(AdapterResultProcessOutcome.INGESTED, AdapterResultProcessOutcome.valueOf("INGESTED"));
        assertEquals(AdapterResultProcessOutcome.REJECTED, AdapterResultProcessOutcome.valueOf("REJECTED"));
        assertEquals(AdapterResultProcessOutcome.FAILED, AdapterResultProcessOutcome.valueOf("FAILED"));
    }

    private AdapterInboundResultProcessor<String> processorWithSink(AdapterResultIngressSink sink) {
        return AdapterInboundResultProcessor.with(
                reading(new AdapterResultFrame("corr-1", "payload", null, "frame-1")),
                sink,
                (frame, result) -> {
                    LinkedHashMap<String, String> diagnostics = new LinkedHashMap<>();
                    diagnostics.put("traceId", "trace-1");
                    return diagnostics;
                }
        );
    }

    private AdapterResultFrameReader<String> reading(AdapterResultFrame result) {
        return new AdapterResultFrameReader<>() {
            @Override
            public boolean isResultFrame(String frame) {
                return true;
            }

            @Override
            public AdapterResultFrame read(String resultFrame) {
                return result;
            }
        };
    }

    private AdapterResultFrameReader<String> rejectingReader() {
        return new AdapterResultFrameReader<>() {
            @Override
            public boolean isResultFrame(String frame) {
                return true;
            }

            @Override
            public AdapterResultFrame read(String resultFrame) {
                throw new IllegalArgumentException("bad frame");
            }
        };
    }
}
