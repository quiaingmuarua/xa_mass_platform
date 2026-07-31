package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
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

public final class RedisWorkerResourceCatalog
        implements WorkerResourceCatalog, AutoCloseable {

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
                || !current.eventCodes().equals(descriptor.eventCodes())
                || !current.itemAllocationFields().equals(
                        descriptor.itemAllocationFields()
                )) {
            return new WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker group eventCodes and itemAllocationFields "
                            + "are immutable"
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
            descriptors.put(
                    workerGroupIds.get(index),
                    value.hasValue()
                            ? WorkerRedisSupport.decodeWorkerGroup(
                                    value.getValue()
                            )
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
    public WorkerRuntimeResult updateWorkerPlatformAttributes(
            String workerGroupId,
            String workerId,
            Map<String, Object> attributes
    ) {
        throw notImplemented("update_worker_platform_attributes");
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
