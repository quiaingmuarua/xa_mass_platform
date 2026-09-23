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

record StateConvergencePhaseState(
        String proofId,
        Instant startedAt,
        Map<String, String> workerIdsByCoordinate,
        List<Batch> batches,
        String propertyWitnessTaskId,
        String propertyWitnessMessageId
) {

    private static final long SCHEMA_VERSION = 4L;

    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "proofId",
            "startedAt",
            "workerIdsByCoordinate",
            "batches",
            "propertyWitnessTaskId",
            "propertyWitnessMessageId"
    );

    StateConvergencePhaseState {
        if (proofId == null || proofId.isBlank()
                || startedAt == null
                || workerIdsByCoordinate == null
                || workerIdsByCoordinate.size()
                != WorkerLabConvergenceSupport.WORKER_COUNT
                || batches == null || batches.size() != 12
                || propertyWitnessTaskId == null
                || propertyWitnessTaskId.isBlank()
                || propertyWitnessMessageId == null
                || propertyWitnessMessageId.isBlank()) {
            throw new IllegalArgumentException(
                    "State convergence phase state is incomplete"
            );
        }
        workerIdsByCoordinate = Collections.unmodifiableMap(
                new LinkedHashMap<>(workerIdsByCoordinate)
        );
        batches = List.copyOf(batches);
    }

    static StateConvergencePhaseState load(Path configuredPath) {
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(Files.readString(
                    normalized(configuredPath),
                    StandardCharsets.UTF_8
            ));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Could not read state convergence phase state",
                    error
            );
        }
        if (!FIELDS.equals(value.keySet())
                || !(value.get("schemaVersion") instanceof Number version)
                || version.longValue() != SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "State convergence phase state is invalid"
            );
        }
        Map<String, Object> rawIds = JsonValues.object(
                value.get("workerIdsByCoordinate"),
                "workerIdsByCoordinate"
        );
        Map<String, String> workerIds = new LinkedHashMap<>();
        rawIds.forEach((coordinate, rawWorkerId) -> {
            if (!(rawWorkerId instanceof String workerId) || workerId.isBlank()) {
                throw JsonValues.invalid("workerId must be non-blank");
            }
            workerIds.put(coordinate, workerId);
        });
        List<Batch> batches = new ArrayList<>();
        for (Object raw : JsonValues.array(value.get("batches"), "batches")) {
            batches.add(Batch.fromMap(JsonValues.object(raw, "batch")));
        }
        return new StateConvergencePhaseState(
                JsonValues.requiredString(value, "proofId"),
                Instant.parse(JsonValues.requiredString(value, "startedAt")),
                workerIds,
                batches,
                JsonValues.requiredString(value, "propertyWitnessTaskId"),
                JsonValues.requiredString(value, "propertyWitnessMessageId")
        );
    }

    void save(Path configuredPath) {
        Path path = normalized(configuredPath);
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("phase-state requires a parent");
        }
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".worker-state-", ".tmp");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("proofId", proofId);
            value.put("startedAt", startedAt.toString());
            value.put("workerIdsByCoordinate", workerIdsByCoordinate);
            value.put("batches", batches.stream().map(Batch::toMap).toList());
            value.put("propertyWitnessTaskId", propertyWitnessTaskId);
            value.put("propertyWitnessMessageId", propertyWitnessMessageId);
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
                    "Could not persist state convergence phase state",
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

    private static Path normalized(Path path) {
        return java.util.Objects.requireNonNull(path, "path")
                .toAbsolutePath()
                .normalize();
    }
}
