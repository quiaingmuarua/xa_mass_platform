package com.xa.mass.kernel.task.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class RedisTaskRuntime implements TaskRuntime, AutoCloseable {

    private static final int INITIAL_PRE_REVIEW_SUFFIX = 1;
    private static final long DEFAULT_CREATION_LEASE_MILLIS = 3_000;
    private static final long DEFAULT_ITEM_TTL_MILLIS =
            365L * 24 * 60 * 60 * 1_000;
    private static final int ITEM_PRIORITY_STEP_MILLIS = 100;
    private static final String CREATE_DESCRIPTOR_SCRIPT = """
            local key = KEYS[1]
            if redis.call("EXISTS", key) == 1 then
              return 0
            end
            redis.call(
              "HSET",
              key,
              "workerGroupId", ARGV[1],
              "workerAllocationMechanism", ARGV[2],
              "idleDisposition", ARGV[3],
              "allocationRuleJson", ARGV[4],
              "configJson", ARGV[5]
            )
            return 1
            """;

    private final RedisClient redisClient;
    private final TaskScoreBandCore scoreBand;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskRuntime(
            RedisClient redisClient,
            TaskScoreBandCore scoreBand,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        this.redisClient = redisClient;
        this.scoreBand = java.util.Objects.requireNonNull(
                scoreBand,
                "scoreBand"
        );
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public TaskCreationResult createTask(TaskDescriptor descriptor) {
        if (descriptor == null) {
            return creation(
                    TaskCreationStatus.INVALID,
                    "descriptor must be present"
            );
        }
        Map<String, String> fields;
        try {
            fields = descriptorFields(descriptor);
        } catch (IllegalArgumentException | JacksonException error) {
            return creation(
                    TaskCreationStatus.INVALID,
                    "descriptor allocation rule is invalid or not JSON "
                            + "serializable"
            );
        }

        try {
            TaskCreationResult started = startOrCompleteCreation(
                    descriptor.taskId()
            );
            if (started != null) {
                return started;
            }

            var initialization = scoreBand.initializeScore(
                    descriptor.taskId(),
                    INITIAL_PRE_REVIEW_SUFFIX,
                    DEFAULT_CREATION_LEASE_MILLIS
            );
            if (initialization.status()
                    != TaskScoreTransitionStatus.TRANSITIONED
                    || initialization.score() == null) {
                return initializationFailure(
                        descriptor.taskId(),
                        fields,
                        initialization.status()
                );
            }
            long observedLease = initialization.score();

            boolean descriptorCreated;
            try {
                descriptorCreated = writeDescriptorIfAbsent(
                        descriptor.taskId(),
                        fields
                );
            } catch (RuntimeException error) {
                releaseBestEffort(descriptor.taskId(), observedLease);
                return creation(
                        TaskCreationStatus.RETRYABLE,
                        "Task descriptor could not be stored"
                );
            }
            if (!descriptorCreated) {
                releaseBestEffort(descriptor.taskId(), observedLease);
                return creation(
                        TaskCreationStatus.CONFLICT,
                        "task descriptor already exists"
                );
            }

            var release = scoreBand.releaseObservedScoreHold(
                    descriptor.taskId(),
                    observedLease
            );
            return release.status()
                    == TaskScoreTransitionStatus.TRANSITIONED
                    ? creation(TaskCreationStatus.CREATED, null)
                    : creation(
                            TaskCreationStatus.RETRYABLE,
                            "task descriptor was written but score release "
                                    + "was not accepted"
                    );
        } catch (RuntimeException error) {
            return creation(
                    TaskCreationStatus.RETRYABLE,
                    "Task creation owner is unavailable"
            );
        }
    }

    private TaskCreationResult startOrCompleteCreation(String taskId) {
        if (commands().exists(taskDescriptorKey(taskId)) > 0) {
            return creation(
                    TaskCreationStatus.CONFLICT,
                    "task descriptor already exists"
            );
        }
        return null;
    }

    private TaskCreationResult initializationFailure(
            String taskId,
            Map<String, String> fields,
            TaskScoreTransitionStatus status
    ) {
        if (status == TaskScoreTransitionStatus.NOOP) {
            var state = scoreBand.getScoreStates(List.of(taskId)).get(taskId);
            if (state != null
                    && state.band() == TaskScoreBand.PRE_REVIEW
                    && commands().exists(taskDescriptorKey(taskId)) == 0
                    && writeDescriptorIfAbsent(taskId, fields)) {
                return creation(TaskCreationStatus.CREATED, null);
            }
            return creation(
                    TaskCreationStatus.CONFLICT,
                    "task score is already initialized outside an "
                            + "incomplete creation"
            );
        }
        if (status == TaskScoreTransitionStatus.INVALID) {
            return creation(
                    TaskCreationStatus.INVALID,
                    "task score initialization was rejected"
            );
        }
        return creation(
                TaskCreationStatus.RETRYABLE,
                "task score initialization could not be confirmed"
        );
    }

    private Map<String, String> descriptorFields(
            TaskDescriptor descriptor
    ) throws JacksonException {
        if (descriptor.allocationRule() != null) {
            TaskConstraintRuleValidator.validate(
                    descriptor.allocationRule()
            );
            rejectNonFiniteNumbers(descriptor.allocationRule());
        }
        rejectNonFiniteNumbers(descriptor.config());
        String allocationRuleJson = descriptor.allocationRule() == null
                ? "null"
                : mapper.writeValueAsString(normalizeJsonValue(
                        descriptor.allocationRule()
                ));
        String configJson = mapper.writeValueAsString(
                new TreeMap<>(descriptor.config())
        );
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workerGroupId", descriptor.workerGroupId());
        fields.put(
                "workerAllocationMechanism",
                descriptor.workerAllocationMechanism().name()
        );
        fields.put(
                "idleDisposition",
                descriptor.idleDisposition().name()
        );
        fields.put("allocationRuleJson", allocationRuleJson);
        fields.put("configJson", configJson);
        return fields;
    }

    private boolean writeDescriptorIfAbsent(
            String taskId,
            Map<String, String> fields
    ) {
        Long result = commands().eval(
                CREATE_DESCRIPTOR_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{taskDescriptorKey(taskId)},
                fields.get("workerGroupId"),
                fields.get("workerAllocationMechanism"),
                fields.get("idleDisposition"),
                fields.get("allocationRuleJson"),
                fields.get("configJson")
        );
        return result != null && result == 1L;
    }

    private void releaseBestEffort(String taskId, long observedLease) {
        try {
            scoreBand.releaseObservedScoreHold(taskId, observedLease);
        } catch (RuntimeException ignored) {
            // The short initialization lease remains the recovery boundary.
        }
    }

    private static TaskCreationResult creation(
            TaskCreationStatus status,
            String reason
    ) {
        return new TaskCreationResult(status, reason);
    }

    @Override
    public Map<String, TaskItemAppendResult> appendItems(
            String taskId,
            List<TaskItem> items
    ) {
        requireNonBlank(taskId, "taskId");
        if (items == null) {
            throw new IllegalArgumentException("items must be present");
        }
        LinkedHashMap<String, TaskItem> orderedItems = latestItems(items);
        if (orderedItems.isEmpty()) {
            return Map.of();
        }

        Integer maxRetryTimes = loadMaxRetryTimes(taskId);
        if (maxRetryTimes == null) {
            return uniformResults(
                    orderedItems.keySet(),
                    TaskItemAppendStatus.NOT_FOUND
            );
        }
        long nowMillis = redisTimeMillis();
        var records = new LinkedHashMap<String, String>();
        var dueMillis = new LinkedHashMap<String, Long>();
        var results = new LinkedHashMap<String, TaskItemAppendResult>();
        orderedItems.forEach((messageId, item) -> {
            try {
                MaterializedItem materialized = materialize(item, nowMillis);
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
            results.putAll(initializeItemScores(
                    taskId,
                    dueMillis,
                    maxRetryTimes
            ));
        } catch (RuntimeException error) {
            records.keySet().forEach(messageId -> results.put(
                    messageId,
                    new TaskItemAppendResult(TaskItemAppendStatus.RETRYABLE)
            ));
        }
        return orderedResults(orderedItems.keySet(), results);
    }

    @Override
    public Map<String, TaskItem> loadTaskItems(
            String taskId,
            List<String> messageIds
    ) {
        throw notImplemented("load_task_items");
    }

    @Override
    public void storeTaskItemSuccessResults(
            String taskId,
            Map<String, String> results
    ) {
        throw notImplemented("store_task_item_success_results");
    }

    @Override
    public Map<String, String> loadTaskItemSuccessResults(
            String taskId,
            List<String> messageIds
    ) {
        requireNonBlank(taskId, "taskId");
        if (messageIds == null) {
            throw new IllegalArgumentException(
                    "messageIds must be present"
            );
        }
        List<String> uniqueIds = new ArrayList<>(
                new LinkedHashSet<>(messageIds)
        );
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        if (uniqueIds.stream().anyMatch(RedisTaskRuntime::isBlank)) {
            throw new IllegalArgumentException(
                    "messageIds must be non-blank"
            );
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

    private Integer loadMaxRetryTimes(String taskId) {
        String rawConfig = commands().hget(
                taskDescriptorKey(taskId),
                "configJson"
        );
        if (rawConfig == null) {
            return null;
        }
        try {
            JsonNode config = mapper.readTree(rawConfig);
            JsonNode value = config.get("maxRetryTimes");
            if (value == null
                    || !value.isTextual()
                    || !isAsciiDecimal(value.textValue())) {
                throw new IllegalArgumentException(
                        "maxRetryTimes must be decimal text"
                );
            }
            int decoded = Integer.parseInt(value.textValue());
            if (decoded < 0 || decoded > 98) {
                throw new IllegalArgumentException(
                        "maxRetryTimes must be in 0..98"
                );
            }
            return decoded;
        } catch (JacksonException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Task scheduling declaration is corrupt",
                    error
            );
        }
    }

    private MaterializedItem materialize(TaskItem item, long nowMillis) {
        rejectNonFiniteNumbers(item.payload());
        if (item.allocationRule() != null) {
            rejectNonFiniteNumbers(item.allocationRule());
        }
        long expiry;
        if (item.expireAtMillis() == null) {
            try {
                expiry = Math.addExact(
                        item.createdAtMillis(),
                        DEFAULT_ITEM_TTL_MILLIS
                );
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException("expiry overflow", error);
            }
        } else {
            expiry = item.expireAtMillis();
        }
        if (nowMillis >= expiry) {
            throw new IllegalArgumentException("TaskItem is already expired");
        }
        return new MaterializedItem(item, expiry);
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
            if (dueMillis < TaskItemScoreBandCore.MIN_TIME_MILLIS
                    || dueMillis > TaskItemScoreBandCore.MAX_TIME_MILLIS) {
                results.put(
                        messageId,
                        new TaskItemAppendResult(TaskItemAppendStatus.INVALID)
                );
            } else {
                long timeSlot = dueMillis
                        / TaskItemScoreBandCore.SLOT_MILLIS;
                pending.put(
                        messageId,
                        TaskItemScoreBandCore.ACTIVE_TAG
                                * TaskItemScoreBandCore.TAG_FACTOR
                                + timeSlot
                                * TaskItemScoreBandCore.SUFFIX_FACTOR
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
        TaskItem record = item.record();
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

    private static long initialDueMillis(MaterializedItem item) {
        return Math.max(
                0,
                item.record().createdAtMillis()
                        - (long) item.record().priority()
                        * ITEM_PRIORITY_STEP_MILLIS
        );
    }

    private static LinkedHashMap<String, TaskItem> latestItems(
            List<TaskItem> items
    ) {
        var latest = new LinkedHashMap<String, TaskItem>();
        for (TaskItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException(
                        "TaskItem must be present"
                );
            }
            latest.put(item.messageId(), item);
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

    private long redisTimeMillis() {
        List<String> parts = commands().time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
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

    private String itemsKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":items";
    }

    private String itemScoreKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":item_score";
    }

    private String resultsKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":results";
    }

    private static KernelOperationNotImplementedException notImplemented(
            String operationName
    ) {
        return new KernelOperationNotImplementedException(
                "TaskRuntime",
                operationName
        );
    }

    private static void requireNonBlank(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must be non-blank");
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
            iterable.forEach(RedisTaskRuntime::rejectNonFiniteNumbers);
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

    private record MaterializedItem(
            TaskItem record,
            long expireAtMillis
    ) {
    }
}
