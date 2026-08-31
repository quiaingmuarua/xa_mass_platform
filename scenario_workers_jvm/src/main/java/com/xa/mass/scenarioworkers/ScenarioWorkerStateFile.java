package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final Path path;
    private final String clientWorkerKey;

    private ScenarioWorkerStateFile(
            Path path,
            String clientWorkerKey
    ) {
        this.path = path;
        this.clientWorkerKey = clientWorkerKey;
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
        readDocument(normalized, true);
        return new ScenarioWorkerStateFile(
                normalized,
                clientWorkerKey
        );
    }

    String clientWorkerKey() {
        return clientWorkerKey;
    }

    Map<String, Object> workerProperties() {
        return readDocument(path, true).workerProperties();
    }

    void replace(String encodedDocument) {
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(encodedDocument);
        } catch (IllegalArgumentException error) {
            throw invalid(path, error);
        }
        if (!FIELDS.equals(value.keySet())
                || !(value.get("schemaVersion") instanceof Long)
                || ((Long) value.get("schemaVersion")) != SCHEMA_VERSION) {
            throw invalid(path, null);
        }
        Map<String, Object> workerProperties = optionalObject(
                value,
                "workerProperties",
                path
        );
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", SCHEMA_VERSION);
        canonical.put("workerProperties", workerProperties);
        writeJson(path, canonical);
    }

    private static StateDocument readDocument(
            Path normalized,
            boolean migrateLegacy
    ) {
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
        if (schemaVersion == LEGACY_SCHEMA_VERSION && migrateLegacy) {
            Map<String, Object> migrated = new LinkedHashMap<>();
            migrated.put("schemaVersion", SCHEMA_VERSION);
            migrated.put("workerProperties", workerProperties);
            writeJson(normalized, migrated);
        }
        return new StateDocument(
                immutableJsonMap(workerProperties, "workerProperties")
        );
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
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException | IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    LAB_PERSIST_FAILED,
                    "scenarioWorkerStateFile.persist",
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

    private record StateDocument(Map<String, Object> workerProperties) {
    }
}
