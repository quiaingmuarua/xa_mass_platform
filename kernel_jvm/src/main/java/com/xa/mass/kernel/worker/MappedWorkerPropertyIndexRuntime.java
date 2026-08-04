package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Routes owner calls to immutable, per-property index implementations. */
public final class MappedWorkerPropertyIndexRuntime
        implements WorkerPropertyIndexRuntime {

    private final WorkerResourceCatalog catalog;
    private final Map<String, WorkerPropertyIndex> indexesByField;

    public MappedWorkerPropertyIndexRuntime(
            WorkerResourceCatalog catalog,
            Map<String, ? extends WorkerPropertyIndex> indexes
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(indexes, "indexes");
        var routed = new LinkedHashMap<String, WorkerPropertyIndex>();
        indexes.forEach((propertyField, index) -> {
            if (!validIndexField(propertyField)) {
                throw new IllegalArgumentException(
                        "property index fields must use index.*"
                );
            }
            Objects.requireNonNull(index, "indexes must not contain null");
            routed.put(propertyField, index);
        });
        this.indexesByField = Collections.unmodifiableMap(routed);
    }

    @Override
    public Map<String, WorkerRuntimeResult> updateIndexedProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> updates
    ) {
        Objects.requireNonNull(updates, "updates");
        if (updates.isEmpty()) {
            return Map.of();
        }
        if (workerGroupId == null || workerGroupId.isEmpty()) {
            return uniform(
                    updates.keySet(),
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerGroupId"
            );
        }
        if (workerId == null || workerId.isEmpty()) {
            return uniform(
                    updates.keySet(),
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerId"
            );
        }

        WorkerDescriptor worker = catalog.getWorkerDescriptors(
                workerGroupId,
                List.of(workerId)
        ).get(workerId);
        if (worker == null) {
            return uniform(
                    updates.keySet(),
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker not found"
            );
        }
        var results = new LinkedHashMap<String, WorkerRuntimeResult>();
        updates.forEach((propertyField, value) -> {
            if (!validIndexField(propertyField)) {
                results.put(
                        propertyField,
                        result(
                                WorkerRuntimeStatus.INVALID,
                                "property index fields must use index.*"
                        )
                );
                return;
            }
            WorkerPropertyIndex index = indexesByField.get(propertyField);
            if (index == null) {
                results.put(
                        propertyField,
                        result(
                                WorkerRuntimeStatus.NOT_FOUND,
                                "property index is not configured"
                        )
                );
                return;
            }
            WorkerRuntimeResult providerResult;
            try {
                providerResult = index.update(
                        workerGroupId,
                        workerId,
                        value
                );
            } catch (RuntimeException error) {
                providerResult = result(
                        WorkerRuntimeStatus.STALE,
                        "property index provider failed"
                );
            }
            if (providerResult == null) {
                providerResult = result(
                        WorkerRuntimeStatus.STALE,
                        "property index provider returned an invalid result"
                );
            }
            results.put(propertyField, providerResult);
        });
        return Collections.unmodifiableMap(results);
    }

    @Override
    public Map<String, Object> loadIndexedPropertyValues(
            String workerGroupId,
            String indexField,
            List<String> workerIds
    ) {
        requireNonEmpty(workerGroupId, "workerGroupId");
        if (!validIndexField(indexField)) {
            throw new IllegalArgumentException(
                    "invalid indexed property field"
            );
        }
        Objects.requireNonNull(workerIds, "workerIds");
        var uniqueWorkerIds = new LinkedHashSet<String>();
        for (String workerId : workerIds) {
            requireNonEmpty(workerId, "Worker id");
            uniqueWorkerIds.add(workerId);
        }
        if (uniqueWorkerIds.isEmpty()
                || uniqueWorkerIds.size() > MAX_INDEXED_PROPERTY_READ_LIMIT) {
            throw new IllegalArgumentException(
                    "indexed property read must contain 1..100 Workers"
            );
        }
        WorkerPropertyIndex index = indexesByField.get(indexField);
        if (index == null) {
            throw new IllegalStateException("property index is not configured");
        }

        Map<String, Object> loaded = index.load(
                workerGroupId,
                List.copyOf(uniqueWorkerIds)
        );
        if (loaded == null) {
            throw new IllegalStateException(
                    "property index returned an invalid projection"
            );
        }
        var values = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : loaded.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().isEmpty()
                    || !uniqueWorkerIds.contains(entry.getKey())
                    || entry.getValue() == null) {
                throw new IllegalStateException(
                        "property index returned an invalid projection"
                );
            }
            values.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    private static boolean validIndexField(String field) {
        return field != null
                && field.startsWith("index.")
                && field.length() > "index.".length();
    }

    private static Map<String, WorkerRuntimeResult> uniform(
            Set<String> propertyNames,
            WorkerRuntimeStatus status,
            String reason
    ) {
        var results = new LinkedHashMap<String, WorkerRuntimeResult>();
        for (String propertyName : propertyNames) {
            results.put(propertyName, result(status, reason));
        }
        return Collections.unmodifiableMap(results);
    }

    private static WorkerRuntimeResult result(
            WorkerRuntimeStatus status,
            String reason
    ) {
        return new WorkerRuntimeResult(status, reason);
    }

    private static void requireNonEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }
}
