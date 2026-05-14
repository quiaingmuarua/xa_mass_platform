package com.xa.mass.trace.operator;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceOperatorServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private final TraceOperatorService operatorService = new TraceOperatorService();

    @Test
    void timelineReadsCanonicalSinkOutputWithoutCliAdapter() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        TraceTimelineResponse response = operatorService.timeline(
                new TraceTimelineRequest(tempDir.toString(), "task-1", null, null));

        assertEquals("task-1", response.taskId());
        assertNull(response.messageId());
        assertEquals(3, response.count());
        assertTrue(containsTimelineEvent(response, "TASK_STATUS_TRANSITION", "READY", "RUNNING"));
        assertTrue(containsTimelineEvent(response, "TASK_TERMINAL_CLOSED", "RUNNING", "TERMINAL"));
    }

    @Test
    void statsAggregatesCanonicalSinkOutputWithoutCliAdapter() throws Exception {
        writeDuplicateReplayTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        TraceStatsResponse response = operatorService.stats(
                new TraceStatsRequest(tempDir.toString(), "task-replay", null, null, null));

        assertEquals("task-replay", response.taskId());
        assertEquals(4, response.count());
        assertEquals("CALLBACK_ACCEPTED", response.rows().getFirst().eventType());
        assertEquals(2, response.rows().getFirst().count());
    }

    @Test
    void validateRejectsMalformedRowsWithoutCliAdapter() throws Exception {
        Path broken = tempDir.resolve("broken.jsonl");
        Files.writeString(broken, """
                {"schema":"xa.mass.execution-event.v1","eventType":"TASK_STATUS_TRANSITION","category":"TASK","severity":"INFO","ts":1,"tsIso":"2026-05-14T00:00:00Z","identity":{"taskId":"task-bad"}}
                {"schema":"xa.mass.execution-event.v1","eventType":"NOT_A_REAL_EVENT","category":"TASK","severity":"INFO","ts":2,"tsIso":"2026-05-14T00:00:01Z","identity":{"taskId":"task-bad"}}
                not-json
                """);

        TraceValidateResponse response = operatorService.validate(new TraceValidateRequest(broken.toString()));

        assertFalse(response.valid());
        assertEquals(1, response.source().fileCount());
        assertTrue(response.issues().size() >= 2);
    }

    @Test
    void analyzeRunsBuiltInScenarioAgainstCanonicalSinkOutput() throws Exception {
        writeDuplicateReplayTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        TraceAnalyzeResponse response = operatorService.analyze(
                new TraceAnalyzeRequest(tempDir.toString(), "duplicate-callback-replay", "task-replay"));

        assertTrue(response.ok());
        assertEquals("duplicate-callback-replay", response.scenarioId());
        assertEquals("task-replay", response.taskId());
        assertEquals(1L, response.eventTypeCounts().get("CALLBACK_IGNORED_DUPLICATE"));
    }

    @Test
    void scenarioRegistryStaysAvailableThroughOperatorService() {
        assertTrue(operatorService.scenarioIds().contains("single-message-success"));
        assertTrue(operatorService.scenarioIds().contains("duplicate-callback-replay"));
    }

    private void writeCanonicalTrace(Path outputDir) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-1")
                    .identity(identity -> identity.taskId("task-1"))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "assignment-success", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-1")
                    .identity(identity -> identity.taskId("task-1").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "result-ingested", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .traceId("trace-1")
                    .identity(identity -> identity.taskId("task-1"))
                    .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                    .attrs(Map.of("reason", "all work converged", "source", "TaskManager", "terminalReason", "ALL_MESSAGES_SUCCEEDED"))
                    .build());
        }
    }

    private void writeDuplicateReplayTrace(Path outputDir) throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(outputDir.toString(), 128, 10_000)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay"))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "assignment-success", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay").messageId("msg-2").attemptId("attempt-2"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay"))
                    .transition("RUNNING", "TERMINAL", "ALL_MESSAGES_SUCCEEDED")
                    .attrs(Map.of("reason", "all work converged", "source", "TaskManager", "terminalReason", "ALL_MESSAGES_SUCCEEDED"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_IGNORED_DUPLICATE)
                    .traceId("trace-replay")
                    .identity(identity -> identity.taskId("task-replay").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "duplicate callback suppressed")
                    .attrs(Map.of("reason", "duplicate callback suppressed", "source", "TaskManager"))
                    .build());
        }
    }

    private void awaitJsonlFiles(Path outputDir, int minFiles) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            try (var files = Files.list(outputDir)) {
                long count = files
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .count();
                if (count >= minFiles) {
                    return;
                }
            }
            Thread.sleep(25L);
        }
        try (var files = Files.list(outputDir)) {
            long count = files
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .count();
            assertTrue(count >= minFiles, "Expected at least " + minFiles + " trace jsonl files but found " + count);
        }
    }

    private boolean containsTimelineEvent(TraceTimelineResponse response, String eventType, String src, String dst) {
        return response.events().stream().anyMatch(event ->
                eventType.equals(event.eventType())
                        && src.equals(event.src())
                        && dst.equals(event.dst()));
    }
}
