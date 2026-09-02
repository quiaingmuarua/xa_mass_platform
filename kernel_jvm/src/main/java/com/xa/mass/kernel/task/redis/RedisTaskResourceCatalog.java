package com.xa.mass.kernel.task.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class RedisTaskResourceCatalog
        implements TaskResourceCatalog, AutoCloseable {

    private final RedisClient redisClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskResourceCatalog(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this.redisClient = redisClient;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public Map<String, TaskDescriptor> loadTaskAllocationDescriptors(
            List<String> taskIds
    ) {
        if (taskIds == null) {
            throw new IllegalArgumentException("taskIds must be present");
        }
        var descriptors = new LinkedHashMap<String, TaskDescriptor>();
        for (String taskId : taskIds) {
            if (taskId == null || taskId.isEmpty()) {
                throw new IllegalArgumentException(
                        "taskIds must be non-empty"
                );
            }
            Map<String, String> fields = commands().hgetall(
                    taskDescriptorKey(taskId)
            );
            descriptors.put(
                    taskId,
                    fields.isEmpty() ? null : decode(taskId, fields)
            );
        }
        return descriptors;
    }

    private TaskDescriptor decode(
            String taskId,
            Map<String, String> fields
    ) {
        try {
            if (!fields.keySet().equals(Set.of(
                    "workerGroupId",
                    "workerAllocationMechanism",
                    "idleDisposition",
                    "configJson"
            ))) {
                throw new IllegalArgumentException(
                        "Task descriptor fields are invalid"
                );
            }
            String workerGroupId = required(
                    fields,
                    "workerGroupId"
            );
            WorkerAllocationMechanism allocationMechanism =
                    WorkerAllocationMechanism.valueOf(required(
                    fields,
                    "workerAllocationMechanism"
            ));
            TaskIdleDisposition idleDisposition =
                    TaskIdleDisposition.valueOf(required(
                            fields,
                            "idleDisposition"
                    ));
            Map<String, String> config = mapper.readValue(
                    required(fields, "configJson"),
                    new TypeReference<>() {
                    }
            );
            return new TaskDescriptor(
                    taskId,
                    workerGroupId,
                    allocationMechanism,
                    idleDisposition,
                    config
            );
        } catch (JacksonException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Task descriptor is corrupt",
                    error
            );
        }
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

    private String taskDescriptorKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":descriptor";
    }

    private static String required(
            Map<String, String> fields,
            String name
    ) {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value;
    }
}
