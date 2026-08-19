package com.xa.mass.integration.workerfleet;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ScenarioWorkerInventory {

    private ScenarioWorkerInventory() {
    }

    static Map<String, Map<String, String>> await(
            Path root,
            FleetSpec spec,
            Duration maximumWait
    ) {
        long deadline = System.nanoTime() + maximumWait.toNanos();
        RuntimeException latest = null;
        while (System.nanoTime() < deadline) {
            try {
                return load(root, spec);
            } catch (IOException | RuntimeException error) {
                latest = error instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException(
                                "Could not read Scenario Worker Lab",
                                error
                        );
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for Worker identities",
                        error
                );
            }
        }
        if (latest instanceof InventoryMismatch mismatch) {
            throw mismatch;
        }
        throw new IllegalStateException(
                "Scenario Worker identities did not converge",
                latest
        );
    }

    static Map<String, Map<String, String>> load(
            Path root,
            FleetSpec spec
    ) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "Scenario Worker Lab does not exist"
            );
        }
        Set<String> actualGroups = directDirectories(root);
        if (!actualGroups.equals(spec.groupIds())) {
            throw mismatch(
                    "lab.worker-groups",
                    null,
                    "Scenario Worker Group directories do not match spec",
                    spec.groupIds(),
                    actualGroups,
                    List.of()
            );
        }

        Set<String> allWorkerIds = new HashSet<>();
        Map<String, Map<String, String>> inventory = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> expected
                : spec.clientWorkerKeysByGroup().entrySet()) {
            Path groupRoot = root.resolve(expected.getKey());
            Map<String, Path> files = workerFiles(groupRoot);
            Set<String> expectedKeys = new LinkedHashSet<>(expected.getValue());
            if (!files.keySet().equals(expectedKeys)) {
                throw mismatch(
                        "lab.client-worker-keys",
                        expected.getKey(),
                        "Scenario Worker files do not match fleet spec",
                        expectedKeys,
                        files.keySet(),
                        List.of()
                );
            }
            Map<String, String> group = new LinkedHashMap<>();
            for (String clientWorkerKey : expected.getValue()) {
                Map<String, Object> state = Jsons.parseObject(
                        Files.readString(
                                files.get(clientWorkerKey),
                                StandardCharsets.UTF_8
                        )
                );
                Object schemaVersion = state.get("schemaVersion");
                if (!(schemaVersion instanceof Number number)
                        || number.intValue() != 1) {
                    throw inconsistent(
                            "lab.worker-state",
                            expected.getKey(),
                            "Scenario Worker state has invalid schemaVersion",
                            clientWorkerKey
                    );
                }
                String workerId;
                try {
                    workerId = canonicalWorkerId(state.get("workerId"));
                } catch (IllegalStateException error) {
                    throw inconsistent(
                            "lab.worker-id",
                            expected.getKey(),
                            error.getMessage(),
                            clientWorkerKey
                    );
                }
                if (!allWorkerIds.add(workerId)) {
                    throw inconsistent(
                            "lab.worker-id-uniqueness",
                            expected.getKey(),
                            "Scenario Worker IDs must be globally unique",
                            workerId
                    );
                }
                group.put(clientWorkerKey, workerId);
            }
            inventory.put(
                    expected.getKey(),
                    Collections.unmodifiableMap(group)
            );
        }
        return Collections.unmodifiableMap(inventory);
    }

    private static Set<String> directDirectories(Path root)
            throws IOException {
        Set<String> directories = new LinkedHashSet<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isDirectory(path)
                        && !Files.isSymbolicLink(path)) {
                    directories.add(path.getFileName().toString());
                }
            }
        }
        return directories;
    }

    private static Map<String, Path> workerFiles(Path groupRoot)
            throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        try (var paths = Files.list(groupRoot)) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json")
                        || !Files.isRegularFile(path)
                        || Files.isSymbolicLink(path)) {
                    continue;
                }
                String key = name.substring(0, name.length() - 5);
                if (files.putIfAbsent(key, path) != null) {
                    throw new IllegalStateException(
                            "Duplicate Scenario Worker file"
                    );
                }
            }
        }
        return files;
    }

    private static String canonicalWorkerId(Object value) {
        if (!(value instanceof String workerId) || workerId.isBlank()) {
            throw new IllegalStateException(
                    "Scenario Worker state has no Worker ID"
            );
        }
        try {
            if (!UUID.fromString(workerId).toString().equals(workerId)) {
                throw new IllegalStateException(
                        "Scenario Worker ID is not canonical"
                );
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Scenario Worker ID is invalid",
                    error
            );
        }
        return workerId;
    }

    private static InventoryMismatch mismatch(
            String invariant,
            String groupId,
            String message,
            Set<String> expected,
            Set<String> actual,
            List<String> inconsistentIds
    ) {
        return new InventoryMismatch(
                invariant,
                groupId,
                message,
                expected.stream()
                        .filter(value -> !actual.contains(value))
                        .toList(),
                actual.stream()
                        .filter(value -> !expected.contains(value))
                        .toList(),
                inconsistentIds
        );
    }

    private static InventoryMismatch inconsistent(
            String invariant,
            String groupId,
            String message,
            String identifier
    ) {
        return new InventoryMismatch(
                invariant,
                groupId,
                message,
                List.of(),
                List.of(),
                List.of(identifier)
        );
    }

    static final class InventoryMismatch extends IllegalStateException {

        private final String invariant;
        private final String groupId;
        private final List<String> missingIds;
        private final List<String> unexpectedIds;
        private final List<String> inconsistentIds;

        private InventoryMismatch(
                String invariant,
                String groupId,
                String message,
                List<String> missingIds,
                List<String> unexpectedIds,
                List<String> inconsistentIds
        ) {
            super(message);
            this.invariant = invariant;
            this.groupId = groupId;
            this.missingIds = List.copyOf(missingIds);
            this.unexpectedIds = List.copyOf(unexpectedIds);
            this.inconsistentIds = List.copyOf(inconsistentIds);
        }

        String invariant() {
            return invariant;
        }

        String groupId() {
            return groupId;
        }

        List<String> missingIds() {
            return missingIds;
        }

        List<String> unexpectedIds() {
            return unexpectedIds;
        }

        List<String> inconsistentIds() {
            return inconsistentIds;
        }
    }
}
