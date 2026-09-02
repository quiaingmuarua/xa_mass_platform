package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.kernel.worker.redis.WorkerRedisSupport.WorkerMetadata;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class RedisWorkerResourceCatalog
        implements WorkerResourceCatalog, AutoCloseable {

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerResourceCatalog(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        this.redisClient = redisClient;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public WorkerRuntimeResult registerWorkerGroup(
            WorkerGroupDescriptor descriptor
    ) {
        if (descriptor == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerGroup descriptor"
            );
        }
        String encoded = WorkerRedisSupport.encodeWorkerGroup(descriptor);
        if (encoded == null) {
            return result(WorkerRuntimeStatus.INVALID, "invalid descriptor json");
        }
        if (commands().hsetnx(
                groupsKey(),
                descriptor.workerGroupId(),
                encoded
        )) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        WorkerGroupDescriptor current = WorkerRedisSupport.decodeWorkerGroup(
                commands().hget(groupsKey(), descriptor.workerGroupId())
        );
        if (current == null
                || !current.workerGroupId().equals(descriptor.workerGroupId())) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker group descriptor is invalid"
            );
        }
        return current.equals(descriptor)
                ? new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)
                : result(
                        WorkerRuntimeStatus.CONFLICT,
                        "worker group is already registered with a different descriptor"
                );
    }

    @Override
    public Map<String, WorkerGroupDescriptor> sampleWorkerGroupDescriptors(
            int sampleLimit
    ) {
        if (sampleLimit < 1
                || sampleLimit > MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT) {
            throw new IllegalArgumentException(
                    "sampleLimit must be between 1 and "
                            + MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT
            );
        }
        List<KeyValue<String, String>> sampled =
                commands().hrandfieldWithvalues(groupsKey(), sampleLimit);
        LinkedHashMap<String, WorkerGroupDescriptor> result =
                new LinkedHashMap<>();
        for (KeyValue<String, String> row : sampled) {
            WorkerGroupDescriptor descriptor = row.hasValue()
                    ? WorkerRedisSupport.decodeWorkerGroup(row.getValue())
                    : null;
            result.put(
                    row.getKey(),
                    descriptor != null
                            && row.getKey().equals(descriptor.workerGroupId())
                            ? descriptor
                            : null
            );
        }
        return result;
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
        LinkedHashMap<String, WorkerGroupDescriptor> result =
                new LinkedHashMap<>();
        for (int index = 0; index < workerGroupIds.size(); index++) {
            String workerGroupId = workerGroupIds.get(index);
            KeyValue<String, String> row = loaded.get(index);
            WorkerGroupDescriptor descriptor = row.hasValue()
                    ? WorkerRedisSupport.decodeWorkerGroup(row.getValue())
                    : null;
            result.put(
                    workerGroupId,
                    descriptor != null
                            && workerGroupId.equals(descriptor.workerGroupId())
                            ? descriptor
                            : null
            );
        }
        return result;
    }

    @Override
    public Map<String, WorkerDescriptor> getWorkerDescriptors(
            String workerGroupId,
            List<String> workerIds
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireIds(workerIds, "workerIds");
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        List<KeyValue<String, String>> loaded = commands().hmget(
                WorkerRedisSupport.workerMetadataKey(keyspace, workerGroupId),
                workerIds.toArray(String[]::new)
        );
        LinkedHashMap<String, WorkerDescriptor> result = new LinkedHashMap<>();
        for (int index = 0; index < workerIds.size(); index++) {
            result.put(
                    workerIds.get(index),
                    descriptor(workerGroupId, workerIds.get(index), loaded.get(index))
            );
        }
        return result;
    }

    @Override
    public Map<String, @Nullable String> getWorkerGroupIds(
            List<String> workerIds
    ) {
        requireIds(workerIds, "workerIds");
        if (workerIds.size() > MAX_WORKER_GROUP_LOOKUP_LIMIT) {
            throw new IllegalArgumentException(
                    "workerIds must contain at most "
                            + MAX_WORKER_GROUP_LOOKUP_LIMIT + " entries"
            );
        }
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        List<KeyValue<String, String>> loaded = commands().hmget(
                WorkerRedisSupport.workerIdOwnersKey(keyspace),
                workerIds.toArray(String[]::new)
        );
        LinkedHashMap<String, @Nullable String> result = new LinkedHashMap<>();
        for (int index = 0; index < workerIds.size(); index++) {
            String owner = loaded.get(index).getValueOrElse(null);
            result.put(
                    workerIds.get(index),
                    owner == null || owner.isEmpty() ? null : owner
            );
        }
        return result;
    }

    @Override
    public Map<String, WorkerDescriptor> sampleWorkerDescriptors(
            String workerGroupId,
            int sampleLimit
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (sampleLimit < 1
                || sampleLimit > MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT) {
            throw new IllegalArgumentException(
                    "sampleLimit must be between 1 and "
                            + MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT
            );
        }
        List<KeyValue<String, String>> sampled =
                commands().hrandfieldWithvalues(
                        WorkerRedisSupport.workerMetadataKey(
                                keyspace,
                                workerGroupId
                        ),
                        sampleLimit
                );
        LinkedHashMap<String, WorkerDescriptor> result = new LinkedHashMap<>();
        for (KeyValue<String, String> row : sampled) {
            result.put(
                    row.getKey(),
                    descriptor(workerGroupId, row.getKey(), row)
            );
        }
        return result;
    }

    private static WorkerDescriptor descriptor(
            String workerGroupId,
            String workerId,
            KeyValue<String, String> row
    ) {
        WorkerMetadata metadata = row.hasValue()
                ? WorkerRedisSupport.decodeWorkerMetadata(row.getValue())
                : null;
        if (metadata == null
                || !workerId.equals(metadata.workerId())
                || !workerGroupId.equals(metadata.workerGroupId())) {
            return null;
        }
        return new WorkerDescriptor(
                metadata.workerId(),
                metadata.workerGroupId(),
                metadata.endpointManagerId()
        );
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
        return WorkerRedisSupport.groupsKey(keyspace);
    }

    private static WorkerRuntimeResult result(
            WorkerRuntimeStatus status,
            String reason
    ) {
        return new WorkerRuntimeResult(status, reason);
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
