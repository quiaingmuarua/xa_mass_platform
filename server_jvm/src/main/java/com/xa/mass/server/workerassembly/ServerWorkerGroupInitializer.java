package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ServerWorkerGroupInitializer {

    private static final Set<String> GROUP_FIELDS = Set.of(
            "attributes",
            "eventCodes"
    );

    private final WorkerResourceCatalog workerCatalog;
    private final List<WorkerGroupDescriptor> descriptors;
    private boolean initialized;

    private ServerWorkerGroupInitializer(
            WorkerResourceCatalog workerCatalog,
            List<WorkerGroupDescriptor> descriptors
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.descriptors = List.copyOf(descriptors);
    }

    static ServerWorkerGroupInitializer fromJson(
            String groupConfigJson,
            WorkerResourceCatalog workerCatalog
    ) {
        return new ServerWorkerGroupInitializer(
                workerCatalog,
                parse(groupConfigJson)
        );
    }

    synchronized void initialize() {
        if (initialized) {
            return;
        }
        for (WorkerGroupDescriptor descriptor : descriptors) {
            WorkerRuntimeResult result = workerCatalog.upsertWorkerGroup(
                    descriptor
            );
            if (result == null
                    || (result.status() != WorkerRuntimeStatus.OK
                    && result.status() != WorkerRuntimeStatus.NOOP)) {
                String status = result == null
                        ? "missing"
                        : result.status().wireValue();
                String reason = result == null || result.reason() == null
                        ? ""
                        : ": " + result.reason();
                throw new IllegalStateException(
                        "WorkerGroup "
                                + descriptor.workerGroupId()
                                + " initialization returned "
                                + status
                                + reason
                );
            }
        }
        initialized = true;
    }

    private static List<WorkerGroupDescriptor> parse(String configJson) {
        Map<String, Object> root = Jsons.parseObject(configJson);
        List<WorkerGroupDescriptor> descriptors =
                new ArrayList<>(root.size());
        root.forEach((workerGroupId, rawGroup) -> {
            requireNonBlank(workerGroupId, "workerGroupId");
            Map<String, Object> group = requireObject(
                    rawGroup,
                    "WorkerGroup " + workerGroupId
            );
            requireExactFields(
                    group,
                    GROUP_FIELDS,
                    "WorkerGroup " + workerGroupId
            );
            descriptors.add(new WorkerGroupDescriptor(
                    workerGroupId,
                    optionalObject(group, "attributes"),
                    new LinkedHashSet<>(requireEventCodes(group))
            ));
        });
        return List.copyOf(descriptors);
    }

    private static List<String> requireEventCodes(
            Map<String, Object> group
    ) {
        Object raw = group.get("eventCodes");
        if (!(raw instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "eventCodes must be an array"
            );
        }
        List<String> eventCodes = new ArrayList<>(((List<?>) raw).size());
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof String) || ((String) item).isBlank()) {
                throw new IllegalArgumentException(
                        "eventCodes must contain non-blank strings"
                );
            }
            if (!unique.add((String) item)) {
                throw new IllegalArgumentException(
                        "eventCodes must not contain duplicates: " + item
                );
            }
            eventCodes.add((String) item);
        }
        return List.copyOf(eventCodes);
    }

    private static Map<String, Object> optionalObject(
            Map<String, Object> value,
            String field
    ) {
        if (!value.containsKey(field)) {
            return Map.of();
        }
        return requireObject(value.get(field), field);
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

    private static void requireExactFields(
            Map<String, Object> value,
            Set<String> allowed,
            String owner
    ) {
        for (String field : value.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        owner + " contains unknown field " + field
                );
            }
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }
}
