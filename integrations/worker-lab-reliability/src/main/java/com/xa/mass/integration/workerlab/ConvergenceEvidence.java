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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class ConvergenceEvidence {

    private final String proofId;
    private final String lane;
    private final Path directory;
    private final Path timeline;
    private final Instant startedAt;

    static ConvergenceEvidence begin(
            String proofId,
            String lane,
            Path directory
    ) throws IOException {
        return new ConvergenceEvidence(
                proofId,
                lane,
                directory,
                Instant.now(),
                true
        );
    }

    static ConvergenceEvidence resume(
            String proofId,
            String lane,
            Path directory,
            Instant startedAt
    ) throws IOException {
        return new ConvergenceEvidence(
                proofId,
                lane,
                directory,
                startedAt,
                false
        );
    }

    private ConvergenceEvidence(
            String proofId,
            String lane,
            Path directory,
            Instant startedAt,
            boolean resetTimeline
    ) throws IOException {
        this.proofId = requireNonBlank(proofId, "proofId");
        this.lane = requireLane(lane);
        this.directory = Objects.requireNonNull(
                directory,
                "directory"
        ).toAbsolutePath().normalize();
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        Files.createDirectories(this.directory);
        timeline = this.directory.resolve(timelineFileName(lane));
        if (resetTimeline) {
            Files.deleteIfExists(timeline);
        }
    }

    synchronized void record(
            String phase,
            String action,
            Map<String, ?> facts
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("observedAt", Instant.now().toString());
        row.put("lane", lane);
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
                    "Could not append Worker Lab convergence timeline",
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
        value.put("lane", lane);
        value.put("status", requireNonBlank(status, "status"));
        value.put("startedAt", startedAt.toString());
        value.put("completedAt", Instant.now().toString());
        value.put("timelineFile", timelineFileName(lane));
        putFacts(value, facts);

        Path target = summaryFile();
        Path temporary = Files.createTempFile(
                directory,
                ".worker-lab-" + lane + "-summary-",
                ".tmp"
        );
        try {
            Files.writeString(
                    temporary,
                    Jsons.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path summaryFile() {
        return directory.resolve(summaryFileName(lane));
    }

    static String summaryFileName(String lane) {
        return "worker-lab-" + requireLane(lane) + "-summary.json";
    }

    static String timelineFileName(String lane) {
        return "worker-lab-" + requireLane(lane) + "-timeline.jsonl";
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
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.contains("payload")
                    || normalized.contains("properties")) {
                throw new IllegalArgumentException(
                        "Evidence must not record payload or Properties"
                );
            }
            if (target.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate evidence field: " + key
                );
            }
        });
    }

    private static String requireLane(String value) {
        String lane = requireNonBlank(value, "lane");
        if (!lane.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "lane must contain lowercase words separated by hyphens"
            );
        }
        return lane;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
