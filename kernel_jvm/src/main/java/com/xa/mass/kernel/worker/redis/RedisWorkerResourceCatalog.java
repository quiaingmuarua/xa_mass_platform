package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
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

        WorkerGroupDescriptor current =
                WorkerRedisSupport.decodeWorkerGroup(commands().hget(
                        groupsKey(),
                        descriptor.workerGroupId()
                ));
        if (current == null) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker group descriptor is invalid"
            );
        }
        if (!current.workerGroupId().equals(descriptor.workerGroupId())
                || !current.eventCodes().equals(descriptor.eventCodes())) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker group eventCodes are immutable"
            );
        }

        commands().hset(
                groupsKey(),
                descriptor.workerGroupId(),
                encoded
        );
        return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
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
        List<KeyValue<String, String>> loaded = commands().hmget(
                WorkerRedisSupport.workersKey(prefix, workerGroupId),
                workerIds.toArray(String[]::new)
        );
        var descriptors = new LinkedHashMap<String, WorkerDescriptor>();
        for (int index = 0; index < workerIds.size(); index++) {
            String workerId = workerIds.get(index);
            KeyValue<String, String> value = loaded.get(index);
            WorkerDescriptor descriptor = value.hasValue()
                    ? WorkerRedisSupport.decodeWorker(value.getValue())
                    : null;
            if (descriptor == null
                    || !workerId.equals(descriptor.workerId())
                    || !workerGroupId.equals(descriptor.workerGroupId())) {
                descriptors.put(workerId, null);
            } else {
                descriptors.put(workerId, descriptor);
            }
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
                        WorkerRedisSupport.workersKey(
                                prefix,
                                workerGroupId
                        ),
                        sampleLimit
                );
        var descriptors =
                new LinkedHashMap<String, WorkerDescriptor>();
        for (KeyValue<String, String> row : sampled) {
            String workerId = row.getKey();
            WorkerDescriptor descriptor = row.hasValue()
                    ? WorkerRedisSupport.decodeWorker(row.getValue())
                    : null;
            if (descriptor == null
                    || !workerId.equals(descriptor.workerId())
                    || !workerGroupId.equals(
                            descriptor.workerGroupId()
                    )) {
                descriptors.put(workerId, null);
                continue;
            }
            descriptors.put(workerId, descriptor);
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
        String workersKey = WorkerRedisSupport.workersKey(
                prefix,
                workerGroupId
        );
        for (int attempt = 0;
                attempt < MAX_DESCRIPTOR_CAS_ATTEMPTS;
                attempt++) {
            String observed = commands().hget(workersKey, workerId);
            WorkerDescriptor current = WorkerRedisSupport.decodeWorker(
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
            WorkerDescriptor next = new WorkerDescriptor(
                    current.workerId(),
                    current.workerGroupId(),
                    current.endpointManagerId(),
                    current.workerProperties(),
                    nextPlatformProperties
            );
            String encoded = WorkerRedisSupport.encodeWorker(next);
            if (encoded == null) {
                return new WorkerRuntimeResult(
                        WorkerRuntimeStatus.INVALID,
                        "invalid descriptor json"
                );
            }
            if (WorkerRedisSupport.compareAndSetHashField(
                    commands(),
                    workersKey,
                    workerId,
                    observed,
                    encoded
            )) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
            }
        }
        return new WorkerRuntimeResult(
                WorkerRuntimeStatus.STALE,
                "worker descriptor changed during platform property patch"
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
