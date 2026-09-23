package com.xa.mass.server.task.call;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskRpcStageEventTest {
    @TempDir Path temporary;

    @Test void disabledPathIsInertAndSamplingHasTheSharedFixedVector() {
        assertEquals(0, TaskRpcStageEvent.start());
        assertDoesNotThrow(() -> TaskRpcStageEvent.items(0, "SUBMISSION", null, null, 0, false));
        assertEquals("40a73f95e3436b2db191dded2ae14e8764ead7378784e6d80c8b933320f16f0a",
                TaskRpcStageEvent.sampleKey("task", "item-87"));
        assertNull(TaskRpcStageEvent.sampleKey("task", "item-0"));
    }

    @Test void recordingExportsOneAggregateAndOneSampleWithoutPrivateInputs() throws Exception {
        Path path = temporary.resolve("stage.jfr");
        try (var recording = new Recording()) {
            recording.enable(TaskRpcStageEvent.class);
            recording.start();
            long started = TaskRpcStageEvent.start();
            assertNotEquals(0, started);
            TaskRpcStageEvent.items(started, "SUBMISSION", "task", List.of("item-87"), 1, false);
            assertDoesNotThrow(() -> TaskRpcStageEvent.items(started, "SUBMISSION", null, null, 0, false));
            recording.stop();
            recording.dump(path);
        }
        var events = RecordingFile.readAllEvents(path);
        assertEquals(2, events.size());
        assertEquals(1, events.stream().filter(e -> e.getString("key").isEmpty()).count());
        assertEquals(1, events.stream().filter(e -> e.getString("key").length() == 64).count());
        for (var event : events) {
            assertTrue(event.getLong("endedNanos") >= event.getLong("startedNanos"));
            for (String forbidden : List.of("payload", "workerId", "taskId", "messageId", "result", "score"))
                assertFalse(event.hasField(forbidden));
        }
    }
}

