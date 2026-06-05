package com.xa.mass.api.worker.registration;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryWorkerRegistrationObservationStore implements WorkerRegistrationObservationStore {

    private final Map<String, WorkerRegistrationObservationRecord> byId = new LinkedHashMap<>();

    @Override
    public synchronized WorkerRegistrationObservationRecord append(WorkerRegistrationObservationRecord record) {
        WorkerRegistrationObservationRecord normalized = Objects.requireNonNull(record, "record is required");
        byId.put(normalized.observationId(), normalized);
        return normalized;
    }

    @Override
    public synchronized List<WorkerRegistrationObservationRecord> listByResource(String resourceType, String resourceId) {
        return byId.values().stream()
                .filter(record -> Objects.equals(record.resourceType(), resourceType))
                .filter(record -> Objects.equals(record.resourceId(), resourceId))
                .sorted(Comparator.comparing(WorkerRegistrationObservationRecord::occurredAt)
                        .thenComparing(WorkerRegistrationObservationRecord::observationId))
                .toList();
    }
}
