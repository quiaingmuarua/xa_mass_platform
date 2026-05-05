package com.xa.mass.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.trace.api.ExecutionEvent;
import com.xa.mass.trace.api.ExecutionEventType;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import com.xa.mass.trace.sink.JsonlSinkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonlExecutionEventSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emittedEventIsWrittenToFile() throws Exception {
        JsonlSinkConfig config = JsonlSinkConfig.defaults(tempDir);
        ExecutionEvent event = ExecutionEvent.builder(ExecutionEventType.TASK_STATUS_CHANGED)
                .ts(Instant.parse("2026-05-05T21:00:00.123Z"))
                .taskId("t-001")
                .src("READY")
                .dst("RUNNING")
                .build();

        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config)) {
            sink.emit(event);
        }

        Path file = tempDir.resolve("events-current.jsonl");
        assertTrue(Files.exists(file), "events-current.jsonl should exist");
        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size(), "exactly one line should be written");

        JsonNode node = MAPPER.readTree(lines.get(0));
        assertEquals(1, node.get("v").asInt());
        assertEquals("TASK_STATUS_CHANGED", node.get("eventType").asText());
        assertEquals("READY", node.get("src").asText());
        assertEquals("RUNNING", node.get("dst").asText());
        assertEquals("t-001", node.get("taskId").asText());
        assertFalse(node.has("reason") && !node.get("reason").isNull(),
                "reason should be null when not set");
    }

    @Test
    void schemaVersionIsAlways1() throws Exception {
        JsonlSinkConfig config = JsonlSinkConfig.defaults(tempDir);
        ExecutionEvent event = ExecutionEvent.builder(ExecutionEventType.WORKER_ONLINE)
                .workerId("w-001")
                .adapterId("polling")
                .build();

        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config)) {
            sink.emit(event);
        }

        List<String> lines = Files.readAllLines(tempDir.resolve("events-current.jsonl"));
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertEquals(1, node.get("v").asInt(), "v must always be 1");
    }

    @Test
    void multipleEventsAreWrittenAsMultipleLines() throws Exception {
        JsonlSinkConfig config = JsonlSinkConfig.defaults(tempDir);

        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config)) {
            sink.emit(ExecutionEvent.builder(ExecutionEventType.MSG_DISPATCH_SENT)
                    .taskId("t-001").messageId("m-001").workerId("w-001").adapterId("polling").build());
            sink.emit(ExecutionEvent.builder(ExecutionEventType.MSG_STATUS_CHANGED)
                    .taskId("t-001").messageId("m-001").src("INIT").dst("SUCCESS").build());
            sink.emit(ExecutionEvent.builder(ExecutionEventType.TASK_STATUS_CHANGED)
                    .taskId("t-001").src("RUNNING").dst("TERMINAL").reason("ALL_MESSAGES_SUCCEEDED").build());
        }

        List<String> lines = Files.readAllLines(tempDir.resolve("events-current.jsonl"));
        assertEquals(3, lines.size(), "three events should produce three lines");
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            assertEquals(1, node.get("v").asInt());
        }
    }

    @Test
    void fileRotatesWhenSizeExceeded() throws Exception {
        // Use a tiny rotate threshold so a single event triggers rotation
        JsonlSinkConfig config = new JsonlSinkConfig(tempDir, 1L, 8192);

        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config)) {
            sink.emit(ExecutionEvent.builder(ExecutionEventType.LEASE_EXPIRED)
                    .taskId("t-001").messageId("m-001").workerId("w-001").build());
            sink.emit(ExecutionEvent.builder(ExecutionEventType.LEASE_EXPIRED)
                    .taskId("t-002").messageId("m-002").workerId("w-002").build());
        }

        // After close, at least one rotated file should exist
        long rotatedFiles = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("events-") &&
                             !p.getFileName().toString().equals("events-current.jsonl"))
                .count();
        assertTrue(rotatedFiles >= 1, "at least one rotated file should exist");
    }

    @Test
    void dropCountIncreasesWhenQueueFull() throws IOException {
        // Capacity of 1 so the second emit gets dropped
        JsonlSinkConfig config = new JsonlSinkConfig(tempDir, 64 * 1024 * 1024L, 1);

        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config)) {
            // Flood many events — some will be dropped
            for (int i = 0; i < 1000; i++) {
                sink.emit(ExecutionEvent.builder(ExecutionEventType.WORKER_OFFLINE)
                        .workerId("w-" + i).adapterId("polling").reason("shutdown").build());
            }
            assertTrue(sink.droppedCount() > 0, "drops should be recorded when queue is full");
        }
    }

    @Test
    void extraFieldIsWrittenAsObject() throws Exception {
        JsonlSinkConfig config = JsonlSinkConfig.defaults(tempDir);
        ExecutionEvent event = ExecutionEvent.builder(ExecutionEventType.MSG_RETRY_SCHEDULED)
                .taskId("t-001").messageId("m-001").retryCount(2)
                .extra(Map.of("errorCode", "TIMEOUT"))
                .build();

        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config)) {
            sink.emit(event);
        }

        List<String> lines = Files.readAllLines(tempDir.resolve("events-current.jsonl"));
        JsonNode node = MAPPER.readTree(lines.get(0));
        assertTrue(node.has("extra"), "extra field should be present");
        assertTrue(node.get("extra").isObject(), "extra should be a JSON object");
        assertEquals("TIMEOUT", node.get("extra").get("errorCode").asText());
        assertEquals(2, node.get("retryCount").asInt());
    }

    @Test
    void emitAfterCloseIsIgnored() {
        JsonlSinkConfig config = JsonlSinkConfig.defaults(tempDir);
        JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config);
        sink.close();

        // Should not throw
        sink.emit(ExecutionEvent.builder(ExecutionEventType.WORKER_ONLINE)
                .workerId("w-001").adapterId("polling").build());
    }
}
