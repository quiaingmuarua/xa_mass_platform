package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ScenarioWorkerStateFile implements WorkerIdentityStore {

    private static final int LAB_INVALID = 14013;
    private static final int LAB_UNAVAILABLE = 14014;
    private static final int LAB_PERSIST_FAILED = 14015;
    private static final long SCHEMA_VERSION = 1L;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "workerId",
            "workerProperties",
            "indexedPropertyUpdates"
    );

    private final Path path;
    private final String clientWorkerKey;
    private final Map<String, Object> workerProperties;
    private final Map<String, Object> indexedPropertyUpdates;
    private String workerId;

    private ScenarioWorkerStateFile(
            Path path,
            String clientWorkerKey,
            Map<String, Object> workerProperties,
            Map<String, Object> indexedPropertyUpdates,
            String workerId
    ) {
        this.path = path;
        this.clientWorkerKey = clientWorkerKey;
        this.workerProperties = immutableJsonMap(
                workerProperties,
                "workerProperties"
        );
        this.indexedPropertyUpdates = immutableJsonMap(
                indexedPropertyUpdates,
                "indexedPropertyUpdates"
        );
        this.workerId = workerId;
    }

    static ScenarioWorkerStateFile open(
            Path path,
            String clientWorkerKey
    ) {
        ScenarioWorkerGroupConfig.requireNonBlank(
                clientWorkerKey,
                "clientWorkerKey"
        );
        Path normalized = path.toAbsolutePath().normalize();
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(Files.readString(
                    normalized,
                    StandardCharsets.UTF_8
            ));
        } catch (IOException error) {
            throw new ScenarioWorkerAssemblyException(
                    LAB_UNAVAILABLE,
                    "scenarioWorkerStateFile.open",
                    "Could not read Scenario Worker file " + normalized,
                    error
            );
        } catch (IllegalArgumentException error) {
            throw invalid(normalized, error);
        }

        for (String field : value.keySet()) {
            if (!FIELDS.contains(field)) {
                throw invalid(normalized, null);
            }
        }
        if (!(value.get("schemaVersion") instanceof Long)
                || ((Long) value.get("schemaVersion"))
                != SCHEMA_VERSION) {
            throw invalid(normalized, null);
        }

        Map<String, Object> workerProperties = optionalObject(
                value,
                "workerProperties",
                normalized
        );
        Map<String, Object> indexedPropertyUpdates = optionalObject(
                value,
                "indexedPropertyUpdates",
                normalized
        );
        indexedPropertyUpdates.keySet().forEach(field -> {
            if (!field.startsWith("index.")
                    || field.length() == "index.".length()) {
                throw invalid(normalized, null);
            }
        });

        String workerId = null;
        if (value.containsKey("workerId")) {
            Object rawWorkerId = value.get("workerId");
            if (!(rawWorkerId instanceof String)) {
                throw invalid(normalized, null);
            }
            workerId = requireWorkerId(
                    (String) rawWorkerId,
                    "scenarioWorkerStateFile.open"
            );
        }
        return new ScenarioWorkerStateFile(
                normalized,
                clientWorkerKey,
                workerProperties,
                indexedPropertyUpdates,
                workerId
        );
    }

    String clientWorkerKey() {
        return clientWorkerKey;
    }

    Map<String, Object> workerProperties() {
        return workerProperties;
    }

    Map<String, Object> indexedPropertyUpdates() {
        return indexedPropertyUpdates;
    }

    @Override
    public synchronized Optional<String> loadWorkerId() {
        return Optional.ofNullable(workerId);
    }

    @Override
    public synchronized void saveWorkerId(String value) {
        String resolvedWorkerId = requireWorkerId(
                value,
                "scenarioWorkerStateFile.storeIdentity"
        );
        if (workerId != null) {
            if (!workerId.equals(resolvedWorkerId)) {
                throw new ScenarioWorkerAssemblyException(
                        LAB_INVALID,
                        "scenarioWorkerStateFile.storeIdentity",
                        "Scenario Worker file already contains a different workerId"
                );
            }
            return;
        }

        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("schemaVersion", SCHEMA_VERSION);
        persisted.put("workerId", resolvedWorkerId);
        persisted.put("workerProperties", workerProperties);
        persisted.put(
                "indexedPropertyUpdates",
                indexedPropertyUpdates
        );
        writeJson(path, persisted);
        workerId = resolvedWorkerId;
    }

    private static void writeJson(
            Path target,
            Map<String, Object> value
    ) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(
                    target.getParent(),
                    target.getFileName().toString() + ".",
                    ".tmp"
            );
            Files.writeString(
                    temporary,
                    Jsons.toJson(value),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    LAB_PERSIST_FAILED,
                    "scenarioWorkerStateFile.storeIdentity",
                    "Could not persist Scenario Worker file " + target,
                    error
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the primary persistence failure.
                }
            }
        }
    }

    private static String requireWorkerId(
            String value,
            String operation
    ) {
        if (value == null || value.trim().isEmpty()) {
            throw new ScenarioWorkerAssemblyException(
                    LAB_INVALID,
                    operation,
                    "Scenario Worker file contains a blank workerId"
            );
        }
        return value;
    }

    private static Map<String, Object> optionalObject(
            Map<String, Object> value,
            String field,
            Path path
    ) {
        if (!value.containsKey(field)) {
            return Map.of();
        }
        Object raw = value.get(field);
        if (!(raw instanceof Map<?, ?>)) {
            throw invalid(path, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object =
                new LinkedHashMap<>((Map<String, Object>) raw);
        return object;
    }

    private static Map<String, Object> immutableJsonMap(
            Map<String, Object> value,
            String name
    ) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static ScenarioWorkerAssemblyException invalid(
            Path path,
            Throwable cause
    ) {
        return new ScenarioWorkerAssemblyException(
                LAB_INVALID,
                "scenarioWorkerStateFile.open",
                "Scenario Worker file is invalid: " + path,
                cause
        );
    }
}
