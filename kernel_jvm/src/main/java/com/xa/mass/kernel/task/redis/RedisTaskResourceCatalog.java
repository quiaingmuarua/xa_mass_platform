package com.xa.mass.kernel.task.redis;

import com.xa.mass.kernel.assignment.RefillTarget;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
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
            if (!fields.keySet().containsAll(Set.of(
                    "projectId",
                    "workerGroupId",
                    "idleDisposition",
                    "configJson",
                    "refillJson"
            )) || !Set.of("projectId", "workerGroupId", "idleDisposition", "configJson", "refillJson", "name", "metadataJson")
                    .containsAll(fields.keySet())) {
                throw new IllegalArgumentException(
                        "Task descriptor fields are invalid"
                );
            }
            String workerGroupId = required(
                    fields,
                    "workerGroupId"
            );
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
            List<Map<String, Object>> targets = mapper.readValue(
                    required(fields, "refillJson"), new TypeReference<>() {});
            if (targets == null) throw new IllegalArgumentException("refillJson must be an array");
            var declarations = new java.util.ArrayList<RefillTarget>();
            for (var target : targets) {
                if (target == null || !target.keySet().equals(Set.of("poolName", "target", "count"))
                        || !(target.get("target") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("refill target fields are invalid");
                }
                declarations.add(RefillTarget.parse(target));
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            if (fields.containsKey("metadataJson")) {
                Map<String, Object> decoded = mapper.readValue(fields.get("metadataJson"), new TypeReference<>() {});
                if (decoded == null) throw new IllegalArgumentException("Task metadata must be an object");
                decoded.forEach((key, value) -> {
                    if (!(value instanceof String text)) throw new IllegalArgumentException("Task metadata values must be strings");
                    metadata.put(key, text);
                });
            }
            return new TaskDescriptor(
                    taskId,
                    required(fields, "projectId"),
                    workerGroupId,
                    idleDisposition,
                    config,
                    declarations,
                    fields.get("name"), metadata
            );
        } catch (JacksonException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Task descriptor is corrupt",
                    error
            );
        }
    }

    @Override
    public ProjectTaskPage listProjectTasks(String projectId, int limit) {
        if (projectId == null || projectId.isBlank() || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("projectId is required and limit must be in 1..1000");
        }
        var rows = commands().zrevrangeWithScores(
                keyspace.base() + ":task:project:" + projectId, 0, limit);
        var entries = new java.util.ArrayList<ProjectTaskEntry>();
        for (var row : rows) {
            double time = row.getScore();
            if (row.getValue().isBlank() || !Double.isFinite(time)
                    || time < 0 || time != Math.floor(time) || time > 9_007_199_254_740_991d) {
                throw new IllegalStateException("Task project directory is corrupt");
            }
            if (entries.size() < limit) {
                entries.add(new ProjectTaskEntry(row.getValue(), (long) time));
            }
        }
        return new ProjectTaskPage(entries, rows.size() > limit);
    }

    @Override
    public ProjectTaskEntry getProjectTask(String projectId, String taskId) {
        if (projectId == null || projectId.isBlank() || taskId == null || taskId.isBlank())
            throw new IllegalArgumentException("Project and Task IDs are required");
        Double time = commands().zscore(keyspace.base() + ":task:project:" + projectId, taskId);
        if (time == null) return null;
        if (!Double.isFinite(time) || time < 0 || time != Math.floor(time) || time > 9_007_199_254_740_991d)
            throw new IllegalStateException("Task project directory is corrupt");
        return new ProjectTaskEntry(taskId, time.longValue());
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
