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
import java.util.stream.Stream;

final class ScenarioWorkerLab {

    static final int MAX_WORKERS_PER_GROUP = 100;

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

        Map<String, Map<String, Map<String, Object>>> defaults = null;
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
            Map<String, Map<String, Object>> defaults
    ) {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory(
                    root,
                    ".initializing-"
            );
            for (Map.Entry<String, Map<String, Object>> worker
                    : defaults.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                Path workerFile = workerPath(
                        temporary,
                        worker.getKey()
                );
                Files.writeString(
                        workerFile,
                        Jsons.toJson(worker.getValue()),
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
                            .toString().endsWith(".json"))
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
        if (workerFiles.size() > MAX_WORKERS_PER_GROUP) {
            throw invalid(
                    "Scenario WorkerGroup "
                            + group.workerGroupId()
                            + " contains more than "
                            + MAX_WORKERS_PER_GROUP
                            + " Worker files",
                    null
            );
        }

        List<ScenarioWorkerStateFile> workers =
                new ArrayList<>(workerFiles.size());
        for (Path workerFile : workerFiles) {
            String filename = workerFile.getFileName().toString();
            String clientWorkerKey = filename.substring(
                    0,
                    filename.length() - ".json".length()
            );
            ScenarioWorkerGroupConfig.requireNonBlank(
                    clientWorkerKey,
                    "clientWorkerKey"
            );
            workers.add(ScenarioWorkerStateFile.open(
                    workerFile,
                    clientWorkerKey
            ));
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

    private static Path workerPath(
            Path groupDirectory,
            String clientWorkerKey
    ) {
        Path segment = singleSegment(
                clientWorkerKey,
                "clientWorkerKey"
        );
        Path target = groupDirectory.resolve(
                segment.toString() + ".json"
        ).normalize();
        if (!groupDirectory.equals(target.getParent())) {
            throw invalid(
                    "clientWorkerKey must map to one Worker file: "
                            + clientWorkerKey,
                    null
            );
        }
        return target;
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

    private static Map<String, Map<String, Map<String, Object>>>
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

    private static Map<String, Map<String, Map<String, Object>>>
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
            Map<String, Map<String, Map<String, Object>>> defaults =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Object> group : root.entrySet()) {
                Map<String, Object> workers = requireObject(
                        group.getValue(),
                        "default WorkerGroup " + group.getKey()
                );
                Map<String, Map<String, Object>> copiedWorkers =
                        new LinkedHashMap<>();
                for (Map.Entry<String, Object> worker
                        : workers.entrySet()) {
                    Map<String, Object> value = requireObject(
                            worker.getValue(),
                            "default Worker " + worker.getKey()
                    );
                    if (value.containsKey("workerId")) {
                        throw new IllegalArgumentException(
                                "Default Worker must not contain workerId: "
                                        + worker.getKey()
                        );
                    }
                    copiedWorkers.put(worker.getKey(), value);
                }
                defaults.put(group.getKey(), Map.copyOf(copiedWorkers));
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
