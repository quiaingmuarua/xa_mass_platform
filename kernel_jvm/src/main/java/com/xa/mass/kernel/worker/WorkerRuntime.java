package com.xa.mass.kernel.worker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface WorkerRuntime {

    WorkerRuntimeResult registerWorker(WorkerDeclaration declaration);

    WorkerRuntimeResult updateWorkerProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> workerProperties
    );

    enum WorkerRuntimeStatus {
        OK("ok"),
        NOOP("noop"),
        REJECTED("rejected"),
        NOT_FOUND("not_found"),
        STALE("stale"),
        CONFLICT("conflict"),
        INVALID("invalid");

        private final String wireValue;

        WorkerRuntimeStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record WorkerDeclaration(
            String workerId,
            String workerGroupId,
            String endpointManagerId,
            Map<String, Object> workerProperties
    ) {
        public WorkerDeclaration {
            requireNonBlank(workerId, "workerId");
            requireNonBlank(workerGroupId, "workerGroupId");
            requireNonBlank(endpointManagerId, "endpointManagerId");
            workerProperties = immutableMap(workerProperties);
        }
    }

    record WorkerDescriptor(
            String workerId,
            String workerGroupId,
            String endpointManagerId,
            Map<String, Object> workerProperties,
            Map<String, Object> platformProperties
    ) {
        public WorkerDescriptor {
            requireNonBlank(workerId, "workerId");
            requireNonBlank(workerGroupId, "workerGroupId");
            requireNonBlank(endpointManagerId, "endpointManagerId");
            workerProperties = immutableMap(workerProperties);
            platformProperties = immutableMap(platformProperties);
        }
    }

    record WorkerGroupDescriptor(
            String workerGroupId,
            Map<String, Object> attributes,
            Set<String> eventCodes
    ) {
        public WorkerGroupDescriptor {
            requireNonBlank(workerGroupId, "workerGroupId");
            attributes = immutableMap(attributes);
            eventCodes = immutableSet(eventCodes);
        }
    }

    record WorkerRuntimeResult(
            WorkerRuntimeStatus status,
            @Nullable String reason
    ) {
        public WorkerRuntimeResult {
            Objects.requireNonNull(status, "status");
        }

        public WorkerRuntimeResult(WorkerRuntimeStatus status) {
            this(status, null);
        }
    }

    private static Map<String, Object> immutableMap(
            Map<String, Object> source
    ) {
        Objects.requireNonNull(source, "mapping");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Set<String> immutableSet(Set<String> source) {
        Objects.requireNonNull(source, "set");
        if (source.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException(
                    "set values must be non-empty"
            );
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }
}
