package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

final class ScenarioWorkerLab {

    static final int MAX_WORKERS_PER_GROUP = 15_000;

    private static final int LAB_INVALID = 14013;
    private static final int LAB_UNAVAILABLE = 14014;
    private static final int LAB_PERSIST_FAILED = 14015;
    private static final String DEFAULT_WORKERS_RESOURCE =
            "/com/xa/mass/scenarioworkers/default-workers.json";

    private final Path root;

    ScenarioWorkerLab(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "sandboxRoot must be non-blank"
            );
        }
        try {
            root = Path.of(configuredRoot)
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException(
                    "sandboxRoot must be a valid path",
                    error
            );
        }
    }

    List<DiscoveredGroup> prepare(
            List<ScenarioWorkerGroupConfig> groups
    ) {
        Objects.requireNonNull(groups, "groups");
        if (groups.isEmpty()) {
            return List.of();
        }
        validateOwnedRoot(groups);
        ensureRootDirectory();

        Map<String, Map<String, List<Map<String, Object>>>> defaults = null;
        List<DiscoveredGroup> discovered = new ArrayList<>(groups.size());
        for (ScenarioWorkerGroupConfig group : groups) {
            Path directory = groupDirectory(group.workerGroupId());
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (defaults == null) {
                    defaults = loadDefaultsForInitialization();
                }
                initializeGroup(
                        group,
                        directory,
                        defaults.getOrDefault(
                                group.workerGroupId(),
                                Map.of()
                        )
                );
            } else {
                requireGroupDirectory(directory);
            }
            discovered.add(discoverGroup(group, directory));
        }
        return List.copyOf(discovered);
    }

    Path root() {
        return root;
    }

    private void initializeGroup(
            ScenarioWorkerGroupConfig group,
            Path target,
            Map<String, List<Map<String, Object>>> defaults
    ) {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory(
                    root,
                    ".initializing-"
            );
            for (Map.Entry<String, List<Map<String, Object>>> inventory
                    : defaults.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                Path workerFile = inventoryPath(
                        temporary,
                        inventory.getKey()
                );
                List<String> encodedRecords = new ArrayList<>();
                for (int index = 0;
                     index < inventory.getValue().size();
                     index++) {
                    encodedRecords.add(seedDocument(
                            inventory.getValue().get(index),
                            inventory.getKey(),
                            index + 1
                    ));
                }
                if (encodedRecords.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Default Worker inventory must not be empty: "
                                    + inventory.getKey()
                    );
                }
                Files.writeString(
                        workerFile,
                        String.join("\n", encodedRecords) + "\n",
                        StandardCharsets.UTF_8
                );
            }
            discoverWorkerFiles(group, temporary);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target);
            }
            temporary = null;
        } catch (RuntimeException | IOException error) {
            if (temporary != null) {
                try {
                    deleteDirectory(temporary);
                } catch (IOException cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
            }
            if (error instanceof ScenarioWorkerAssemblyException) {
                throw (ScenarioWorkerAssemblyException) error;
            }
            throw new ScenarioWorkerAssemblyException(
                    LAB_PERSIST_FAILED,
                    "scenarioWorkerLab.initializeGroup",
                    "Could not initialize Scenario WorkerGroup "
                            + group.workerGroupId()
                            + " in "
                            + root,
                    error
            );
        }
    }

    private DiscoveredGroup discoverGroup(
            ScenarioWorkerGroupConfig group,
            Path directory
    ) {
        return new DiscoveredGroup(
                group,
                discoverWorkerFiles(group, directory)
        );
    }

    private List<ScenarioWorkerStateFile> discoverWorkerFiles(
            ScenarioWorkerGroupConfig group,
            Path directory
    ) {
        List<Path> workerFiles;
        try (Stream<Path> children = Files.list(directory)) {
            workerFiles = children
                    .filter(path -> path.getFileName()
                            .toString().endsWith(".jsonl"))
                    .filter(path -> Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                    ))
                    .sorted(Comparator.comparing(path ->
                            path.getFileName().toString()))
                    .toList();
        } catch (IOException error) {
            throw unavailable(
                    "Could not list Scenario WorkerGroup directory "
                            + directory,
                    error
            );
        }
        List<ScenarioWorkerStateFile> workers = new ArrayList<>();
        for (Path workerFile : workerFiles) {
            workers.addAll(ScenarioWorkerStateFile.open(workerFile));
        }
        if (workers.size() > MAX_WORKERS_PER_GROUP) {
            throw invalid(
                    "Scenario WorkerGroup "
                            + group.workerGroupId()
                            + " contains more than "
                            + MAX_WORKERS_PER_GROUP
                            + " Worker records",
                    null
            );
        }
        return List.copyOf(workers);
    }

    private void ensureRootDirectory() {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(root)
                        || !Files.isDirectory(
                        root,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    throw invalid(
                            "Scenario Worker Lab root must be a directory: "
                                    + root,
                            null
                    );
                }
                return;
            }
            Files.createDirectories(root);
        } catch (ScenarioWorkerAssemblyException error) {
            throw error;
        } catch (IOException error) {
            throw unavailable(
                    "Could not create Scenario Worker Lab root " + root,
                    error
            );
        }
    }

    private static void requireGroupDirectory(Path directory) {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(
                directory,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw invalid(
                    "Scenario WorkerGroup path must be a directory: "
                            + directory,
                    null
            );
        }
    }

    private void validateOwnedRoot(
            List<ScenarioWorkerGroupConfig> groups
    ) {
        if (!root.endsWith(Path.of("data", "scenario-workers"))) {
            throw invalid(
                    "Scenario Worker Lab root must end with "
                            + "data/scenario-workers: "
                            + root,
                    null
            );
        }
        Path current = root.getRoot();
        for (Path segment : root) {
            current = current == null
                    ? segment
                    : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw invalid(
                        "Scenario Worker Lab root must not pass through "
                                + "a symbolic link: "
                                + current,
                        null
                );
            }
        }
        for (ScenarioWorkerGroupConfig group : groups) {
            groupDirectory(group.workerGroupId());
        }
    }

    private Path groupDirectory(String workerGroupId) {
        Path segment = singleSegment(workerGroupId, "workerGroupId");
        Path directory = root.resolve(segment).normalize();
        if (!root.equals(directory.getParent())) {
            throw invalid(
                    "workerGroupId must map to one Lab directory: "
                            + workerGroupId,
                    null
            );
        }
        return directory;
    }

    private static Path inventoryPath(
            Path groupDirectory,
            String filename
    ) {
        Path segment = singleSegment(
                filename,
                "inventory filename"
        );
        Path target = groupDirectory.resolve(segment).normalize();
        if (!filename.endsWith(".jsonl")
                || !groupDirectory.equals(target.getParent())) {
            throw invalid(
                    "inventory filename must map to one JSONL file: "
                            + filename,
                    null
            );
        }
        return target;
    }

    private static String seedDocument(
            Map<String, Object> document,
            String inventoryFileName,
            int lineNumber
    ) {
        if (document == null
                || !document.keySet().equals(Set.of(
                "schemaVersion",
                "workerProperties"
        ))
                || !(document.get("schemaVersion") instanceof Long)
                || ((Long) document.get("schemaVersion")) != 2L
                || !(document.get("workerProperties") instanceof Map<?, ?>)) {
            throw invalid(
                    "Default Worker inventory record is invalid: "
                            + inventoryFileName
                            + ":"
                            + lineNumber,
                    null
            );
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceProperties =
                (Map<String, Object>) document.get("workerProperties");
        if (sourceProperties.containsKey("labInventoryKey")
                || sourceProperties.containsKey("labInventoryLine")) {
            throw invalid(
                    "Default Worker inventory must not define derived "
                            + "Lab identity fields: "
                            + inventoryFileName
                            + ":"
                            + lineNumber,
                    null
            );
        }
        Map<String, Object> properties = new LinkedHashMap<>(
                sourceProperties
        );
        properties.put("labInventoryKey", inventoryFileName);
        properties.put("labInventoryLine", (long) lineNumber);
        Map<String, Object> seeded = new LinkedHashMap<>();
        seeded.put("schemaVersion", 2L);
        seeded.put("workerProperties", properties);
        return Jsons.toJson(seeded);
    }

    private static Path singleSegment(String value, String name) {
        ScenarioWorkerGroupConfig.requireNonBlank(value, name);
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException error) {
            throw invalid(name + " must be a valid path segment", error);
        }
        if (path.isAbsolute()
                || path.getNameCount() != 1
                || ".".equals(value)
                || "..".equals(value)) {
            throw invalid(name + " must be one path segment", null);
        }
        return path;
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path visited,
                    IOException error
            ) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(visited);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, Map<String, List<Map<String, Object>>>>
    loadDefaultsForInitialization() {
        try {
            return loadDefaults();
        } catch (IOException | IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    LAB_PERSIST_FAILED,
                    "scenarioWorkerLab.loadDefaults",
                    "Could not load Scenario Worker Lab defaults",
                    error
            );
        }
    }

    private static Map<String, Map<String, List<Map<String, Object>>>>
    loadDefaults() throws IOException {
        try (InputStream input = ScenarioWorkerLab.class
                .getResourceAsStream(DEFAULT_WORKERS_RESOURCE)) {
            if (input == null) {
                throw new IOException(
                        "Missing " + DEFAULT_WORKERS_RESOURCE
                );
            }
            Map<String, Object> root = Jsons.parseObject(new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            Map<String, Map<String, List<Map<String, Object>>>> defaults =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Object> group : root.entrySet()) {
                Map<String, Object> workers = requireObject(
                        group.getValue(),
                        "default WorkerGroup " + group.getKey()
                );
                Map<String, List<Map<String, Object>>> copiedInventories =
                        new LinkedHashMap<>();
                for (Map.Entry<String, Object> inventory
                        : workers.entrySet()) {
                    inventoryPath(Path.of("inventory"), inventory.getKey());
                    if (!(inventory.getValue() instanceof List<?> rawRecords)
                            || rawRecords.isEmpty()
                            || rawRecords.size()
                            > ScenarioWorkerStateFile.MAX_RECORDS_PER_FILE) {
                        throw new IllegalArgumentException(
                                "Default Worker inventory must contain "
                                        + "1..100 records: "
                                        + inventory.getKey()
                        );
                    }
                    List<Map<String, Object>> records = new ArrayList<>();
                    for (Object rawRecord : rawRecords) {
                        Map<String, Object> record = requireObject(
                                rawRecord,
                                "default Worker " + inventory.getKey()
                        );
                        if (record.containsKey("workerId")) {
                            throw new IllegalArgumentException(
                                    "Default Worker must not contain workerId: "
                                            + inventory.getKey()
                            );
                        }
                        records.add(record);
                    }
                    copiedInventories.put(
                            inventory.getKey(),
                            List.copyOf(records)
                    );
                }
                defaults.put(
                        group.getKey(),
                        Map.copyOf(copiedInventories)
                );
            }
            return Map.copyOf(defaults);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(
            Object value,
            String owner
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(owner + " must be an object");
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static ScenarioWorkerAssemblyException invalid(
            String message,
            Throwable cause
    ) {
        return new ScenarioWorkerAssemblyException(
                LAB_INVALID,
                "scenarioWorkerLab.validate",
                message,
                cause
        );
    }

    private static ScenarioWorkerAssemblyException unavailable(
            String message,
            Throwable cause
    ) {
        return new ScenarioWorkerAssemblyException(
                LAB_UNAVAILABLE,
                "scenarioWorkerLab.discover",
                message,
                cause
        );
    }

    record DiscoveredGroup(
            ScenarioWorkerGroupConfig config,
            List<ScenarioWorkerStateFile> workers
    ) {
    }
}
