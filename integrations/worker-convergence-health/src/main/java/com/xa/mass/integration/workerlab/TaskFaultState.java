package com.xa.mass.integration.workerlab;

import com.xa.mass.integration.workerlab.ConvergenceWorkload.Batch;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record TaskFaultState(
        String proofId,
        Instant startedAt,
        Map<String, String> workerIdsByCoordinate,
        String targetCoordinate,
        String targetWorkerId,
        String backupCoordinate,
        String checkpointToken,
        String checkpointMessageId,
        List<Batch> batches,
        String recoveredWorkerId
) {

    private static final long SCHEMA_VERSION = 5L;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "proofId",
            "startedAt",
            "workerIdsByCoordinate",
            "targetCoordinate",
            "targetWorkerId",
            "backupCoordinate",
            "checkpointToken",
            "checkpointMessageId",
            "batches",
            "recoveredWorkerId"
    );

    TaskFaultState {
        proofId = requireNonBlank(proofId, "proofId");
        java.util.Objects.requireNonNull(startedAt, "startedAt");
        if (workerIdsByCoordinate == null
                || workerIdsByCoordinate.size()
                != WorkerLabConvergenceSupport.WORKER_COUNT
                || workerIdsByCoordinate.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException(
                    "workerIdsByCoordinate must contain 1000 identities"
            );
        }
        workerIdsByCoordinate = Collections.unmodifiableMap(
                new LinkedHashMap<>(workerIdsByCoordinate)
        );
        targetCoordinate = requireNonBlank(targetCoordinate, "targetCoordinate");
        targetWorkerId = requireNonBlank(targetWorkerId, "targetWorkerId");
        backupCoordinate = requireNonBlank(backupCoordinate, "backupCoordinate");
        checkpointToken = requireNonBlank(checkpointToken, "checkpointToken");
        checkpointMessageId = requireNonBlank(
                checkpointMessageId,
                "checkpointMessageId"
        );
        batches = List.copyOf(batches);
        if (batches.size() != 4 && batches.size() != 6) {
            throw new IllegalArgumentException(
                    "Task-fault state must contain two or three waves"
            );
        }
        if (recoveredWorkerId != null && recoveredWorkerId.isBlank()) {
            throw new IllegalArgumentException(
                    "recoveredWorkerId must be null or non-blank"
            );
        }
    }

    static TaskFaultState load(Path configuredPath) {
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(Files.readString(
                    normalized(configuredPath),
                    StandardCharsets.UTF_8
            ));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Could not read Worker task-fault phase state",
                    error
            );
        }
        if (!FIELDS.equals(value.keySet())
                || !(value.get("schemaVersion") instanceof Number version)
                || version.longValue() != SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Worker task-fault phase state is invalid"
            );
        }
        try {
            Map<String, String> workerIds = new LinkedHashMap<>();
            JsonValues.object(
                    value.get("workerIdsByCoordinate"),
                    "workerIdsByCoordinate"
            ).forEach((coordinate, rawWorkerId) -> {
                if (!(rawWorkerId instanceof String workerId)
                        || workerId.isBlank()) {
                    throw JsonValues.invalid("workerId must be non-blank");
                }
                workerIds.put(coordinate, workerId);
            });
            List<Batch> batches = new ArrayList<>();
            for (Object raw : JsonValues.array(value.get("batches"), "batches")) {
                batches.add(Batch.fromMap(JsonValues.object(raw, "batch")));
            }
            Object recovered = value.get("recoveredWorkerId");
            if (recovered != null && !(recovered instanceof String)) {
                throw JsonValues.invalid("recoveredWorkerId must be a string");
            }
            return new TaskFaultState(
                    JsonValues.requiredString(value, "proofId"),
                    Instant.parse(JsonValues.requiredString(value, "startedAt")),
                    workerIds,
                    JsonValues.requiredString(value, "targetCoordinate"),
                    JsonValues.requiredString(value, "targetWorkerId"),
                    JsonValues.requiredString(value, "backupCoordinate"),
                    JsonValues.requiredString(value, "checkpointToken"),
                    JsonValues.requiredString(value, "checkpointMessageId"),
                    batches,
                    (String) recovered
            );
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Worker task-fault phase state is invalid",
                    error
            );
        }
    }

    void save(Path configuredPath) {
        Path path = normalized(configuredPath);
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "phase-state must have a parent directory"
            );
        }
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(
                    parent,
                    ".worker-task-fault-",
                    ".tmp"
            );
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("proofId", proofId);
            value.put("startedAt", startedAt.toString());
            value.put("workerIdsByCoordinate", workerIdsByCoordinate);
            value.put("targetCoordinate", targetCoordinate);
            value.put("targetWorkerId", targetWorkerId);
            value.put("backupCoordinate", backupCoordinate);
            value.put("checkpointToken", checkpointToken);
            value.put("checkpointMessageId", checkpointMessageId);
            value.put("batches", batches.stream().map(Batch::toMap).toList());
            value.put("recoveredWorkerId", recoveredWorkerId);
            Files.writeString(
                    temporary,
                    Jsons.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not persist Worker task-fault phase state",
                    error
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the primary persistence error.
                }
            }
        }
    }

    TaskFaultState recoveredBy(
            String workerId,
            List<Batch> completedBatches
    ) {
        return new TaskFaultState(
                proofId,
                startedAt,
                workerIdsByCoordinate,
                targetCoordinate,
                targetWorkerId,
                backupCoordinate,
                checkpointToken,
                checkpointMessageId,
                completedBatches,
                requireNonBlank(workerId, "workerId")
        );
    }

    private static Path normalized(Path path) {
        return java.util.Objects.requireNonNull(path, "path")
                .toAbsolutePath()
                .normalize();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
