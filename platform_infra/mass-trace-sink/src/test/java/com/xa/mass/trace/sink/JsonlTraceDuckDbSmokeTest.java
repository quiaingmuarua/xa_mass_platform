package com.xa.mass.trace.sink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTraceDuckDbSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void jsonlOutputCanBeQueriedByDuckDbForLocalTraceDiagnosis() throws Exception {
        JsonlExecutionEventSink sink = new JsonlExecutionEventSink(tempDir.toString(), 128, 10_000);
        try {
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                    .traceId("trace-local-1")
                    .identity(b -> b.taskId("task-local-1"))
                    .transition("READY", "RUNNING", "assignment-success")
                    .attrs(Map.of("reason", "first lease acquired", "source", "TaskManager"))
                    .build());
            sink.emit(ExecutionEvent.builder()
                    .eventType(ExecutionEventType.CALLBACK_ACCEPTED)
                    .traceId("trace-local-1")
                    .identity(b -> b.taskId("task-local-1").messageId("msg-local-1").attemptId("attempt-local-1"))
                    .outcome(true, null, "worker callback accepted")
                    .attrs(Map.of("reason", "result-ingested", "source", "TaskRuntimeServingLane"))
                    .build());
        } finally {
            sink.close();
        }

        List<Path> files = Files.list(tempDir)
                .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                .toList();
        assertFalse(files.isEmpty(), "Expected trace JSONL output");

        String jsonlPath = files.get(0).toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                    SELECT
                        eventType,
                        traceId,
                        identity.taskId AS taskId,
                        identity.messageId AS messageId,
                        attrs.reason AS reason,
                        attrs.source AS source
                    FROM read_ndjson('%s')
                    WHERE identity.taskId = 'task-local-1'
                    ORDER BY ts
                    """.formatted(jsonlPath))) {
                assertTrue(result.next(), "Expected first trace event");
                assertEquals("TASK_STATUS_TRANSITION", result.getString("eventType"));
                assertEquals("trace-local-1", result.getString("traceId"));
                assertEquals("task-local-1", result.getString("taskId"));
                assertEquals("first lease acquired", result.getString("reason"));
                assertEquals("TaskManager", result.getString("source"));

                assertTrue(result.next(), "Expected second trace event");
                assertEquals("CALLBACK_ACCEPTED", result.getString("eventType"));
                assertEquals("msg-local-1", result.getString("messageId"));
                assertEquals("result-ingested", result.getString("reason"));
                assertEquals("TaskRuntimeServingLane", result.getString("source"));

                assertFalse(result.next(), "Expected exactly two trace events for the task");
            }
        }
    }
}
