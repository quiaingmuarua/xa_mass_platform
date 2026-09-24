package com.xa.mass.integration.workerloadedrecovery;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;

/** Offline streaming whitelist; no Runtime API, environment, payload or raw identity export. */
public final class LoadedRecoveryDiagnostics {
    private static final Map<String, List<String>> FIELDS = Map.of(
            "xa.mass.WorkerScoreDiagnostic", List.of("workerKey", "operation", "startedEpochMillis",
                    "endedEpochMillis", "status", "polarity", "timeMillis", "mark", "evidenceMillis", "targetPolarity"),
            "xa.mass.WorkerEvidenceDiagnostic", List.of("batchId", "workerKey", "stage", "eventName", "kind",
                    "observedAtMillis", "processedAtMillis"),
            "jdk.DataLoss", List.of("amount"));

    private LoadedRecoveryDiagnostics() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("recording and output paths required");
        Path input = Path.of(args[0]);
        if (Files.size(input) > 256L * 1024 * 1024) throw new IllegalArgumentException("Recording exceeds bound");
        try (var recording = new RecordingFile(input); var output = Files.newBufferedWriter(Path.of(args[1]))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                String name = event.getEventType().getName();
                var fields = FIELDS.get(name);
                if (fields == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventType", name);
                var timestamp = event.getStartTime();
                row.put("eventAtEpochNanos", Math.addExact(Math.multiplyExact(timestamp.getEpochSecond(), 1_000_000_000L), timestamp.getNano()));
                row.put("javaThreadId", event.getThread() == null ? 0 : event.getThread().getJavaThreadId());
                for (String field : fields) row.put(field, event.getValue(field));
                output.write(Jsons.toJson(row));
                output.newLine();
            }
        }
    }
}
