package com.xa.mass.workersimulator;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Consumer;
import java.util.Locale;

final class WorkerSimulatorLab {

    static final int MAX_WORKERS_PER_GROUP = 15_000;

    private static final int LAB_INVALID = 14013;
    private static final int LAB_UNAVAILABLE = 14014;
    private static final int LAB_PERSIST_FAILED = 14015;

    private final Path root;

    WorkerSimulatorLab(String configuredRoot) {
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
            List<WorkerSimulatorGroupConfig> groups,
            long seed,
            Consumer<List<DiscoveredGroup>> validateWorld
    ) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(validateWorld, "validateWorld");
        validateOwnedRoot(groups);
        if (groups.isEmpty()) {
            validateWorld.accept(List.of());
            return List.of();
        }
        ensureRootDirectory();
        Map<WorkerSimulatorGroupConfig, Path> staged = new LinkedHashMap<>();
        try {
            List<DiscoveredGroup> world = new ArrayList<>();
            for (WorkerSimulatorGroupConfig group : groups) {
                Path target = groupDirectory(group.workerGroupId());
                boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (exists) requireGroupDirectory(target);
                Path source = target;
                if (!exists || group.newEnvironment()) {
                    source = Files.createTempDirectory(root, ".initializing-");
                    staged.put(group, source);
                    generateGroup(group, seed, source);
                }
                world.add(discoverGroup(group, source));
            }
            // Business requirements and startup coordinates are validated before any existing inventory changes.
            validateWorld.accept(List.copyOf(world));
            for (Map.Entry<WorkerSimulatorGroupConfig, Path> entry : staged.entrySet()) {
                installGroup(entry.getValue(), groupDirectory(entry.getKey().workerGroupId()));
            }
            List<DiscoveredGroup> installed = new ArrayList<>();
            for (WorkerSimulatorGroupConfig group : groups) {
                installed.add(discoverGroup(group, groupDirectory(group.workerGroupId())));
            }
            return List.copyOf(installed);
        } catch (IOException error) {
            throw new WorkerSimulatorAssemblyException(LAB_PERSIST_FAILED, "workerSimulatorLab.prepare",
                    "Could not install Worker Simulator inventory in " + root, error);
        } finally {
            for (Path path : staged.values()) {
                try { deleteDirectory(path); }
                catch (IOException error) {
                    System.getLogger(WorkerSimulatorLab.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not remove staging directory " + path, error);
                }
            }
        }
    }

    Path root() { return root; }

    private void generateGroup(WorkerSimulatorGroupConfig group, long seed, Path directory) throws IOException {
        for (int first = 1; first <= group.count(); first += WorkerSimulatorStateFile.MAX_RECORDS_PER_FILE) {
            String name = String.format(Locale.ROOT, "workers-%03d.jsonl",
                    (first - 1) / WorkerSimulatorStateFile.MAX_RECORDS_PER_FILE);
            List<String> lines = new ArrayList<>();
            int end = Math.min(group.count(), first + WorkerSimulatorStateFile.MAX_RECORDS_PER_FILE - 1);
            for (int ordinal = first; ordinal <= end; ordinal++) {
                Map<String, String> properties = new LinkedHashMap<>(group.generateProperties(seed, ordinal));
                properties.put("labInventoryKey", name);
                properties.put("labInventoryLine", Integer.toString(ordinal - first + 1));
                lines.add(Jsons.toJson(Map.of("schemaVersion", 2, "workerProperties", properties)));
            }
            Files.writeString(inventoryPath(directory, name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
    }

    private void installGroup(Path staged, Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireGroupDirectory(target);
            backup = Files.createTempDirectory(root, ".replaced-");
            Files.delete(backup); // Only the freshly reserved, empty directory.
            moveDirectory(target, backup);
        }
        try {
            moveDirectory(staged, target);
        } catch (IOException | RuntimeException error) {
            if (backup != null) {
                try { moveDirectory(backup, target); }
                catch (IOException | RuntimeException restoreFailure) {
                    error.addSuppressed(new IOException("Previous inventory remains at " + backup, restoreFailure));
                }
            }
            throw error;
        }
        if (backup != null) deleteDirectory(backup);
    }

    void moveDirectory(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source, target); }
    }

    private DiscoveredGroup discoverGroup(
            WorkerSimulatorGroupConfig group,
            Path directory
    ) {
        return new DiscoveredGroup(
                group,
                discoverWorkerFiles(group, directory)
        );
    }

    private List<WorkerSimulatorStateFile> discoverWorkerFiles(
            WorkerSimulatorGroupConfig group,
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
                    "Could not list Worker Simulator Group directory "
                            + directory,
                    error
            );
        }
        List<WorkerSimulatorStateFile> workers = new ArrayList<>();
        for (Path workerFile : workerFiles) {
            workers.addAll(WorkerSimulatorStateFile.open(workerFile));
        }
        if (workers.size() > MAX_WORKERS_PER_GROUP) {
            throw invalid(
                    "Worker Simulator Group "
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
                            "Worker Simulator root must be a directory: "
                                    + root,
                            null
                    );
                }
                return;
            }
            Files.createDirectories(root);
        } catch (WorkerSimulatorAssemblyException error) {
            throw error;
        } catch (IOException error) {
            throw unavailable(
                    "Could not create Worker Simulator root " + root,
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
                    "Worker Simulator Group path must be a directory: "
                            + directory,
                    null
            );
        }
    }

    private void validateOwnedRoot(
            List<WorkerSimulatorGroupConfig> groups
    ) {
        if (!root.endsWith(Path.of("data", "scenario-workers"))) {
            throw invalid(
                    "Worker Simulator root must end with "
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
                        "Worker Simulator root must not pass through "
                                + "a symbolic link: "
                                + current,
                        null
                );
            }
        }
        Set<Path> directories = new HashSet<>();
        for (WorkerSimulatorGroupConfig group : groups) {
            if (!directories.add(groupDirectory(group.workerGroupId()))) {
                throw invalid("WorkerGroups must map to distinct inventory directories", null);
            }
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

    private static Path singleSegment(String value, String name) {
        WorkerSimulatorGroupConfig.requireNonBlank(value, name);
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

    private void deleteDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!root.equals(absolute.getParent()) || absolute.equals(root) || Files.isSymbolicLink(absolute)) {
            throw new IOException("Refusing cleanup outside a direct owned staging/backup directory");
        }
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

    private static WorkerSimulatorAssemblyException invalid(
            String message,
            Throwable cause
    ) {
        return new WorkerSimulatorAssemblyException(
                LAB_INVALID,
                "workerSimulatorLab.validate",
                message,
                cause
        );
    }

    private static WorkerSimulatorAssemblyException unavailable(
            String message,
            Throwable cause
    ) {
        return new WorkerSimulatorAssemblyException(
                LAB_UNAVAILABLE,
                "workerSimulatorLab.discover",
                message,
                cause
        );
    }

    record DiscoveredGroup(
            WorkerSimulatorGroupConfig config,
            List<WorkerSimulatorStateFile> workers
    ) {
    }
}
