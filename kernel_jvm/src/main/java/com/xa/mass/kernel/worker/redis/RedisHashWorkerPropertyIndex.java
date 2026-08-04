package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.worker.WorkerPropertyIndex;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import io.lettuce.core.KeyValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One Redis-backed point-readable property projection. */
public final class RedisHashWorkerPropertyIndex
        implements WorkerPropertyIndex {

    private final RedisHashWorkerPropertyIndexProvider provider;
    private final String propertyField;

    RedisHashWorkerPropertyIndex(
            RedisHashWorkerPropertyIndexProvider provider,
            String propertyField
    ) {
        this.provider = provider;
        this.propertyField = propertyField;
    }

    @Override
    public WorkerRuntimeResult update(
            String workerGroupId,
            String workerId,
            @Nullable Object value
    ) {
        String valuesKey = WorkerRedisSupport.propertyValuesKey(
                provider.prefix(),
                workerGroupId,
                propertyField
        );
        if (value == null) {
            provider.commands().hdel(valuesKey, workerId);
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        final String encoded;
        try {
            encoded = WorkerRedisSupport.encodeIndexedPropertyValue(value);
        } catch (IllegalArgumentException error) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "property projection requires a JSON-compatible value"
            );
        }
        provider.commands().hset(valuesKey, workerId, encoded);
        return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
    }

    @Override
    public Map<String, Object> load(
            String workerGroupId,
            List<String> workerIds
    ) {
        if (workerGroupId == null || workerGroupId.isEmpty()) {
            throw new IllegalArgumentException(
                    "workerGroupId must be non-empty"
            );
        }
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        String valuesKey = WorkerRedisSupport.propertyValuesKey(
                provider.prefix(),
                workerGroupId,
                propertyField
        );
        List<KeyValue<String, String>> loaded = provider.commands().hmget(
                valuesKey,
                workerIds.toArray(String[]::new)
        );
        var values = new LinkedHashMap<String, Object>();
        for (KeyValue<String, String> item : loaded) {
            if (!item.hasValue()) {
                continue;
            }
            values.put(
                    item.getKey(),
                    WorkerRedisSupport.decodeIndexedPropertyValue(
                            item.getValue()
                    )
            );
        }
        return Collections.unmodifiableMap(values);
    }
}
