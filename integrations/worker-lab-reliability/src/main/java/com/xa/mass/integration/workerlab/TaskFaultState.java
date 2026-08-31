package com.xa.mass.integration.workerlab;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record TaskFaultState(
        String proofId,
        Instant startedAt,
        String targetWorkerId,
        String taskId,
        String messageId,
        String checkpointToken,
        long labSlot,
        String recoveredWorkerId
) {

    private static final long SCHEMA_VERSION = 1L;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "proofId",
            "startedAt",
            "targetWorkerId",
            "taskId",
            "messageId",
            "checkpointToken",
            "labSlot",
            "recoveredWorkerId"
    );

    TaskFaultState {
        proofId = requireNonBlank(proofId, "proofId");
        java.util.Objects.requireNonNull(startedAt, "startedAt");
        targetWorkerId = requireNonBlank(targetWorkerId, "targetWorkerId");
        taskId = requireNonBlank(taskId, "taskId");
        messageId = requireNonBlank(messageId, "messageId");
        checkpointToken = requireNonBlank(
                checkpointToken,
                "checkpointToken"
        );
        if (labSlot < 1L) {
            throw new IllegalArgumentException("labSlot must be positive");
        }
        if (recoveredWorkerId != null && recoveredWorkerId.isBlank()) {
            throw new IllegalArgumentException(
                    "recoveredWorkerId must be null or non-blank"
            );
        }
    }

    static TaskFaultState load(Path configuredPath) {
        Path path = normalized(configuredPath);
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(Files.readString(
                    path,
                    StandardCharsets.UTF_8
            ));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Could not read Worker task-fault phase state",
                    error
            );
        }
        if (!FIELDS.equals(value.keySet())
                || !(value.get("schemaVersion") instanceof Long version)
                || version != SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Worker task-fault phase state is invalid"
            );
        }
        Object recovered = value.get("recoveredWorkerId");
        if (recovered != null && !(recovered instanceof String)) {
            throw new IllegalStateException(
                    "Worker task-fault recoveredWorkerId is invalid"
            );
        }
        try {
            return new TaskFaultState(
                    JsonValues.requiredString(value, "proofId"),
                    Instant.parse(JsonValues.requiredString(
                            value,
                            "startedAt"
                    )),
                    JsonValues.requiredString(value, "targetWorkerId"),
                    JsonValues.requiredString(value, "taskId"),
                    JsonValues.requiredString(value, "messageId"),
                    JsonValues.requiredString(value, "checkpointToken"),
                    JsonValues.requiredLong(value, "labSlot"),
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
            value.put("targetWorkerId", targetWorkerId);
            value.put("taskId", taskId);
            value.put("messageId", messageId);
            value.put("checkpointToken", checkpointToken);
            value.put("labSlot", labSlot);
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

    TaskFaultState recoveredBy(String workerId) {
        return new TaskFaultState(
                proofId,
                startedAt,
                targetWorkerId,
                taskId,
                messageId,
                checkpointToken,
                labSlot,
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
