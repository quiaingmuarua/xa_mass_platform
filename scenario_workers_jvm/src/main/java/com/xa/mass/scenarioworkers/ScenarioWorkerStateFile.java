package com.xa.mass.scenarioworkers;

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
import java.util.Set;

final class ScenarioWorkerStateFile {

    private static final int LAB_INVALID = 14013;
    private static final int LAB_UNAVAILABLE = 14014;
    private static final int LAB_PERSIST_FAILED = 14015;
    private static final long LEGACY_SCHEMA_VERSION = 1L;
    private static final long SCHEMA_VERSION = 2L;
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "schemaVersion",
            "workerId",
            "workerProperties"
    );
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "workerProperties"
    );

    private final String clientWorkerKey;
    private final Map<String, Object> workerProperties;

    private ScenarioWorkerStateFile(
            String clientWorkerKey,
            Map<String, Object> workerProperties
    ) {
        this.clientWorkerKey = clientWorkerKey;
        this.workerProperties = immutableJsonMap(
                workerProperties,
                "workerProperties"
        );
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

        if (!(value.get("schemaVersion") instanceof Long)) {
            throw invalid(normalized, null);
        }
        long schemaVersion = (Long) value.get("schemaVersion");
        Set<String> allowedFields = schemaVersion == LEGACY_SCHEMA_VERSION
                ? LEGACY_FIELDS
                : FIELDS;
        if ((schemaVersion != LEGACY_SCHEMA_VERSION
                && schemaVersion != SCHEMA_VERSION)
                || !allowedFields.containsAll(value.keySet())) {
            throw invalid(normalized, null);
        }

        Map<String, Object> workerProperties = optionalObject(
                value,
                "workerProperties",
                normalized
        );

        if (schemaVersion == LEGACY_SCHEMA_VERSION
                && value.containsKey("workerId")) {
            Object rawWorkerId = value.get("workerId");
            if (!(rawWorkerId instanceof String)
                    || ((String) rawWorkerId).trim().isEmpty()) {
                throw invalid(normalized, null);
            }
        }
        if (schemaVersion == LEGACY_SCHEMA_VERSION) {
            Map<String, Object> migrated = new LinkedHashMap<>();
            migrated.put("schemaVersion", SCHEMA_VERSION);
            migrated.put("workerProperties", workerProperties);
            writeJson(normalized, migrated);
        }
        return new ScenarioWorkerStateFile(
                clientWorkerKey,
                workerProperties
        );
    }

    String clientWorkerKey() {
        return clientWorkerKey;
    }

    Map<String, Object> workerProperties() {
        return workerProperties;
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
                    "scenarioWorkerStateFile.migrate",
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
