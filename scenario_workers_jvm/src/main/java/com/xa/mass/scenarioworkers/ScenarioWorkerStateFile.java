package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One fixed physical-line Worker record inside a Lab inventory file. */
final class ScenarioWorkerStateFile {

    static final int MAX_RECORDS_PER_FILE = 100;

    private static final int LAB_INVALID = 14013;
    private static final int LAB_UNAVAILABLE = 14014;
    private static final int LAB_PERSIST_FAILED = 14015;
    private static final long SCHEMA_VERSION = 2L;
    private static final String LAB_INVENTORY_KEY = "labInventoryKey";
    private static final String LAB_INVENTORY_LINE = "labInventoryLine";
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion",
            "workerProperties"
    );

    private final Path path;
    private final String inventoryFileName;
    private final int lineNumber;
    private final String labWorkerKey;

    private ScenarioWorkerStateFile(
            Path path,
            String inventoryFileName,
            int lineNumber
    ) {
        this.path = path;
        this.inventoryFileName = inventoryFileName;
        this.lineNumber = lineNumber;
        labWorkerKey = inventoryFileName + ":" + lineNumber;
    }

    static List<ScenarioWorkerStateFile> open(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        List<StateDocument> records = readRecords(normalized);
        String filename = normalized.getFileName().toString();
        List<ScenarioWorkerStateFile> workers = new ArrayList<>(
                records.size()
        );
        for (int index = 0; index < records.size(); index++) {
            workers.add(new ScenarioWorkerStateFile(
                    normalized,
                    filename,
                    index + 1
            ));
        }
        return List.copyOf(workers);
    }

    String labWorkerKey() {
        return labWorkerKey;
    }

    String inventoryFileName() {
        return inventoryFileName;
    }

    int lineNumber() {
        return lineNumber;
    }

    Map<String, Object> workerProperties() {
        return readRecords(path).get(lineNumber - 1).workerProperties();
    }

    void replace(String encodedDocument) {
        StateDocument replacement = parseDocument(
                encodedDocument,
                path,
                lineNumber
        );
        List<String> lines = readLines(path);
        for (int index = 0; index < lines.size(); index++) {
            parseDocument(lines.get(index), path, index + 1);
        }
        if (lineNumber > lines.size()) {
            throw invalid(path, lineNumber, null);
        }

        List<String> updated = new ArrayList<>(lines);
        updated.set(lineNumber - 1, encode(replacement));
        writeLines(path, updated);
    }

    private static List<StateDocument> readRecords(Path path) {
        List<String> lines = readLines(path);
        List<StateDocument> records = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            records.add(parseDocument(lines.get(index), path, index + 1));
        }
        return List.copyOf(records);
    }

    private static List<String> readLines(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ScenarioWorkerAssemblyException(
                    LAB_UNAVAILABLE,
                    "scenarioWorkerStateFile.open",
                    "Could not read Scenario Worker file " + path,
                    error
            );
        }
        if (lines.isEmpty()
                || lines.size() > MAX_RECORDS_PER_FILE) {
            throw invalid(path, 0, null);
        }
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                throw invalid(path, index + 1, null);
            }
        }
        return lines;
    }

    private static StateDocument parseDocument(
            String encoded,
            Path path,
            int lineNumber
    ) {
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(encoded);
        } catch (IllegalArgumentException error) {
            throw invalid(path, lineNumber, error);
        }
        if (!FIELDS.equals(value.keySet())
                || !(value.get("schemaVersion") instanceof Long)
                || ((Long) value.get("schemaVersion")) != SCHEMA_VERSION
                || !(value.get("workerProperties") instanceof Map<?, ?>)) {
            throw invalid(path, lineNumber, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) value.get(
                "workerProperties"
        );
        requireInventoryIdentity(properties, path, lineNumber);
        return new StateDocument(immutableJsonMap(
                properties,
                "workerProperties"
        ));
    }

    private static void requireInventoryIdentity(
            Map<String, Object> properties,
            Path path,
            int lineNumber
    ) {
        String filename = path.getFileName().toString();
        if (properties.containsKey("clientWorkerKey")
                || !filename.equals(properties.get(LAB_INVENTORY_KEY))
                || !(properties.get(LAB_INVENTORY_LINE) instanceof Long)
                || ((Long) properties.get(LAB_INVENTORY_LINE))
                != lineNumber) {
            throw invalid(path, lineNumber, null);
        }
    }

    private static String encode(StateDocument document) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", SCHEMA_VERSION);
        canonical.put("workerProperties", document.workerProperties());
        return Jsons.toJson(canonical);
    }

    private static void writeLines(Path target, List<String> lines) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(
                    target.getParent(),
                    target.getFileName().toString() + ".",
                    ".tmp"
            );
            Files.writeString(
                    temporary,
                    String.join("\n", lines) + "\n",
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
            int lineNumber,
            Throwable cause
    ) {
        String location = lineNumber > 0
                ? path + ":" + lineNumber
                : path.toString();
        return new ScenarioWorkerAssemblyException(
                LAB_INVALID,
                "scenarioWorkerStateFile.open",
                "Scenario Worker file is invalid: " + location,
                cause
        );
    }

    private record StateDocument(Map<String, Object> workerProperties) {
    }
}
