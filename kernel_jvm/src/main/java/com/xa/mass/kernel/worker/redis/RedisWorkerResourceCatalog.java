package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class RedisWorkerResourceCatalog
        implements WorkerResourceCatalog, AutoCloseable {

    private final RedisClient redisClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerResourceCatalog(
            RedisClient redisClient,
            String prefix
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.redisClient = redisClient;
        this.prefix = prefix;
    }

    @Override
    public WorkerRuntimeResult upsertWorkerGroup(
            WorkerGroupDescriptor descriptor
    ) {
        throw notImplemented("upsert_worker_group");
    }

    @Override
    public Map<String, WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    ) {
        requireIds(workerGroupIds, "workerGroupIds");
        if (workerGroupIds.isEmpty()) {
            return Map.of();
        }
        List<KeyValue<String, String>> loaded = commands().hmget(
                groupsKey(),
                workerGroupIds.toArray(String[]::new)
        );
        var descriptors = new LinkedHashMap<String, WorkerGroupDescriptor>();
        for (int index = 0; index < workerGroupIds.size(); index++) {
            KeyValue<String, String> value = loaded.get(index);
            descriptors.put(
                    workerGroupIds.get(index),
                    value.hasValue()
                            ? decodeWorkerGroup(value.getValue())
                            : null
            );
        }
        return descriptors;
    }

    @Override
    public Map<String, WorkerDescriptor> getWorkerDescriptors(
            String workerGroupId,
            List<String> workerIds
    ) {
        throw notImplemented("get_worker_descriptors");
    }

    @Override
    public WorkerRuntimeResult updateWorkerPlatformAttributes(
            String workerGroupId,
            String workerId,
            Map<String, Object> attributes
    ) {
        throw notImplemented("update_worker_platform_attributes");
    }

    private WorkerGroupDescriptor decodeWorkerGroup(String raw) {
        try {
            JsonNode payload = requireObject(raw);
            return new WorkerGroupDescriptor(
                    requireText(payload, "workerGroupId"),
                    objectMap(payload.path("attributes")),
                    stringSet(payload.path("eventCodes")),
                    stringSet(payload.path("itemAllocationFields"))
            );
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "WorkerGroup descriptor is corrupt",
                    error
            );
        }
    }

    private JsonNode requireObject(String raw) throws JacksonException {
        JsonNode payload = mapper.readTree(raw);
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException(
                    "descriptor must be a JSON object"
            );
        }
        return payload;
    }

    private static String requireText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value.textValue();
    }

    private Map<String, Object> objectMap(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("field must be an object");
        }
        return mapper.convertValue(
                value,
                new TypeReference<LinkedHashMap<String, Object>>() {
                }
        );
    }

    private static Set<String> stringSet(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("field must be an array");
        }
        var values = new LinkedHashSet<String>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "array values must be non-empty strings"
                );
            }
            values.add(item.textValue());
        });
        return values;
    }

    private RedisCommands<String, String> commands() {
        return connection().sync();
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null || !current.isOpen()) {
            synchronized (this) {
                current = connection;
                if (current == null || !current.isOpen()) {
                    current = redisClient.connect(StringCodec.UTF8);
                    connection = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }

    private String groupsKey() {
        return "wr:" + prefix + ":groups";
    }

    private static KernelOperationNotImplementedException notImplemented(
            String operation
    ) {
        return new KernelOperationNotImplementedException(
                "WorkerResourceCatalog",
                operation
        );
    }

    private static void requireIds(List<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        values.forEach(value -> requireNonBlank(value, name));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }
}
