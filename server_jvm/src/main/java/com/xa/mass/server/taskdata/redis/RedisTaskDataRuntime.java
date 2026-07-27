package com.xa.mass.server.taskdata.redis;

import com.xa.mass.server.kernelredis.KernelRedisProperties;
import com.xa.mass.server.taskdata.TaskDataException;
import com.xa.mass.server.taskdata.TaskDataRuntime;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemAppendResult;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemAppendStatus;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemRecord;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class RedisTaskDataRuntime implements TaskDataRuntime {

    private static final long DEFAULT_ITEM_TTL_MILLIS =
            365L * 24 * 60 * 60 * 1_000;
    private static final int ITEM_PRIORITY_STEP_MILLIS = 100;
    private static final long SLOT_MILLIS = 100;
    private static final long MAX_TIME_SLOT = 99_999_999_999L;
    private static final long MAX_TIME_MILLIS = MAX_TIME_SLOT * SLOT_MILLIS;
    private static final long SUFFIX_FACTOR = 100;
    private static final long TAG_FACTOR = (MAX_TIME_SLOT + 1) * SUFFIX_FACTOR;
    private static final int ACTIVE_TAG = 1;

    private final RedisClient redisClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskDataRuntime(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        this.redisClient = redisClient;
        this.prefix = properties.redisPrefix();
    }

    @Override
    public Map<String, TaskItemAppendResult> appendTaskItems(
            String taskId,
            List<TaskItemRecord> items
    ) {
        requireNonBlank(taskId, "taskId");
        if (items == null) {
            throw TaskDataException.invalid("items must be present");
        }
        LinkedHashMap<String, TaskItemRecord> orderedItems =
                latestItems(items);
        if (orderedItems.isEmpty()) {
            return Map.of();
        }

        TaskDefinition task = loadTaskDefinition(taskId);
        if (task == null) {
            return uniformResults(
                    orderedItems.keySet(),
                    TaskItemAppendStatus.NOT_FOUND
            );
        }
        long nowMillis = currentTimeMillis();
        var records = new LinkedHashMap<String, String>();
        var dueMillis = new LinkedHashMap<String, Long>();
        var results = new LinkedHashMap<String, TaskItemAppendResult>();

        orderedItems.forEach((messageId, item) -> {
            try {
                MaterializedItem materialized = validateAndMaterialize(
                        task,
                        item,
                        nowMillis
                );
                records.put(messageId, encodeItem(materialized));
                dueMillis.put(messageId, initialDueMillis(materialized));
            } catch (IllegalArgumentException | JacksonException error) {
                results.put(
                        messageId,
                        new TaskItemAppendResult(
                                TaskItemAppendStatus.INVALID,
                                "TaskItem is invalid or not JSON serializable"
                        )
                );
            }
        });

        if (records.isEmpty()) {
            return orderedResults(orderedItems.keySet(), results);
        }
        try {
            commands().hset(itemsKey(taskId), records);
        } catch (RuntimeException error) {
            records.keySet().forEach(messageId -> results.put(
                    messageId,
                    new TaskItemAppendResult(TaskItemAppendStatus.RETRYABLE)
            ));
            return orderedResults(orderedItems.keySet(), results);
        }

        try {
            Map<String, TaskItemAppendResult> scoreResults =
                    initializeItemScores(
                            taskId,
                            dueMillis,
                            task.maxRetryTimes()
                    );
            results.putAll(scoreResults);
        } catch (RuntimeException error) {
            records.keySet().forEach(messageId -> results.put(
                    messageId,
                    new TaskItemAppendResult(TaskItemAppendStatus.RETRYABLE)
            ));
        }
        return orderedResults(orderedItems.keySet(), results);
    }

    @Override
    public Map<String, String> loadTaskItemSuccessResults(
            String taskId,
            List<String> messageIds
    ) {
        requireNonBlank(taskId, "taskId");
        if (messageIds == null || messageIds.isEmpty()) {
            throw TaskDataException.invalid(
                    "messageIds must contain 1..1000 values"
            );
        }
        if (messageIds.size() > 1000
                || messageIds.stream().anyMatch(RedisTaskDataRuntime::isBlank)) {
            throw TaskDataException.invalid(
                    "messageIds must contain 1..1000 non-blank values"
            );
        }
        List<String> uniqueIds = new ArrayList<>(
                new LinkedHashSet<>(messageIds)
        );
        if (commands().exists(taskDescriptorKey(taskId)) == 0) {
            throw TaskDataException.notFound("Task was not found");
        }
        List<KeyValue<String, String>> loaded = commands().hmget(
                resultsKey(taskId),
                uniqueIds.toArray(String[]::new)
        );
        var results = new LinkedHashMap<String, String>();
        for (int index = 0; index < uniqueIds.size(); index++) {
            KeyValue<String, String> value = loaded.get(index);
            results.put(
                    uniqueIds.get(index),
                    value.hasValue() ? value.getValue() : null
            );
        }
        return results;
    }

    private TaskDefinition loadTaskDefinition(String taskId) {
        Map<String, String> descriptor = commands().hgetall(
                taskDescriptorKey(taskId)
        );
        if (descriptor.isEmpty()) {
            return null;
        }
        try {
            String workerGroupId = requireStoredText(
                    descriptor.get("workerGroupId"),
                    "workerGroupId"
            );
            TaskType taskType = TaskType.valueOf(requireStoredText(
                    descriptor.get("taskType"),
                    "taskType"
            ));
            int maxRetryTimes = decodeMaxRetryTimes(
                    descriptor.get("configJson")
            );
            Set<String> itemAllocationFields = Set.of();
            if (taskType == TaskType.ITEM_DRIVEN) {
                String rawGroup = commands().hget(groupsKey(), workerGroupId);
                if (rawGroup == null) {
                    return new TaskDefinition(
                            taskType,
                            maxRetryTimes,
                            Set.of()
                    );
                }
                itemAllocationFields = decodeItemAllocationFields(rawGroup);
            }
            return new TaskDefinition(
                    taskType,
                    maxRetryTimes,
                    itemAllocationFields
            );
        } catch (IllegalArgumentException | JacksonException error) {
            throw TaskDataException.unavailable(
                    new IllegalStateException(
                            "Task scheduling declaration is corrupt",
                            error
                    )
            );
        }
    }

    private MaterializedItem validateAndMaterialize(
            TaskDefinition task,
            TaskItemRecord item,
            long nowMillis
    ) {
        if (item == null
                || isBlank(item.messageId())
                || isBlank(item.eventCode())
                || item.payload() == null
                || item.createdAtMillis() < 0
                || item.priority() < 0
                || item.priority() > 10) {
            throw new IllegalArgumentException("invalid TaskItem");
        }
        rejectNonFiniteNumbers(item.payload());

        Long explicitExpiry = item.expireAtMillis();
        long expiry;
        if (explicitExpiry == null) {
            try {
                expiry = Math.addExact(
                        item.createdAtMillis(),
                        DEFAULT_ITEM_TTL_MILLIS
                );
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException("expiry overflow", error);
            }
        } else {
            expiry = explicitExpiry;
        }
        if (expiry <= item.createdAtMillis() || nowMillis >= expiry) {
            throw new IllegalArgumentException("TaskItem is already expired");
        }

        if (task.taskType() == TaskType.TASK_DRIVEN) {
            if (item.allocationRule() != null) {
                throw new IllegalArgumentException(
                        "TASK_DRIVEN forbids a TaskItem allocation rule"
                );
            }
        } else {
            validateWorkerIdRule(
                    item.allocationRule(),
                    task.itemAllocationFields()
            );
        }
        return new MaterializedItem(item, expiry);
    }

    private static void validateWorkerIdRule(
            Map<String, Object> rule,
            Set<String> allowedFields
    ) {
        if (rule == null || rule.size() != 1 || !rule.containsKey("workerId")) {
            throw new IllegalArgumentException(
                    "ITEM_DRIVEN requires a workerId allocation rule"
            );
        }
        if (!allowedFields.contains("workerId")) {
            throw new IllegalArgumentException(
                    "workerId is not allowed by WorkerGroup"
            );
        }
        Object rawOperatorRule = rule.get("workerId");
        if (!(rawOperatorRule instanceof Map<?, ?> operatorRule)
                || operatorRule.size() != 1) {
            throw new IllegalArgumentException(
                    "workerId target requires exactly one operator"
            );
        }
        Map.Entry<?, ?> operator = operatorRule.entrySet().iterator().next();
        if ("$eq".equals(operator.getKey())) {
            if (!(operator.getValue() instanceof String workerId)
                    || workerId.isBlank()) {
                throw new IllegalArgumentException(
                        "workerId $eq requires a non-blank value"
                );
            }
            return;
        }
        if ("$in".equals(operator.getKey())
                && operator.getValue() instanceof List<?> workerIds
                && !workerIds.isEmpty()
                && workerIds.stream().allMatch(
                        value -> value instanceof String workerId
                                && !workerId.isBlank()
                )) {
            return;
        }
        throw new IllegalArgumentException(
                "workerId target only supports $eq or $in"
        );
    }

    private Map<String, TaskItemAppendResult> initializeItemScores(
            String taskId,
            Map<String, Long> dueMillisByMessageId,
            int maxRetryTimes
    ) {
        int remainingBudget = 1 + maxRetryTimes;
        var results = new LinkedHashMap<String, TaskItemAppendResult>();
        var pending = new LinkedHashMap<String, Long>();
        dueMillisByMessageId.forEach((messageId, dueMillis) -> {
            if (dueMillis < 0 || dueMillis > MAX_TIME_MILLIS) {
                results.put(
                        messageId,
                        new TaskItemAppendResult(TaskItemAppendStatus.INVALID)
                );
            } else {
                long timeSlot = dueMillis / SLOT_MILLIS;
                pending.put(
                        messageId,
                        ACTIVE_TAG * TAG_FACTOR
                                + timeSlot * SUFFIX_FACTOR
                                + remainingBudget
                );
            }
        });
        if (pending.isEmpty()) {
            return results;
        }

        RedisAsyncCommands<String, String> async = connection().async();
        var futures = new LinkedHashMap<String, RedisFuture<Long>>();
        pending.forEach((messageId, score) -> futures.put(
                messageId,
                async.zadd(
                        itemScoreKey(taskId),
                        ZAddArgs.Builder.nx(),
                        score.doubleValue(),
                        messageId
                )
        ));
        for (Map.Entry<String, RedisFuture<Long>> entry : futures.entrySet()) {
            try {
                entry.getValue().get();
                results.put(
                        entry.getKey(),
                        new TaskItemAppendResult(
                                TaskItemAppendStatus.APPENDED
                        )
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Item score initialization was interrupted",
                        error
                );
            } catch (ExecutionException error) {
                throw new IllegalStateException(
                        "Item score initialization failed",
                        error.getCause()
                );
            }
        }
        return results;
    }

    private String encodeItem(MaterializedItem item)
            throws JacksonException {
        TaskItemRecord record = item.record();
        var payload = new TreeMap<String, Object>();
        payload.put("eventCode", record.eventCode());
        payload.put("payload", normalizeJsonValue(record.payload()));
        payload.put("priority", record.priority());
        payload.put("createdAtMillis", record.createdAtMillis());
        payload.put("expireAtMillis", item.expireAtMillis());
        payload.put(
                "allocationRule",
                normalizeJsonValue(record.allocationRule())
        );
        return mapper.writeValueAsString(payload);
    }

    private long currentTimeMillis() {
        List<String> parts = commands().time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }

    private static long initialDueMillis(MaterializedItem item) {
        return Math.max(
                0,
                item.record().createdAtMillis()
                        - (long) item.record().priority()
                        * ITEM_PRIORITY_STEP_MILLIS
        );
    }

    private int decodeMaxRetryTimes(String rawConfig)
            throws JacksonException {
        JsonNode config = mapper.readTree(requireStoredText(
                rawConfig,
                "configJson"
        ));
        JsonNode value = config.get("maxRetryTimes");
        if (value == null || !value.isTextual() || !isAsciiDecimal(value.textValue())) {
            throw new IllegalArgumentException(
                    "maxRetryTimes must be decimal text"
            );
        }
        int decoded = Integer.parseInt(value.textValue());
        if (decoded < 0 || decoded > 98) {
            throw new IllegalArgumentException("maxRetryTimes must be in 0..98");
        }
        return decoded;
    }

    private Set<String> decodeItemAllocationFields(String rawGroup)
            throws JacksonException {
        JsonNode group = mapper.readTree(rawGroup);
        JsonNode fields = group.get("itemAllocationFields");
        if (fields == null || !fields.isArray()) {
            throw new IllegalArgumentException(
                    "itemAllocationFields must be an array"
            );
        }
        var decoded = new LinkedHashSet<String>();
        for (JsonNode field : fields) {
            if (!field.isTextual() || field.textValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "itemAllocationFields must contain strings"
                );
            }
            decoded.add(field.textValue());
        }
        return Set.copyOf(decoded);
    }

    private static LinkedHashMap<String, TaskItemRecord> latestItems(
            List<TaskItemRecord> items
    ) {
        var latest = new LinkedHashMap<String, TaskItemRecord>();
        for (TaskItemRecord item : items) {
            String messageId = item == null ? null : item.messageId();
            if (messageId == null) {
                throw TaskDataException.invalid(
                        "TaskItem messageId must be present"
                );
            }
            latest.put(messageId, item);
        }
        return latest;
    }

    private static Map<String, TaskItemAppendResult> uniformResults(
            Set<String> messageIds,
            TaskItemAppendStatus status
    ) {
        var results = new LinkedHashMap<String, TaskItemAppendResult>();
        messageIds.forEach(messageId -> results.put(
                messageId,
                new TaskItemAppendResult(status)
        ));
        return results;
    }

    private static Map<String, TaskItemAppendResult> orderedResults(
            Set<String> messageIds,
            Map<String, TaskItemAppendResult> results
    ) {
        var ordered = new LinkedHashMap<String, TaskItemAppendResult>();
        messageIds.forEach(messageId -> ordered.put(
                messageId,
                results.get(messageId)
        ));
        return ordered;
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

    @PreDestroy
    void closeConnection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }

    private String taskDescriptorKey(String taskId) {
        return "tc:" + prefix + ":task:" + taskId;
    }

    private String groupsKey() {
        return "wr:" + prefix + ":groups";
    }

    private String itemsKey(String taskId) {
        return "tr:" + prefix + ":task:" + taskId + ":items";
    }

    private String itemScoreKey(String taskId) {
        return "tr:" + prefix + ":task:" + taskId + ":item-score";
    }

    private String resultsKey(String taskId) {
        return "tr:" + prefix + ":task:" + taskId + ":results";
    }

    private static String requireStoredText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value;
    }

    private static void requireNonBlank(String value, String name) {
        if (isBlank(value)) {
            throw TaskDataException.invalid(name + " must be non-blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isAsciiDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static void rejectNonFiniteNumbers(Object value) {
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException(
                    "JSON numbers must be finite"
            );
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException(
                    "JSON numbers must be finite"
            );
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings"
                    );
                }
                rejectNonFiniteNumbers(child);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(RedisTaskDataRuntime::rejectNonFiniteNumbers);
        } else if (value != null
                && !(value instanceof String)
                && !(value instanceof Number)
                && !(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "Value is not JSON serializable"
            );
        }
    }

    private static Object normalizeJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var normalized = new TreeMap<String, Object>();
            map.forEach((key, child) -> normalized.put(
                    (String) key,
                    normalizeJsonValue(child)
            ));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            var normalized = new ArrayList<>();
            iterable.forEach(
                    child -> normalized.add(normalizeJsonValue(child))
            );
            return normalized;
        }
        return value;
    }

    private enum TaskType {
        TASK_DRIVEN,
        ITEM_DRIVEN
    }

    private record TaskDefinition(
            TaskType taskType,
            int maxRetryTimes,
            Set<String> itemAllocationFields
    ) {
    }

    private record MaterializedItem(
            TaskItemRecord record,
            long expireAtMillis
    ) {
    }
}
