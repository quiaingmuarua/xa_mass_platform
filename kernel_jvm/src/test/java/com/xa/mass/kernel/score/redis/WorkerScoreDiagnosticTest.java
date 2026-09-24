package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity.*;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerScoreDiagnosticTest {
    @TempDir Path temporary;

    @Test void disabledPathDoesNotInspectInputs() {
        assertEquals(0, WorkerScoreDiagnostic.start());
        assertDoesNotThrow(() -> WorkerScoreDiagnostic.batch(0, null, null, null, null));
        assertDoesNotThrow(() -> WorkerScoreDiagnostic.single(0, null, null, null));
    }

    @Test void recordingKeepsEveryReplyAndExactEvidenceWithoutPrivateInputs() throws Exception {
        Path path = temporary.resolve("worker-score.jfr");
        var results = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        results.put("worker-private", new WorkerScoreTransitionResult(TRANSITIONED,
                -WorkerScoreEncoding.absoluteScore(123, 1)));
        results.put("worker-stale", new WorkerScoreTransitionResult(STALE, null));
        results.put("worker-invalid", new WorkerScoreTransitionResult(STALE, 0L));
        try (var recording = new Recording()) {
            recording.enable(WorkerScoreDiagnostic.RecordedEvent.class);
            recording.start();
            long started = WorkerScoreDiagnostic.start();
            assertNotEquals(0, started);
            WorkerScoreDiagnostic.batch(started, "Worker score polarity", results,
                    Map.of("worker-private", 12_345L, "worker-stale", 12_346L), RECOVERY_RECHECK);
            WorkerScoreDiagnostic.single(started, "compare_and_set", "worker-noop",
                    new WorkerScoreTransitionResult(NOOP, 456L));
            assertDoesNotThrow(() -> WorkerScoreDiagnostic.batch(started, null, null, null, null));
            assertDoesNotThrow(() -> WorkerScoreDiagnostic.single(started, null, null, null));
            recording.stop();
            recording.dump(path);
        }
        var events = RecordingFile.readAllEvents(path);
        assertEquals(4, events.size());
        var changed = events.stream().filter(e -> e.getString("status").equals("TRANSITIONED"))
                .findFirst().orElseThrow();
        assertEquals("89d1b4ea6df4bbbb9bc6e63212e176331c24f7f9012a90b01132a918cecd1cfd",
                changed.getString("workerKey"));
        assertEquals("Worker score polarity", changed.getString("operation"));
        assertEquals("RECOVERY_RECHECK", changed.getString("polarity"));
        assertEquals(12_300, changed.getLong("timeMillis"));
        assertEquals(1, changed.getInt("mark"));
        assertEquals(12_345, changed.getLong("evidenceMillis"));
        assertEquals("RECOVERY_RECHECK", changed.getString("targetPolarity"));
        var stale = events.stream().filter(e -> e.getString("status").equals("STALE")).toList();
        assertEquals(2, stale.size());
        stale.forEach(event -> {
            assertEquals("", event.getString("polarity"));
            assertEquals(-1, event.getLong("timeMillis"));
            assertEquals(-1, event.getInt("mark"));
        });
        for (var event : events) {
            assertEquals(64, event.getString("workerKey").length());
            assertTrue(event.getLong("endedEpochMillis") >= event.getLong("startedEpochMillis"));
            for (String forbidden : List.of("payload", "workerId", "taskId", "messageId", "result", "score"))
                assertFalse(event.hasField(forbidden));
        }
    }
}
