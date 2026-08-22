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
import java.util.function.Function;

final class ScenarioWorkerInventory {

    private static final Set<String> STATE_FIELDS = Set.of(
            "schemaVersion",
            "workerProperties"
    );

    private ScenarioWorkerInventory() {
    }

    static Map<String, Map<String, String>> await(
            Path root,
            FleetSpec spec,
            RuntimeApiClient api,
            Duration maximumWait
    ) {
        long deadline = System.nanoTime() + maximumWait.toNanos();
        RuntimeException latest = null;
        while (System.nanoTime() < deadline) {
            try {
                validateLab(root, spec);
                return loadRuntime(api, spec);
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

    static void validateLab(Path root, FleetSpec spec) throws IOException {
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

        for (Map.Entry<String, List<String>> expected
                : spec.clientWorkerKeysByGroup().entrySet()) {
            Map<String, Path> files = workerFiles(root.resolve(
                    expected.getKey()
            ));
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
            for (String clientWorkerKey : expected.getValue()) {
                Map<String, Object> state = Jsons.parseObject(
                        Files.readString(
                                files.get(clientWorkerKey),
                                StandardCharsets.UTF_8
                        )
                );
                Object schemaVersion = state.get("schemaVersion");
                boolean propertiesValid =
                        !state.containsKey("workerProperties")
                                || state.get("workerProperties")
                                instanceof Map<?, ?>;
                if (!STATE_FIELDS.containsAll(state.keySet())
                        || !(schemaVersion instanceof Number number)
                        || number.intValue() != 2
                        || !propertiesValid) {
                    throw inconsistent(
                            "lab.worker-state",
                            expected.getKey(),
                            "Scenario Worker state is not schema v2",
                            clientWorkerKey
                    );
                }
            }
        }
    }

    static Map<String, Map<String, String>> loadRuntime(
            RuntimeApiClient api,
            FleetSpec spec
    ) {
        return loadRuntime(spec, api::previewWorkerIdentities);
    }

    static Map<String, Map<String, String>> loadRuntime(
            FleetSpec spec,
            Function<String, Map<String, String>> previewByGroup
    ) {
        Set<String> allWorkerIds = new HashSet<>();
        Map<String, Map<String, String>> inventory = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> expected
                : spec.clientWorkerKeysByGroup().entrySet()) {
            Map<String, String> observed = previewByGroup.apply(
                    expected.getKey()
            );
            Set<String> expectedKeys = new LinkedHashSet<>(expected.getValue());
            Set<String> observedKeys = observed.keySet();
            if (!observedKeys.containsAll(expectedKeys)) {
                throw mismatch(
                        "runtime.client-worker-keys",
                        expected.getKey(),
                        "Runtime Worker preview is missing fleet identities",
                        expectedKeys,
                        observedKeys,
                        List.of()
                );
            }
            Map<String, String> group = new LinkedHashMap<>();
            for (String clientWorkerKey : expected.getValue()) {
                String workerId;
                try {
                    workerId = canonicalWorkerId(
                            observed.get(clientWorkerKey)
                    );
                } catch (IllegalStateException error) {
                    throw inconsistent(
                            "runtime.worker-id",
                            expected.getKey(),
                            error.getMessage(),
                            clientWorkerKey
                    );
                }
                if (!allWorkerIds.add(workerId)) {
                    throw inconsistent(
                            "runtime.worker-id-uniqueness",
                            expected.getKey(),
                            "Runtime Worker IDs must be globally unique",
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
                if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
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
                    "Runtime Worker descriptor has no Worker ID"
            );
        }
        try {
            if (!UUID.fromString(workerId).toString().equals(workerId)) {
                throw new IllegalStateException(
                        "Runtime Worker ID is not canonical"
                );
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Runtime Worker ID is invalid",
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
