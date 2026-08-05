package com.xa.mass.kernel.worker.redis;

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

    private static final int MAX_DESCRIPTOR_CAS_ATTEMPTS = 8;

    private final RedisClient redisClient;
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
        if (descriptor == null) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerGroup descriptor"
            );
        }
        String encoded = WorkerRedisSupport.encodeWorkerGroup(descriptor);
        if (encoded == null) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "invalid descriptor json"
            );
        }
        if (commands().hsetnx(
                groupsKey(),
                descriptor.workerGroupId(),
                encoded
        )) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }

        for (int attempt = 0;
                attempt < MAX_DESCRIPTOR_CAS_ATTEMPTS;
                attempt++) {
            String observed = commands().hget(
                    groupsKey(),
                    descriptor.workerGroupId()
            );
            WorkerGroupDescriptor current =
                    WorkerRedisSupport.decodeWorkerGroup(observed);
            if (current == null) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "stored worker group descriptor is invalid"
                );
            }
            if (!current.workerGroupId().equals(
                    descriptor.workerGroupId()
            )) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.CONFLICT,
                        "stored worker group identity does not match"
                );
            }
            if (current.equals(descriptor)) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP);
            }
            if (WorkerRedisSupport.compareAndSetHashField(
                    commands(),
                    groupsKey(),
                    descriptor.workerGroupId(),
                    observed,
                    encoded
            )) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
            }
        }
        return new WorkerRuntimeResult(
                WorkerRuntimeStatus.STALE,
                "worker group descriptor changed during metadata replacement"
        );
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
            String workerGroupId = workerGroupIds.get(index);
            WorkerGroupDescriptor descriptor = value.hasValue()
                    ? WorkerRedisSupport.decodeWorkerGroup(value.getValue())
                    : null;
            descriptors.put(
                    workerGroupId,
                    descriptor != null
                            && workerGroupId.equals(
                                    descriptor.workerGroupId()
                            )
                            ? descriptor
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
        requireNonBlank(workerGroupId, "workerGroupId");
        requireIds(workerIds, "workerIds");
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        List<KeyValue<String, String>> metadataRows = commands().hmget(
                WorkerRedisSupport.workerMetadataKey(prefix, workerGroupId),
                workerIds.toArray(String[]::new)
        );
        List<KeyValue<String, String>> propertyRows = commands().hmget(
                WorkerRedisSupport.workerPropertiesKey(prefix, workerGroupId),
                workerIds.toArray(String[]::new)
        );
        var descriptors = new LinkedHashMap<String, WorkerDescriptor>();
        for (int index = 0; index < workerIds.size(); index++) {
            String workerId = workerIds.get(index);
            descriptors.put(
                    workerId,
                    composeWorkerDescriptor(
                            workerGroupId,
                            workerId,
                            metadataRows.get(index),
                            propertyRows.get(index)
                    )
            );
        }
        return descriptors;
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
                                prefix,
                                workerGroupId
                        ),
                        sampleLimit
                );
        var descriptors =
                new LinkedHashMap<String, WorkerDescriptor>();
        if (sampled.isEmpty()) {
            return descriptors;
        }
        String[] sampledWorkerIds = sampled.stream()
                .map(KeyValue::getKey)
                .toArray(String[]::new);
        List<KeyValue<String, String>> propertyRows = commands().hmget(
                WorkerRedisSupport.workerPropertiesKey(prefix, workerGroupId),
                sampledWorkerIds
        );
        for (int index = 0; index < sampled.size(); index++) {
            KeyValue<String, String> row = sampled.get(index);
            String workerId = row.getKey();
            descriptors.put(
                    workerId,
                    composeWorkerDescriptor(
                            workerGroupId,
                            workerId,
                            row,
                            propertyRows.get(index)
                    )
            );
        }
        return descriptors;
    }

    @Override
    public WorkerRuntimeResult patchWorkerPlatformProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> properties
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        if (properties == null) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "platform properties must be present"
            );
        }
        for (Map.Entry<String, @Nullable Object> entry
                : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "platform property names must be non-empty"
                );
            }
        }
        String metadataKey = WorkerRedisSupport.workerMetadataKey(
                prefix,
                workerGroupId
        );
        for (int attempt = 0;
                attempt < MAX_DESCRIPTOR_CAS_ATTEMPTS;
                attempt++) {
            String observed = commands().hget(metadataKey, workerId);
            WorkerMetadata current = WorkerRedisSupport.decodeWorkerMetadata(
                    observed
            );
            if (current == null) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.NOT_FOUND,
                        "worker descriptor not found"
                );
            }
            if (!workerId.equals(current.workerId())) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.CONFLICT,
                        "worker id mismatch"
                );
            }
            if (!workerGroupId.equals(current.workerGroupId())) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.CONFLICT,
                        "worker group mismatch"
                );
            }
            var nextPlatformProperties = new LinkedHashMap<>(
                    current.platformProperties()
            );
            for (Map.Entry<String, @Nullable Object> entry
                    : properties.entrySet()) {
                if (entry.getValue() == null) {
                    nextPlatformProperties.remove(entry.getKey());
                } else {
                    nextPlatformProperties.put(
                            entry.getKey(),
                            entry.getValue()
                    );
                }
            }
            WorkerMetadata next = new WorkerMetadata(
                    current.workerId(),
                    current.workerGroupId(),
                    current.endpointManagerId(),
                    nextPlatformProperties
            );
            String encoded = WorkerRedisSupport.encodeWorkerMetadata(next);
            if (encoded == null) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "invalid descriptor json"
                );
            }
            if (WorkerRedisSupport.compareAndSetHashField(
                    commands(),
                    metadataKey,
                    workerId,
                    observed,
                    encoded
            )) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
            }
        }
        return new WorkerRuntimeResult(
                WorkerRuntimeStatus.STALE,
                "worker metadata changed during platform property patch"
        );
    }

    private static WorkerDescriptor composeWorkerDescriptor(
            String workerGroupId,
            String workerId,
            KeyValue<String, String> metadataRow,
            KeyValue<String, String> propertyRow
    ) {
        WorkerMetadata metadata = metadataRow.hasValue()
                ? WorkerRedisSupport.decodeWorkerMetadata(
                        metadataRow.getValue()
                )
                : null;
        Map<String, Object> workerProperties = propertyRow.hasValue()
                ? WorkerRedisSupport.decodeWorkerProperties(
                        propertyRow.getValue()
                )
                : null;
        if (metadata == null
                || workerProperties == null
                || !workerId.equals(metadata.workerId())
                || !workerGroupId.equals(metadata.workerGroupId())) {
            return null;
        }
        return new WorkerDescriptor(
                metadata.workerId(),
                metadata.workerGroupId(),
                metadata.endpointManagerId(),
                workerProperties,
                metadata.platformProperties()
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
        return WorkerRedisSupport.groupsKey(prefix);
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
