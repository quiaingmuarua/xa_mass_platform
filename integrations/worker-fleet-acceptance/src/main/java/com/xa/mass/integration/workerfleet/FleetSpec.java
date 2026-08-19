package com.xa.mass.integration.workerfleet;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record FleetSpec(
        String endpointManagerId,
        Map<String, List<String>> clientWorkerKeysByGroup
) {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "endpointManagerId",
            "groups"
    );
    private static final Set<String> GROUP_FIELDS = Set.of(
            "clientWorkerKeys"
    );

    FleetSpec {
        endpointManagerId = Identifiers.require(
                endpointManagerId,
                "endpointManagerId"
        );
        if (clientWorkerKeysByGroup == null
                || clientWorkerKeysByGroup.isEmpty()) {
            throw new IllegalArgumentException(
                    "fleet groups must not be empty"
            );
        }
        Map<String, List<String>> copied = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<String>> entry
                : clientWorkerKeysByGroup.entrySet()) {
            String groupId = Identifiers.require(
                    entry.getKey(),
                    "workerGroupId"
            );
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException(
                        "clientWorkerKeys must not be empty for " + groupId
                );
            }
            List<String> keys = new ArrayList<>(values.size());
            Set<String> unique = new HashSet<>();
            for (String value : values) {
                String key = Identifiers.require(value, "clientWorkerKey");
                if (!unique.add(key)) {
                    throw new IllegalArgumentException(
                            "duplicate clientWorkerKey for " + groupId
                    );
                }
                keys.add(key);
            }
            total += keys.size();
            copied.put(groupId, List.copyOf(keys));
        }
        if (total > 100) {
            throw new IllegalArgumentException(
                    "fleet proof supports at most 100 Workers"
            );
        }
        clientWorkerKeysByGroup = Collections.unmodifiableMap(copied);
    }

    static FleetSpec load(Path path) throws IOException {
        Map<String, Object> root = Jsons.parseObject(Files.readString(
                path,
                StandardCharsets.UTF_8
        ));
        if (!root.keySet().equals(ROOT_FIELDS)) {
            throw new IllegalArgumentException(
                    "fleet spec must contain endpointManagerId and groups"
            );
        }
        Object rawEndpoint = root.get("endpointManagerId");
        if (!(rawEndpoint instanceof String endpointManagerId)) {
            throw new IllegalArgumentException(
                    "fleet spec endpointManagerId must be a string"
            );
        }
        Map<String, Object> groups = objectMap(
                root.get("groups"),
                "fleet spec groups"
        );
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : groups.entrySet()) {
            Map<String, Object> group = objectMap(
                    entry.getValue(),
                    "fleet group " + entry.getKey()
            );
            if (!group.keySet().equals(GROUP_FIELDS)) {
                throw new IllegalArgumentException(
                        "fleet group must contain only clientWorkerKeys"
                );
            }
            Object rawKeys = group.get("clientWorkerKeys");
            if (!(rawKeys instanceof List<?> values)) {
                throw new IllegalArgumentException(
                        "clientWorkerKeys must be an array"
                );
            }
            List<String> keys = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof String key)) {
                    throw new IllegalArgumentException(
                            "clientWorkerKeys must contain strings"
                    );
                }
                keys.add(key);
            }
            parsed.put(entry.getKey(), keys);
        }
        return new FleetSpec(endpointManagerId, parsed);
    }

    List<String> allClientWorkerKeys() {
        return clientWorkerKeysByGroup.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    Set<String> groupIds() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(clientWorkerKeysByGroup.keySet())
        );
    }

    private static Map<String, Object> objectMap(
            Object value,
            String name
    ) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        name + " keys must be strings"
                );
            }
            copied.put(key, entry.getValue());
        }
        return copied;
    }
}
