package com.xa.mass.kernel.task.redis;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskType;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class RedisTaskResourceCatalog
        implements TaskResourceCatalog, AutoCloseable {

    private final RedisClient redisClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskResourceCatalog(
            RedisClient redisClient,
            String prefix
    ) {
        this.redisClient = redisClient;
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.prefix = prefix;
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
            String workerGroupId = required(
                    fields,
                    "workerGroupId"
            );
            TaskType taskType = TaskType.valueOf(required(
                    fields,
                    "taskType"
            ));
            JsonNode allocationNode = mapper.readTree(required(
                    fields,
                    "allocationRuleJson"
            ));
            Map<String, Object> allocationRule = allocationNode.isNull()
                    ? null
                    : mapper.convertValue(
                            allocationNode,
                            new TypeReference<>() {
                            }
                    );
            Map<String, String> config = mapper.readValue(
                    required(fields, "configJson"),
                    new TypeReference<>() {
                    }
            );
            long emptyCloseAtMillis = Long.parseLong(required(
                    fields,
                    "emptyCloseAtMillis"
            ));
            return new TaskDescriptor(
                    taskId,
                    workerGroupId,
                    taskType,
                    allocationRule,
                    config,
                    emptyCloseAtMillis
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
        return "tc:" + prefix + ":task:" + taskId;
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
