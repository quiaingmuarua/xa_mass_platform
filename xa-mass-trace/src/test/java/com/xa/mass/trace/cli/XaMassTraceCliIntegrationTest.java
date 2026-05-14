package com.xa.mass.trace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaMassTraceCliIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void timelineReadsCanonicalJsonlProducedBySink() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("timeline", "--path", tempDir.toString(), "--task-id", "task-1", "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertEquals("task-1", root.get("taskId").asText());
        assertEquals(3, root.get("count").asInt());
        JsonNode first = root.get("events").get(0);
        assertEquals("TASK_STATUS_TRANSITION", first.get("eventType").asText());
        assertEquals("READY", first.get("src").asText());
        assertEquals("RUNNING", first.get("dst").asText());
        assertTrue(result.stderr.isBlank());
    }

    @Test
    void statsAggregatesAcrossRotatedFilesProducedBySink() throws Exception {
        try (JsonlExecutionEventSink sink = new JsonlExecutionEventSink(tempDir.toString(), 128, 1)) {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-a")
                    .identity(identity -> identity.taskId("task-2"))
                    .transition("READY", "RUNNING", "leased")
                    .attrs(Map.of("reason", "leased", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-a")
                    .identity(identity -> identity.taskId("task-2").messageId("msg-1").attemptId("attempt-1"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-a")
                    .identity(identity -> identity.taskId("task-2").messageId("msg-2").attemptId("attempt-2"))
                    .outcome(true, null, "accepted")
                    .attrs(Map.of("reason", "accepted", "source", "TaskResultService"))
                    .build());
        }
        awaitJsonlFiles(tempDir, 2);

        CommandResult result = run("stats", "--path", tempDir.toString(), "--task-id", "task-2", "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertEquals(2, root.get("count").asInt());
        JsonNode first = root.get("rows").get(0);
        assertEquals("CALLBACK_ACCEPTED", first.get("eventType").asText());
        assertEquals(2, first.get("count").asInt());
    }

    @Test
    void validateAcceptsCanonicalSinkOutputAndCountsRowsThroughDuckDb() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("validate", "--path", tempDir.toString(), "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("valid").asBoolean());
        assertEquals(3, root.get("validRows").asInt());
        assertEquals(0, root.get("issues").size());
    }

    @Test
    void validateRejectsMalformedOrNonCanonicalRows() throws Exception {
        Path broken = tempDir.resolve("broken.jsonl");
        Files.writeString(broken, """
                {"schema":"xa.mass.execution-event.v1","eventType":"TASK_STATUS_TRANSITION","category":"TASK","severity":"INFO","ts":1,"tsIso":"2026-05-14T00:00:00Z","identity":{"taskId":"task-bad"}}
                {"schema":"xa.mass.execution-event.v1","eventType":"NOT_A_REAL_EVENT","category":"TASK","severity":"INFO","ts":2,"tsIso":"2026-05-14T00:00:01Z","identity":{"taskId":"task-bad"}}
                {"schema":"xa.mass.execution-event.v1","category":"TASK","severity":"INFO","ts":3,"tsIso":"2026-05-14T00:00:02Z","identity":{"taskId":"task-bad"}}
                not-json
                """);

        CommandResult result = run("validate", "--path", broken.toString(), "--json");

        assertEquals(3, result.exitCode);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertFalse(root.get("valid").asBoolean());
        assertTrue(root.get("issues").size() >= 3);
        assertTrue(result.stderr.isBlank());
    }

    @Test
    void analyzeRecognizesSingleMessageSuccessScenarioFromCanonicalTrace() throws Exception {
        writeCanonicalTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("analyze",
                "--path", tempDir.toString(),
                "--scenario", "single-message-success",
                "--task-id", "task-1",
                "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("ok").asBoolean());
        assertEquals("single-message-success", root.get("scenarioId").asText());
        assertEquals("task-1", root.get("taskId").asText());
        assertTrue(result.stderr.isBlank());
    }

    @Test
    void analyzeRecognizesDuplicateCallbackReplayScenarioFromCanonicalTrace() throws Exception {
        writeDuplicateReplayTrace(tempDir);
        awaitJsonlFiles(tempDir, 1);

        CommandResult result = run("analyze",
                "--path", tempDir.toString(),
                "--scenario", "duplicate-callback-replay",
                "--task-id", "task-replay",
                "--json");

        assertEquals(0, result.exitCode, "stderr=" + result.stderr + System.lineSeparator() + "stdout=" + result.stdout);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertTrue(root.get("ok").asBoolean());
        assertEquals("duplicate-callback-replay", root.get("scenarioId").asText());
        assertEquals(1, root.get("eventTypeCounts").get("CALLBACK_IGNORED_DUPLICATE").asInt());
    }

    @Test
    void analyzeFailsWhenScenarioExpectationsAreNotMet() throws Exception {
        Path broken = tempDir.resolve("broken-scenario.jsonl");
        Files.writeString(broken, """
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-1","eventType":"TASK_STATUS_TRANSITION","category":"TASK","severity":"INFO","ts":1,"tsIso":"2026-05-14T00:00:00Z","traceId":"trace-x","identity":{"taskId":"task-missing"},"transition":{"src":"READY","dst":"RUNNING","reason":"leased"},"outcome":{},"attrs":{"source":"TaskManager","reason":"leased"}}
                {"schema":"xa.mass.execution-event.v1","eventId":"evt-2","eventType":"TASK_TERMINAL_CLOSED","category":"TASK","severity":"INFO","ts":2,"tsIso":"2026-05-14T00:00:01Z","traceId":"trace-x","identity":{"taskId":"task-missing"},"transition":{"src":"RUNNING","dst":"TERMINAL","reason":"all done"},"outcome":{},"attrs":{"source":"TaskManager","reason":"all done","terminalReason":"ALL_MESSAGES_SUCCEEDED"}}
                """);

        CommandResult result = run("analyze",
                "--path", broken.toString(),
                "--scenario", "single-message-success",
                "--task-id", "task-missing",
                "--json");

        assertEquals(4, result.exitCode);
        JsonNode root = objectMapper.readTree(result.stdout);
        assertFalse(root.get("ok").asBoolean());
        assertTrue(root.get("issues").size() >= 1);
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

    private CommandResult run(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = com.xa.mass.trace.cli.XaMassTraceCli.run(
                args,
                new PrintStream(stdout, true),
                new PrintStream(stderr, true)
        );
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
