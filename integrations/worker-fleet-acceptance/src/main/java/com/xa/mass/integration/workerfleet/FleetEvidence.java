package com.xa.mass.integration.workerfleet;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FleetEvidence {

    private final String proofId;
    private final FleetCommandLineOptions.Phase phase;
    private final String endpointManagerId;
    private final Map<String, GroupEvidence> groups = new LinkedHashMap<>();
    private final List<Map<String, Object>> failures = new ArrayList<>();
    private Boolean baselineIdentityMatched;

    FleetEvidence(
            String proofId,
            FleetCommandLineOptions.Phase phase,
            FleetSpec spec
    ) {
        this.proofId = proofId;
        this.phase = phase;
        endpointManagerId = spec.endpointManagerId();
        spec.labWorkerKeysByGroup().forEach((groupId, keys) ->
                groups.put(groupId, new GroupEvidence(keys)));
    }

    void inventory(Map<String, Map<String, String>> inventory) {
        inventory.forEach((groupId, workers) ->
                groups.get(groupId).workerIdsByLabWorkerKey =
                        new LinkedHashMap<>(workers));
    }

    void connected(String groupId, List<String> workerIds) {
        groups.get(groupId).connectedWorkerIds = List.copyOf(workerIds);
    }

    void probeObserved(String groupId, List<String> workerIds) {
        groups.get(groupId).probeObservedWorkerIds = List.copyOf(workerIds);
    }

    void propertiesMatched(String groupId, List<String> workerIds) {
        groups.get(groupId).propertiesMatchedWorkerIds = List.copyOf(workerIds);
    }

    void baselineIdentityMatched(boolean value) {
        baselineIdentityMatched = value;
    }

    void failure(
            String invariant,
            String groupId,
            String message,
            List<String> missingIds,
            List<String> unexpectedIds,
            List<String> inconsistentIds
    ) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("invariant", invariant);
        if (groupId != null) {
            failure.put("workerGroupId", groupId);
        }
        failure.put("message", message);
        failure.put("missingIds", List.copyOf(missingIds));
        failure.put("unexpectedIds", List.copyOf(unexpectedIds));
        failure.put("inconsistentIds", List.copyOf(inconsistentIds));
        failures.add(Collections.unmodifiableMap(failure));
    }

    void write(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                Jsons.toJson(toMap()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    Map<String, Object> toMap() {
        Map<String, Object> encodedGroups = new LinkedHashMap<>();
        groups.forEach((groupId, group) -> encodedGroups.put(
                groupId,
                group.toMap()
        ));
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schemaVersion", 1);
        encoded.put("proofId", proofId);
        encoded.put("phase", phase.wireValue());
        encoded.put("status", failures.isEmpty() ? "succeeded" : "failed");
        encoded.put("endpointManagerId", endpointManagerId);
        encoded.put("groups", encodedGroups);
        encoded.put("baselineIdentityMatched", baselineIdentityMatched);
        encoded.put("failures", List.copyOf(failures));
        return encoded;
    }

    private static final class GroupEvidence {

        private final int expectedReplicaCount;
        private final List<String> expectedLabWorkerKeys;
        private Map<String, String> workerIdsByLabWorkerKey = Map.of();
        private List<String> connectedWorkerIds = List.of();
        private List<String> probeObservedWorkerIds = List.of();
        private List<String> propertiesMatchedWorkerIds = List.of();

        private GroupEvidence(List<String> expectedLabWorkerKeys) {
            this.expectedLabWorkerKeys = List.copyOf(
                    expectedLabWorkerKeys
            );
            expectedReplicaCount = expectedLabWorkerKeys.size();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("expectedReplicaCount", expectedReplicaCount);
            encoded.put("expectedLabWorkerKeys", expectedLabWorkerKeys);
            encoded.put(
                    "workerIdsByLabWorkerKey",
                    workerIdsByLabWorkerKey
            );
            encoded.put("connectedWorkerIds", connectedWorkerIds);
            encoded.put("probeObservedWorkerIds", probeObservedWorkerIds);
            encoded.put(
                    "propertiesMatchedWorkerIds",
                    propertiesMatchedWorkerIds
            );
            return encoded;
        }
    }
}
