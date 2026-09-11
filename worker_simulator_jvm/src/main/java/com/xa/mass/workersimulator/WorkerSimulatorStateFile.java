package com.xa.mass.workersimulator;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One fixed physical-line Worker record inside a Lab inventory file. */
final class WorkerSimulatorStateFile {

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
    private volatile Map<String, String> currentProperties;

    private WorkerSimulatorStateFile(
            Path path,
            String inventoryFileName,
            int lineNumber,
            Map<String, String> properties
    ) {
        this.path = path;
        this.inventoryFileName = inventoryFileName;
        this.lineNumber = lineNumber;
        labWorkerKey = inventoryFileName + ":" + lineNumber;
        currentProperties = properties;
    }

    static List<WorkerSimulatorStateFile> open(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        List<StateDocument> records = readRecords(normalized);
        String filename = normalized.getFileName().toString();
        List<WorkerSimulatorStateFile> workers = new ArrayList<>(
                records.size()
        );
        for (int index = 0; index < records.size(); index++) {
            workers.add(new WorkerSimulatorStateFile(
                    normalized,
                    filename,
                    index + 1,
                    records.get(index).workerProperties()
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

    Map<String, String> workerProperties() {
        return currentProperties;
    }

    Map<String, String> readProperties() {
        return readRecords(path).get(lineNumber - 1).workerProperties();
    }

    Map<String, String> parseReplacement(String encodedDocument) {
        return parseDocument(encodedDocument, path, lineNumber).workerProperties();
    }

    void install(Map<String, String> properties) {
        currentProperties = properties;
    }

    void replace(String encodedDocument) {
        Map<String, String> properties = parseReplacement(encodedDocument);
        persist(properties);
        install(properties);
    }

    void persist(Map<String, String> properties) {
        StateDocument replacement = new StateDocument(properties);
        requireInventoryIdentity(properties, path, lineNumber);
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
            throw new WorkerSimulatorAssemblyException(
                    LAB_UNAVAILABLE,
                    "workerSimulatorStateFile.open",
                    "Could not read Worker Simulator file " + path,
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
        try {
            Map<String, String> properties = WorkerDeliveryCodec.copyWorkerProperties(
                    (Map<?, ?>) value.get("workerProperties")
            );
            requireInventoryIdentity(properties, path, lineNumber);
            return new StateDocument(properties);
        } catch (IllegalArgumentException error) {
            throw invalid(path, lineNumber, error);
        }
    }

    private static void requireInventoryIdentity(
            Map<String, String> properties,
            Path path,
            int lineNumber
    ) {
        String filename = path.getFileName().toString();
        if (properties.containsKey("clientWorkerKey")
                || !filename.equals(properties.get(LAB_INVENTORY_KEY))
                || !Integer.toString(lineNumber).equals(properties.get(LAB_INVENTORY_LINE))) {
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
            throw new WorkerSimulatorAssemblyException(
                    LAB_PERSIST_FAILED,
                    "workerSimulatorStateFile.persist",
                    "Could not persist Worker Simulator file " + target,
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

    private static WorkerSimulatorAssemblyException invalid(
            Path path,
            int lineNumber,
            Throwable cause
    ) {
        String location = lineNumber > 0
                ? path + ":" + lineNumber
                : path.toString();
        return new WorkerSimulatorAssemblyException(
                LAB_INVALID,
                "workerSimulatorStateFile.open",
                "Worker Simulator file is invalid: " + location,
                cause
        );
    }

    private record StateDocument(Map<String, String> workerProperties) {
    }
}
