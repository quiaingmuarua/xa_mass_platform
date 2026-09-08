package com.xa.mass.kernel.worker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** Worker and Group registration plus caller-bounded resource reads. */
public interface WorkerResourceCatalog {

    int MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT = 1000;
    int MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT = 100;
    int MAX_WORKER_BATCH_SIZE = 100;

    /** Create-only Group registration: OK, NOOP, CONFLICT or INVALID. */
    RegistrationResult registerWorkerGroup(WorkerGroupDescriptor descriptor);

    /**
     * Registers 1..100 unique Workers. Existing same-Group bindings win over
     * the default Endpoint. Binding and cold Score initialization commit
     * independently; retry fills missing members without changing any Score.
     * Infrastructure failures throw and may leave a partially completed batch.
     */
    Map<String, WorkerRegistrationResult> registerWorkers(
            String workerGroupId,
            List<String> workerIds,
            String defaultEndpointManagerId
    );

    Map<String, @Nullable WorkerGroupDescriptor> sampleWorkerGroupDescriptors(
            int sampleLimit
    );

    Map<String, @Nullable WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    );

    Map<String, @Nullable WorkerDescriptor> getWorkerDescriptors(
            List<String> workerIds
    );

    CompletableFuture<Map<String, @Nullable WorkerDescriptor>> getWorkerDescriptorsAsync(
            List<String> workerIds
    );

    Map<String, @Nullable WorkerDescriptor> sampleWorkerDescriptors(
            String workerGroupId,
            int sampleLimit
    );

    enum RegistrationStatus {
        OK("ok"),
        NOOP("noop"),
        CONFLICT("conflict"),
        INVALID("invalid"),
        /** Worker registration requires an existing Group. */
        NOT_FOUND("not_found");

        private final String wireValue;

        RegistrationStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /**
     * Binding snapshot. Worker/Group identify the execution slot; Endpoint is
     * its current delivery address. Endpoint migration is not implemented.
     * A Binding alone does not prove Score membership or network availability.
     */
    record WorkerDescriptor(
            String workerId,
            String workerGroupId,
            String endpointManagerId
    ) {
        public WorkerDescriptor {
            requireNonEmpty(workerId, "workerId");
            requireNonEmpty(workerGroupId, "workerGroupId");
            requireNonEmpty(endpointManagerId, "endpointManagerId");
        }
    }

    record WorkerGroupDescriptor(
            String workerGroupId,
            Map<String, Object> attributes,
            Set<String> eventCodes
    ) {
        public WorkerGroupDescriptor {
            requireNonEmpty(workerGroupId, "workerGroupId");
            Objects.requireNonNull(attributes, "mapping");
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            Objects.requireNonNull(eventCodes, "set");
            if (eventCodes.stream().anyMatch(value -> value == null || value.isEmpty())) {
                throw new IllegalArgumentException("set values must be non-empty");
            }
            eventCodes = Collections.unmodifiableSet(new LinkedHashSet<>(eventCodes));
        }
    }

    record RegistrationResult(
            RegistrationStatus status,
            @Nullable String reason
    ) {
        public RegistrationResult {
            Objects.requireNonNull(status, "status");
        }

        public RegistrationResult(RegistrationStatus status) {
            this(status, null);
        }
    }

    record WorkerRegistrationResult(
            RegistrationStatus status,
            @Nullable String endpointManagerId,
            @Nullable String reason
    ) {
        public WorkerRegistrationResult {
            Objects.requireNonNull(status, "status");
            if (status == RegistrationStatus.OK || status == RegistrationStatus.NOOP) {
                requireNonEmpty(endpointManagerId, "endpointManagerId");
            }
        }
    }

    private static void requireNonEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }
}
