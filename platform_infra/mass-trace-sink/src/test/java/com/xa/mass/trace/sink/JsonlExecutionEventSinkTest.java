package com.xa.mass.trace.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonlExecutionEventSinkTest {

    @TempDir
    Path tempDir;

    private JsonlExecutionEventSink sink;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sink = new JsonlExecutionEventSink(tempDir.toString(), 1024, 10_000);
    }

    @AfterEach
    void tearDown() {
        sink.close();
    }

    // 鈹€鈹€ helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    private List<String> drainLines() throws IOException, InterruptedException {
        sink.close();
        Thread.sleep(50);
        List<Path> files = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .sorted()
                .toList();
        assertFalse(files.isEmpty(), "No JSONL file was created");
        return Files.readAllLines(files.get(0));
    }

    private JsonNode firstLine() throws Exception {
        List<String> lines = drainLines();
        assertFalse(lines.isEmpty(), "No events written");
        return mapper.readTree(lines.get(0));
    }

    // 鈹€鈹€ tests 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    @Test
    void taskStatusTransition_schemaAndTopLevelFields() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .node("srv-1", "eng-1", null)
                .identity(b -> b.taskId("t-abc"))
                .transition("READY", "RUNNING", null)
                .build());

        JsonNode n = firstLine();

        assertEquals("xa.mass.execution-event.v1", n.get("schema").asText());
        assertFalse(n.get("eventId").asText().isBlank(), "eventId must not be blank");
        assertEquals("TASK_STATUS_TRANSITION", n.get("eventType").asText());
        assertEquals("TASK", n.get("category").asText());
        assertEquals("INFO", n.get("severity").asText());
        assertTrue(n.get("ts").asLong() > 0, "ts must be positive epoch millis");
        assertFalse(n.get("tsIso").asText().isBlank(), "tsIso must not be blank");
    }

    @Test
    void taskStatusTransition_transitionBlock() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .node("srv-1", "eng-1", null)
                .identity(b -> b.taskId("t-abc"))
                .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                .build());

        JsonNode n = firstLine();

        JsonNode tr = n.get("transition");
        assertNotNull(tr, "transition block must be present");
        assertEquals("RUNNING", tr.get("src").asText());
        assertEquals("TERMINAL", tr.get("dst").asText());
        assertEquals("ALL_MESSAGES_SUCCEEDED", tr.get("reason").asText());
    }

    @Test
    void taskStatusTransition_identityTaskId() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .node("srv-1", "eng-1", null)
                .identity(b -> b.taskId("t-xyz"))
                .transition("READY", "RUNNING", null)
                .build());

        JsonNode n = firstLine();

        JsonNode id = n.get("identity");
        assertNotNull(id, "identity block must be present");
        assertEquals("t-xyz", id.get("taskId").asText());
    }

    @Test
    void taskStatusTransition_nodeBlock() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .node("server-1", "engine-1", null)
                .identity(b -> b.taskId("t-1"))
                .transition("NEW", "READY", null)
                .build());

        JsonNode n = firstLine();

        JsonNode node = n.get("node");
        assertNotNull(node, "node block must be present");
        assertEquals("server-1", node.get("serverNodeId").asText());
        assertEquals("engine-1", node.get("engineNodeId").asText());
        assertTrue(node.get("adapterNodeId").isNull());
    }

    @Test
    void taskMsgRetryReset_defaultSeverityIsWarn() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_WORK_RETRY_RESET)
                .identity(b -> b.messageId("m-1"))
                .build());

        JsonNode n = firstLine();
        assertEquals("WARN", n.get("severity").asText());
        assertEquals("MSG", n.get("category").asText());
    }

    @Test
    void leaseExpired_defaultSeverityIsWarn() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.LEASE_EXPIRED)
                .identity(b -> b.leaseToken("lt-1"))
                .build());

        JsonNode n = firstLine();
        assertEquals("WARN", n.get("severity").asText());
        assertEquals("LEASE", n.get("category").asText());
    }

    @Test
    void severityOverride_builderOverridesDefault() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .severity(EventSeverity.WARN)
                .identity(b -> b.taskId("t-1"))
                .build());

        JsonNode n = firstLine();
        assertEquals("WARN", n.get("severity").asText());
    }

    @Test
    void attrsSerializedAsJsonObject() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .identity(b -> b.taskId("t-1"))
                .attrs(Map.of("totalMessages", 10, "successCount", 10, "failedCount", 0))
                .build());

        JsonNode n = firstLine();

        JsonNode attrs = n.get("attrs");
        assertNotNull(attrs, "attrs must be present");
        assertTrue(attrs.isObject(), "attrs must be a JSON object");
        assertEquals(10, attrs.get("totalMessages").asInt());
    }

    @Test
    void nullFieldsIncludedInJson() throws Exception {
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.WORKER_ONLINE)
                .identity(b -> b.workerId("w-1"))
                .build());

        JsonNode n = firstLine();

        // transition is null for non-transition events; it should be present as null or absent
        // per schema: null fields are included as null
        assertTrue(n.has("traceId"), "traceId field must be present");
        assertTrue(n.get("traceId").isNull(), "traceId must be null");
    }

    @Test
    void rotate_createsNewFileAfterThreshold() throws Exception {
        JsonlExecutionEventSink rotatingSink =
                new JsonlExecutionEventSink(tempDir.toString(), 1024, 2);

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            rotatingSink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_ONLINE)
                    .identity(b -> b.workerId("w-" + idx))
                    .build());
        }

        rotatingSink.close();
        Thread.sleep(100);

        long fileCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .count();
        assertTrue(fileCount >= 2, "Expected at least 2 rotated files, got " + fileCount);
    }

    @Test
    void dropCountIncrements_whenQueueFull_dropPolicy() throws InterruptedException {
        // Explicitly uses DROP policy (the default); verifies droppedCount increments.
        JsonlExecutionEventSink tightSink = new JsonlExecutionEventSink(
                tempDir.toString(), 1, 10_000, OverflowPolicy.DROP, 5_000);

        for (int i = 0; i < 100; i++) {
            final int idx = i;
            tightSink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_ONLINE)
                    .identity(b -> b.workerId("w-" + idx))
                    .build());
        }

        tightSink.close();
        Thread.sleep(50);
        assertTrue(tightSink.getDroppedCount() > 0,
                "Expected some drops with DROP policy and queue capacity 1");
    }

    @Test
    void overflowFallbackSync_writesEventSynchronously() throws Exception {
        // Queue capacity 1 with FALLBACK_SYNC: events that don't fit the queue are
        // written synchronously on the caller thread and must appear in the output file.
        JsonlExecutionEventSink fallbackSink = new JsonlExecutionEventSink(
                tempDir.toString(), 1, 10_000, OverflowPolicy.FALLBACK_SYNC, 5_000);

        // Emit enough events to guarantee at least one hits the FALLBACK_SYNC path.
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            fallbackSink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.WORKER_ONLINE)
                    .identity(b -> b.workerId("w-" + idx))
                    .build());
        }

        fallbackSink.close();
        Thread.sleep(50);

        List<Path> files = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .toList();
        assertFalse(files.isEmpty(), "No JSONL file was created");

        long totalLines = files.stream()
                .mapToLong(f -> {
                    try { return Files.readAllLines(f).size(); } catch (IOException e) { return 0; }
                })
                .sum();
        // All 20 events must be persisted (queued or fallback-sync path).
        assertEquals(20, totalLines, "FALLBACK_SYNC must write all events to file");
    }

    @Test
    void shutdownDrain_doesNotLoseEnqueuedEvents() throws Exception {
        // Emit an event and immediately close - the event must not be lost.
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                .identity(b -> b.taskId("t-drain"))
                .transition("READY", "RUNNING", null)
                .build());

        List<String> lines = drainLines();
        assertFalse(lines.isEmpty(), "Event enqueued before close must appear in the output file");

        JsonNode n = mapper.readTree(lines.get(0));
        assertEquals("t-drain", n.get("identity").get("taskId").asText());
    }

    @Test
    void emitAfterClose_silentlyDropped() throws Exception {
        // Emit one event, close, then emit another - the second must not appear in the file.
        sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.WORKER_ONLINE)
                .identity(b -> b.workerId("w-before-close"))
                .build());

        List<String> lines = drainLines();  // calls sink.close() internally
        int countBeforeSecondEmit = lines.size();

        // Must not throw, and must not write anything new.
        assertDoesNotThrow(() -> sink.emit(ExecutionEvent.builder()
                .eventType(ExecutionEventType.WORKER_ONLINE)
                .identity(b -> b.workerId("w-after-close"))
                .build()));

        Thread.sleep(50);
        List<Path> files = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .sorted()
                .toList();
        long totalLines = files.stream()
                .mapToLong(f -> {
                    try { return Files.readAllLines(f).size(); } catch (IOException e) { return 0; }
                })
                .sum();
        assertEquals(countBeforeSecondEmit, totalLines,
                "emit() after close must not write any additional events");
    }
}

