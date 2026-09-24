package com.xa.mass.integration.workerloadedrecovery;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadedRecoveryDiagnosticsTest {
    @TempDir Path temporary;

    @Name("xa.mass.WorkerEvidenceDiagnostic") @StackTrace(false)
    static final class Evidence extends Event {
        public long batchId = 1;
        public String workerKey = "a".repeat(64);
        public String stage = "SELECTED";
        public String eventName = "";
        public String kind = "AVAILABLE";
        public long observedAtMillis = 1000;
        public long processedAtMillis = 1200;
        public String payload = "PRIVATE_PAYLOAD_SENTINEL";
        public String workerId = "PRIVATE_ID_SENTINEL";
    }

    @Test void offlineExportKeepsOnlyApprovedFields() throws Exception {
        Path recordingPath = temporary.resolve("private.jfr");
        try (var recording = new Recording()) {
            recording.enable(Evidence.class);
            recording.start();
            new Evidence().commit();
            recording.stop();
            recording.dump(recordingPath);
        }
        Path output = temporary.resolve("evidence.jsonl");
        LoadedRecoveryDiagnostics.main(new String[]{recordingPath.toString(), output.toString()});
        var lines = Files.readAllLines(output);
        assertEquals(1, lines.size());
        var row = com.xa.mass.workerdelivery.json.Jsons.parseObject(lines.getFirst());
        assertEquals("a".repeat(64), row.get("workerKey"));
        assertEquals("SELECTED", row.get("stage"));
        assertTrue(((Number) row.get("javaThreadId")).longValue() > 0);
        assertTrue(((Number) row.get("eventAtEpochNanos")).longValue() > 0);
        assertFalse(lines.getFirst().contains("PRIVATE_"));
        assertFalse(row.containsKey("eventThread"));
    }
}
