package com.xa.mass.integration.workercorrectness;

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
            CorrectnessSpec spec,
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

    static void validateLab(Path root, CorrectnessSpec spec) throws IOException {
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
                : spec.labWorkerKeysByGroup().entrySet()) {
            Map<String, String> records = workerRecords(
                    expected.getKey(),
                    root.resolve(expected.getKey())
            );
            Set<String> expectedKeys = new LinkedHashSet<>(expected.getValue());
            if (!records.keySet().equals(expectedKeys)) {
                throw mismatch(
                        "lab.worker-keys",
                        expected.getKey(),
                        "Scenario Worker records do not match fleet spec",
                        expectedKeys,
                        records.keySet(),
                        List.of()
                );
            }
            for (String labWorkerKey : expected.getValue()) {
                Map<String, Object> state = Jsons.parseObject(
                        records.get(labWorkerKey)
                );
                Object schemaVersion = state.get("schemaVersion");
                if (!STATE_FIELDS.equals(state.keySet())
                        || !(schemaVersion instanceof Number number)
                        || number.intValue() != 2
                        || !(state.get("workerProperties")
                        instanceof Map<?, ?>)) {
                    throw inconsistent(
                            "lab.worker-state",
                            expected.getKey(),
                            "Scenario Worker state is not schema v2",
                            labWorkerKey
                    );
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> properties =
                        (Map<String, Object>) state.get("workerProperties");
                int separator = labWorkerKey.lastIndexOf(':');
                String inventoryKey = labWorkerKey.substring(0, separator);
                long inventoryLine = Long.parseLong(
                        labWorkerKey.substring(separator + 1)
                );
                if (!inventoryKey.equals(properties.get("labInventoryKey"))
                        || !Long.toString(inventoryLine).equals(properties.get("labInventoryLine"))
                        || properties.containsKey("clientWorkerKey")) {
                    throw inconsistent(
                            "lab.worker-identity",
                            expected.getKey(),
                            "Scenario Worker inventory identity does not match its physical line",
                            labWorkerKey
                    );
                }
            }
        }
    }

    static Map<String, Map<String, String>> loadRuntime(
            RuntimeApiClient api,
            CorrectnessSpec spec
    ) {
        return loadRuntime(spec, api::previewWorkerIdentities);
    }

    static Map<String, Map<String, String>> loadRuntime(
            CorrectnessSpec spec,
            Function<String, Map<String, String>> previewByGroup
    ) {
        Set<String> allWorkerIds = new HashSet<>();
        Map<String, Map<String, String>> inventory = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> expected
                : spec.labWorkerKeysByGroup().entrySet()) {
            Map<String, String> observed = previewByGroup.apply(
                    expected.getKey()
            );
            Set<String> expectedKeys = new LinkedHashSet<>(expected.getValue());
            Set<String> observedKeys = observed.keySet();
            if (!observedKeys.containsAll(expectedKeys)) {
                throw mismatch(
                        "runtime.worker-keys",
                        expected.getKey(),
                        "Runtime Worker preview is missing fleet identities",
                        expectedKeys,
                        observedKeys,
                        List.of()
                );
            }
            Map<String, String> group = new LinkedHashMap<>();
            for (String labWorkerKey : expected.getValue()) {
                String workerId;
                try {
                    workerId = canonicalWorkerId(
                            observed.get(labWorkerKey)
                    );
                } catch (IllegalStateException error) {
                    throw inconsistent(
                            "runtime.worker-id",
                            expected.getKey(),
                            error.getMessage(),
                            labWorkerKey
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
                group.put(labWorkerKey, workerId);
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

    private static Map<String, String> workerRecords(
            String groupId,
            Path groupRoot
    )
            throws IOException {
        Map<String, String> records = new LinkedHashMap<>();
        try (var paths = Files.list(groupRoot)) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".jsonl")
                        || !Files.isRegularFile(path)
                        || Files.isSymbolicLink(path)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(
                        path,
                        StandardCharsets.UTF_8
                );
                if (lines.isEmpty() || lines.size() > 100) {
                    throw inconsistent(
                            "lab.inventory-file",
                            groupId,
                            "Scenario Worker inventory must contain "
                                    + "1..100 physical lines",
                            name
                    );
                }
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    String key = name + ":" + (index + 1);
                    if (line.isBlank()) {
                        throw inconsistent(
                                "lab.worker-state",
                                groupId,
                                "Scenario Worker record must not be blank",
                                key
                        );
                    }
                    if (records.putIfAbsent(key, line) != null) {
                        throw new IllegalStateException(
                                "Duplicate Scenario Worker record"
                        );
                    }
                }
            }
        }
        return records;
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
