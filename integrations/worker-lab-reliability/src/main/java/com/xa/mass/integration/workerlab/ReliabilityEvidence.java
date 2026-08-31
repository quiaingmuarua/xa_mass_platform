package com.xa.mass.integration.workerlab;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ReliabilityEvidence {

    static final String SUMMARY_FILE =
            "worker-lab-reliability-summary.json";
    static final String TIMELINE_FILE =
            "worker-lab-reliability-timeline.jsonl";

    private final String proofId;
    private final Path directory;
    private final Path timeline;
    private final Instant startedAt;

    ReliabilityEvidence(String proofId, Path directory) throws IOException {
        this.proofId = requireNonBlank(proofId, "proofId");
        this.directory = Objects.requireNonNull(
                directory,
                "directory"
        ).toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        timeline = this.directory.resolve(TIMELINE_FILE);
        Files.deleteIfExists(timeline);
        startedAt = Instant.now();
    }

    synchronized void record(
            String phase,
            String action,
            Map<String, ?> facts
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("observedAt", Instant.now().toString());
        row.put("phase", requireNonBlank(phase, "phase"));
        row.put("action", requireNonBlank(action, "action"));
        putFacts(row, facts);
        try {
            Files.writeString(
                    timeline,
                    Jsons.toJson(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not append Worker Lab reliability timeline",
                    error
            );
        }
    }

    synchronized void writeSummary(
            String status,
            Map<String, ?> facts
    ) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("proofId", proofId);
        value.put("status", requireNonBlank(status, "status"));
        value.put("startedAt", startedAt.toString());
        value.put("completedAt", Instant.now().toString());
        value.put("timelineFile", TIMELINE_FILE);
        putFacts(value, facts);

        Path target = directory.resolve(SUMMARY_FILE);
        Path temporary = Files.createTempFile(
                directory,
                ".worker-lab-reliability-summary-",
                ".tmp"
        );
        try {
            Files.writeString(
                    temporary,
                    Jsons.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path summaryFile() {
        return directory.resolve(SUMMARY_FILE);
    }

    private static void putFacts(
            Map<String, Object> target,
            Map<String, ?> facts
    ) {
        Objects.requireNonNull(facts, "facts").forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "Evidence fact names must be non-blank"
                );
            }
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("payload")) {
                throw new IllegalArgumentException(
                        "Evidence must not record payload"
                );
            }
            if (target.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate evidence field: " + key
                );
            }
        });
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
