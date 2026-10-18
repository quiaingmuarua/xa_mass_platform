package com.xa.mass.kernel.task.redis;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore
        .TaskItemScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResultPage;
import io.lettuce.core.KeyValue;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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
            local descriptorType = redis.call("TYPE", key).ok
            if descriptorType ~= "none" and descriptorType ~= "hash" then
              return redis.error_reply("Task descriptor has invalid type")
            end
            if redis.call("EXISTS", key) == 1 then
              return 0
            end
            redis.call(
              "HSET",
              key,
              "workerGroupId", ARGV[1],
              "idleDisposition", ARGV[2],
              "configJson", ARGV[3],
              "refillJson", ARGV[4],
              "metadataJson", ARGV[5],
              "dataPhase", "OPEN"
            )
            if ARGV[6] ~= '' then redis.call('HSET', key, 'name', ARGV[6]) end
            return 1
            """;
    private static final String RESULT_CODEC_SCRIPT = """
            local function decode_result(stored)
              local ok, current = pcall(cjson.decode, stored)
              if not ok or type(current) ~= 'table' or type(current.code) ~= 'string' or current.code == ''
                  or type(current.opaqueResultPayload) ~= 'string' or current.opaqueResultPayload == '' then
                error('TaskItem Result is corrupt')
              end
              local fields = 0
              for field, _ in pairs(current) do
                if field ~= 'code' and field ~= 'opaqueResultPayload' and field ~= 'tag' and field ~= 'observedAtMillis' then
                  error('TaskItem Result is corrupt')
                end
                fields = fields + 1
              end
              if current.code == '200' then
                if fields ~= 4 or type(current.tag) ~= 'number' or current.tag < 2 or current.tag > 9
                    or current.tag ~= math.floor(current.tag) or type(current.observedAtMillis) ~= 'number'
                    or current.observedAtMillis < 0 or current.observedAtMillis > 9999999999900
                    or current.observedAtMillis ~= math.floor(current.observedAtMillis) then
                  error('TaskItem Result is corrupt')
                end
              elseif fields ~= 2 then error('TaskItem Result is corrupt') end
              return current
            end
            """;
    private static final String TAKE_RESULTS_SCRIPT = RESULT_CODEC_SCRIPT + """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN'
                or redis.call('HGET', KEYS[2], 'idleDisposition') ~= 'PARK_WHEN_IDLE' then
              return redis.error_reply('Task does not support Result consumption')
            end
            local rows = redis.call('HMGET', KEYS[1], unpack(ARGV))
            for index, value in ipairs(rows) do
              if value then
                local current = decode_result(value)
                rows[index] = {current.code, current.opaqueResultPayload}
              end
            end
            redis.call('HDEL', KEYS[1], unpack(ARGV))
            return rows
            """;
    private static final String STORE_SUCCESS_RESULTS_SCRIPT = RESULT_CODEC_SCRIPT + """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN' then return 0 end
            local updates = {}
            for i = 2, #ARGV, 2 do
              local target = cjson.decode(ARGV[i + 1])
              local stored = redis.call("HGET", KEYS[1], ARGV[i])
              local replace = true
              if stored then
                local current = decode_result(stored)
                if current.code == "200" then
                  replace = target.tag > current.tag or
                      (target.tag == current.tag and target.observedAtMillis > current.observedAtMillis)
                end
              end
              if replace then
                updates[#updates + 1] = ARGV[i]
                updates[#updates + 1] = ARGV[i + 1]
              end
            end
            if #updates > 0 then
              redis.call("HSET", KEYS[1], unpack(updates))
              if redis.call('HGET', KEYS[2], 'idleDisposition') == 'PARK_WHEN_IDLE' then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
              end
            end
            return #updates / 2
            """;
    private static final String STORE_FAILED_RESULTS_SCRIPT = """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN' then return 0 end
            local results_key = KEYS[1]
            local failed_result = ARGV[2]
            local results_type = redis.call("TYPE", results_key).ok
            if results_type ~= "none" and results_type ~= "hash" then
              return redis.error_reply("Task results key must be a hash")
            end
            local stored = 0
            for index = 3, #ARGV do
              stored = stored + redis.call(
                "HSETNX",
                results_key,
                ARGV[index],
                failed_result
              )
            end
            if stored > 0 and redis.call('HGET', KEYS[2], 'idleDisposition') == 'PARK_WHEN_IDLE' then
              redis.call('PEXPIRE', results_key, ARGV[1])
            end
            return stored
            """;

    private static final String APPEND_ITEMS_SCRIPT = """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN' then return 0 end
            redis.call('HSET', KEYS[1], unpack(ARGV))
            return 1
            """;

    // The only cross-resource transaction: seal against writers and retain the
    // descriptor until the final discoverable Score can be removed atomically.
    private static final String RETENTION_SCRIPT = """
            local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
            local phase = redis.call('HGET', KEYS[1], 'dataPhase')
            if not score and not phase and ARGV[3] == 'finish' then return 0 end
            if not score or tonumber(score) ~= tonumber(ARGV[2]) then
              return redis.error_reply('Task retention Score fence differs')
            end
            if redis.call('HGET', KEYS[1], 'idleDisposition') ~= 'CLOSE_WHEN_IDLE' then
              return redis.error_reply('Task retention requires a finite Task')
            end
            if not phase then return redis.error_reply('Task data phase is missing') end
            local op = ARGV[3]
            if op == 'freeze' then
              if phase == 'OPEN' then
                redis.call('HSET', KEYS[1], 'dataPhase', 'FROZEN', 'frozenAtMillis', ARGV[4])
                return 1
              end
              if phase == 'FROZEN' or phase == 'RELEASED' then return 0 end
            elseif op == 'release' then
              if phase == 'RELEASED' then return 0 end
              if phase == 'FROZEN' then
                redis.call('UNLINK', KEYS[3], KEYS[4])
                redis.call('HSET', KEYS[1], 'dataPhase', 'RELEASED')
                return 1
              end
            elseif op == 'begin' then
              if phase == 'PURGING' then return 0 end
              if phase == 'OPEN' or phase == 'FROZEN' or phase == 'RELEASED' then
                redis.call('HSET', KEYS[1], 'dataPhase', 'PURGING')
                return 1
              end
            elseif op == 'purge' and phase == 'PURGING' then
              return redis.call('UNLINK', KEYS[3], KEYS[4], KEYS[5])
            elseif op == 'finish' and phase == 'PURGING' then
              if redis.call('EXISTS', KEYS[3], KEYS[4], KEYS[5]) ~= 0 then
                return redis.error_reply('Task payload cleanup is incomplete')
              end
              redis.call('DEL', KEYS[1])
              redis.call('ZREM', KEYS[2], ARGV[1])
              return 1
            end
            return redis.error_reply('Task retention phase conflicts')
            """;

    private final RedisClient redisClient;
    private final TaskScoreBandCore scoreBand;
    private final TaskItemScoreBandCore itemScoreBand;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RedisKeyspace keyspace;
    private final LongSupplier currentTimeMillis;
    private final long rpcResultIdleTtlMillis;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskRuntime(
            RedisClient redisClient,
            TaskScoreBandCore scoreBand,
            TaskItemScoreBandCore itemScoreBand,
            RedisKeyspace keyspace,
            long rpcResultIdleTtlMillis
    ) {
        this(redisClient, scoreBand, itemScoreBand, keyspace, rpcResultIdleTtlMillis, System::currentTimeMillis);
    }

    public RedisTaskRuntime(
            RedisClient redisClient,
            TaskScoreBandCore scoreBand,
            TaskItemScoreBandCore itemScoreBand,
            RedisKeyspace keyspace,
            long rpcResultIdleTtlMillis,
            LongSupplier currentTimeMillis
    ) {
        if (rpcResultIdleTtlMillis <= 0) throw new IllegalArgumentException("RPC Result idle TTL must be positive");
        this.rpcResultIdleTtlMillis = rpcResultIdleTtlMillis;
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        this.redisClient = redisClient;
        this.scoreBand = java.util.Objects.requireNonNull(
                scoreBand,
                "scoreBand"
        );
        this.itemScoreBand = java.util.Objects.requireNonNull(
                itemScoreBand,
                "itemScoreBand"
        );
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
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
                    "descriptor contains a non-finite or non-JSON-"
                            + "serializable value"
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
        rejectNonFiniteNumbers(descriptor.config());
        String configJson = mapper.writeValueAsString(
                new TreeMap<>(descriptor.config())
        );
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workerGroupId", descriptor.workerGroupId());
        fields.put(
                "idleDisposition",
                descriptor.idleDisposition().name()
        );
        fields.put("configJson", configJson);
        fields.put("refillJson", mapper.writeValueAsString(descriptor.refill()));
        fields.put("metadataJson", mapper.writeValueAsString(new TreeMap<>(descriptor.metadata())));
        if (descriptor.name() != null) fields.put("name", descriptor.name());
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
                fields.get("idleDisposition"),
                fields.get("configJson"),
                fields.get("refillJson"),
                fields.get("metadataJson"),
                fields.getOrDefault("name", "")
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

        TaskAppendPolicy appendPolicy = loadAppendPolicy(taskId);
        if (appendPolicy == null) {
            return uniformResults(
                    orderedItems.keySet(),
                    TaskItemAppendStatus.NOT_FOUND
            );
        }
        long nowMillis = currentTimeMillis.getAsLong();
        var records = new LinkedHashMap<String, String>();
        var dueMillis = new LinkedHashMap<String, Long>();
        var results = new LinkedHashMap<String, TaskItemAppendResult>();
        orderedItems.forEach((messageId, item) -> {
            try {
                MaterializedItem materialized = materialize(
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

        long storedAt = TaskStorageEvent.start();
        try {
            var arguments = new ArrayList<String>();
            records.forEach((id, encoded) -> { arguments.add(id); arguments.add(encoded); });
            Long accepted = commands().eval(APPEND_ITEMS_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{itemsKey(taskId), taskDescriptorKey(taskId)}, arguments.toArray(String[]::new));
            if (accepted == null || accepted != 1) return uniformResults(orderedItems.keySet(), TaskItemAppendStatus.NOT_FOUND);
            TaskStorageEvent.items(storedAt, "ITEM_STORED", taskId, records.keySet(), records.size(), false);
        } catch (RuntimeException error) {
            TaskStorageEvent.items(storedAt, "ITEM_STORED", taskId, records.keySet(), 0, true);
            records.keySet().forEach(messageId -> results.put(
                    messageId,
                    new TaskItemAppendResult(TaskItemAppendStatus.RETRYABLE)
            ));
            return orderedResults(orderedItems.keySet(), results);
        }

        long initializedAt = TaskStorageEvent.start();
        try {
            itemScoreBand.initializeItemScores(
                    taskId,
                    dueMillis,
                    appendPolicy.maxRetryTimes()
            ).forEach((messageId, result) -> results.put(
                    messageId,
                    switch (result.status()) {
                        case TRANSITIONED, NOOP ->
                                new TaskItemAppendResult(
                                        TaskItemAppendStatus.APPENDED
                                );
                        case INVALID -> new TaskItemAppendResult(
                                TaskItemAppendStatus.INVALID
                        );
                        default -> new TaskItemAppendResult(
                                TaskItemAppendStatus.RETRYABLE
                        );
                    }
            ));
            if (initializedAt != 0) TaskStorageEvent.items(initializedAt, "ITEM_INITIALIZED", taskId, dueMillis.keySet(),
                    (int) results.values().stream().filter(r -> r.status() == TaskItemAppendStatus.APPENDED).count(), false);
        } catch (RuntimeException error) {
            TaskStorageEvent.items(initializedAt, "ITEM_INITIALIZED", taskId, dueMillis.keySet(), 0, true);
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
                itemsKey(taskId),
                uniqueIds.toArray(String[]::new)
        );
        LinkedHashMap<String, TaskItem> results = new LinkedHashMap<>();
        for (int index = 0; index < uniqueIds.size(); index++) {
            KeyValue<String, String> value = loaded.get(index);
            results.put(
                    uniqueIds.get(index),
                    value.hasValue()
                            ? decodeTaskItem(
                                    uniqueIds.get(index),
                                    value.getValue()
                            )
                            : null
            );
        }
        return results;
    }

    @Override
    public void storeTaskItemSuccessResults(
            String taskId,
            Map<String, TaskItemSuccessResult> results
    ) {
        requireNonBlank(taskId, "taskId");
        if (results == null || results.size() > TaskItemScoreBandCore.MAX_ITEM_BATCH_SIZE) {
            throw new IllegalArgumentException("results must contain at most 100 entries");
        }
        if (results.isEmpty()) {
            return;
        }
        List<String> arguments = new ArrayList<>(results.size() * 2 + 1);
        arguments.add(Long.toString(rpcResultIdleTtlMillis));
        results.forEach((messageId, result) -> {
            requireNonBlank(messageId, "messageId");
            if (result == null) {
                throw new IllegalArgumentException("result must be present");
            }
            arguments.add(messageId);
            arguments.add(mapper.writeValueAsString(new TreeMap<>(Map.of(
                    "code", "200", "opaqueResultPayload", result.opaqueResultPayload(),
                    "tag", result.tag(), "observedAtMillis", result.observedAtMillis()))));
        });
        commands().eval(STORE_SUCCESS_RESULTS_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{resultsKey(taskId), taskDescriptorKey(taskId)}, arguments.toArray(String[]::new));
    }

    @Override
    public void storeTaskItemFailedResults(
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
            return;
        }
        if (uniqueIds.stream().anyMatch(RedisTaskRuntime::isBlank)) {
            throw new IllegalArgumentException(
                    "messageIds must be non-blank"
            );
        }
        List<String> arguments = new ArrayList<>(uniqueIds.size() + 2);
        arguments.add(Long.toString(rpcResultIdleTtlMillis));
        arguments.add(encodeTaskItemResult(TaskItemResult.failed()));
        arguments.addAll(uniqueIds);
        commands().eval(
                STORE_FAILED_RESULTS_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{resultsKey(taskId), taskDescriptorKey(taskId)},
                arguments.toArray(String[]::new)
        );
    }

    @Override
    public Map<String, TaskItemResult> takeTaskItemResults(String taskId, List<String> messageIds) {
        requireNonBlank(taskId, "taskId");
        if (messageIds == null || messageIds.isEmpty() || messageIds.size() > 1000
                || messageIds.stream().anyMatch(RedisTaskRuntime::isBlank))
            throw new IllegalArgumentException("Expected 1..1000 non-blank messageIds");
        var ids = new ArrayList<>(new LinkedHashSet<>(messageIds));
        List<List<String>> values = commands().eval(TAKE_RESULTS_SCRIPT, ScriptOutputType.MULTI,
                new String[]{resultsKey(taskId), taskDescriptorKey(taskId)}, ids.toArray(String[]::new));
        var results = new LinkedHashMap<String, TaskItemResult>();
        for (int index = 0; index < ids.size(); index++) {
            List<String> value = values.get(index);
            results.put(ids.get(index), value == null ? null : new TaskItemResult(value.get(0), value.get(1)));
        }
        return results;
    }

    @Override
    public Map<String, TaskItemResult> loadTaskItemResults(
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
        var results = new LinkedHashMap<String, TaskItemResult>();
        for (int index = 0; index < uniqueIds.size(); index++) {
            String messageId = uniqueIds.get(index);
            KeyValue<String, String> value = loaded.get(index);
            results.put(
                    messageId,
                    value.hasValue()
                            ? decodeTaskItemResult(value.getValue())
                            : null
            );
        }
        return results;
    }

    @Override
    public TaskItemResultPage scanTaskItemResults(
            String taskId,
            String cursor,
            int countHint
    ) {
        requireNonBlank(taskId, "taskId");
        if (!isAsciiDecimal(cursor)) {
            throw new IllegalArgumentException(
                    "cursor must be decimal text"
            );
        }
        if (countHint < 1
                || countHint > MAX_RESULT_SCAN_COUNT_HINT) {
            throw new IllegalArgumentException(
                    "countHint must be in 1..1000"
            );
        }
        MapScanCursor<String, String> page = commands().hscan(
                resultsKey(taskId),
                ScanCursor.of(cursor),
                new ScanArgs().limit(countHint)
        );
        LinkedHashMap<String, TaskItemResult> results = new LinkedHashMap<>();
        page.getMap().forEach((messageId, encoded) -> results.put(
                messageId,
                decodeTaskItemResult(encoded)
        ));
        return new TaskItemResultPage(
                page.getCursor(),
                results
        );
    }

    @Override
    public TaskItemPage scanTaskItems(String taskId, String cursor, int countHint) {
        requireNonBlank(taskId, "taskId");
        if (!isAsciiDecimal(cursor) || countHint < 1 || countHint > 1000)
            throw new IllegalArgumentException("Expected decimal cursor and countHint in 1..1000");
        var page = commands().hscan(itemsKey(taskId), ScanCursor.of(cursor), new ScanArgs().limit(countHint));
        var items = new LinkedHashMap<String, TaskItem>();
        page.getMap().forEach((id, encoded) -> items.put(id, decodeTaskItem(id, encoded)));
        return new TaskItemPage(page.getCursor(), items);
    }

    private String encodeTaskItemResult(TaskItemResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JacksonException error) {
            throw new IllegalStateException(
                    "TaskItem Result cannot be encoded",
                    error
            );
        }
    }

    private TaskItemResult decodeTaskItemResult(String encoded) {
        try {
            JsonNode value = mapper.readTree(encoded);
            if (value == null || !value.isObject()
                    || !value.hasNonNull("code") || !value.get("code").isTextual()
                    || !value.hasNonNull("opaqueResultPayload") || !value.get("opaqueResultPayload").isTextual()) {
                throw new IllegalArgumentException("Result fields are invalid");
            }
            TaskItemResult result = new TaskItemResult(value.get("code").textValue(),
                    value.get("opaqueResultPayload").textValue());
            if (result.succeeded()) {
                if (value.size() != 4 || !value.hasNonNull("tag") || !value.get("tag").isIntegralNumber()
                        || !value.get("tag").canConvertToInt() || !value.hasNonNull("observedAtMillis")
                        || !value.get("observedAtMillis").isIntegralNumber()
                        || !value.get("observedAtMillis").canConvertToLong()) {
                    throw new IllegalArgumentException("Result ordering is invalid");
                }
                new TaskItemSuccessResult(value.get("tag").intValue(),
                        value.get("observedAtMillis").longValue(), result.opaqueResultPayload());
            } else if (value.size() != 2) {
                throw new IllegalArgumentException("Failed Result fields are invalid");
            }
            return result;
        } catch (JacksonException | IllegalArgumentException error) {
            throw new IllegalStateException("TaskItem Result is corrupt", error);
        }
    }

    private TaskAppendPolicy loadAppendPolicy(String taskId) {
        List<KeyValue<String, String>> fields = commands().hmget(
                taskDescriptorKey(taskId),
                "configJson"
        );
        if (fields.stream().allMatch(field -> !field.hasValue())) {
            return null;
        }
        try {
            if (fields.stream().anyMatch(field -> !field.hasValue())) {
                throw new IllegalArgumentException(
                        "Task append policy fields are incomplete"
                );
            }
            JsonNode config = mapper.readTree(fields.get(0).getValue());
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
            return new TaskAppendPolicy(decoded);
        } catch (JacksonException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Task scheduling declaration is corrupt",
                    error
            );
        }
    }

    private MaterializedItem materialize(
            TaskItem item,
            long nowMillis
    ) {
        rejectNonFiniteNumbers(item.payload());
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

    private String encodeItem(MaterializedItem item)
            throws JacksonException {
        TaskItem record = item.record();
        var payload = new TreeMap<String, Object>();
        payload.put("eventCode", record.eventCode());
        payload.put("payload", normalizeJsonValue(record.payload()));
        payload.put("priority", record.priority());
        payload.put("createdAtMillis", record.createdAtMillis());
        payload.put("expireAtMillis", item.expireAtMillis());
        payload.put("workerSelector", record.workerSelector());
        return mapper.writeValueAsString(payload);
    }

    private TaskItem decodeTaskItem(String messageId, String encoded) {
        try {
            JsonNode item = mapper.readTree(encoded);
            if (item == null || !item.isObject()
                    || !Set.copyOf(item.propertyNames()).equals(Set.of(
                            "eventCode",
                            "payload",
                            "priority",
                            "createdAtMillis",
                            "expireAtMillis",
                            "workerSelector"
                    ))) {
                return null;
            }
            JsonNode eventCode = item.get("eventCode");
            JsonNode payload = item.get("payload");
            JsonNode priority = item.get("priority");
            JsonNode createdAt = item.get("createdAtMillis");
            JsonNode expireAt = item.get("expireAtMillis");
            JsonNode selector = item.get("workerSelector");
            if (eventCode == null || !eventCode.isTextual()
                    || payload == null || !payload.isObject()
                    || priority == null || !priority.isIntegralNumber()
                    || createdAt == null || !createdAt.isIntegralNumber()
                    || expireAt == null || !expireAt.isIntegralNumber()
                    || selector == null || !selector.isObject()) {
                return null;
            }
            Map<String, Object> payloadMap = mapper.convertValue(
                    payload,
                    new TypeReference<>() {
                    }
            );
            Map<String, Object> expression = mapper.convertValue(selector, new TypeReference<>() { });
            return new TaskItem(
                    messageId,
                    eventCode.textValue(),
                    createdAt.longValue(),
                    payloadMap,
                    priority.intValue(),
                    expireAt.longValue(),
                    WorkerQuery.parse(expression)
            );
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
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

    @Override
    public Map<String, TaskDataState> loadTaskDataStates(List<String> taskIds) {
        if (taskIds == null || taskIds.size() > 100 || taskIds.stream().anyMatch(RedisTaskRuntime::isBlank))
            throw new IllegalArgumentException("Expected at most 100 Task IDs");
        var pending = new LinkedHashMap<String, io.lettuce.core.RedisFuture<List<KeyValue<String, String>>>>();
        taskIds.forEach(id -> pending.put(id, connection().async().hmget(taskDescriptorKey(id), "dataPhase", "frozenAtMillis", "idleDisposition")));
        var result = new LinkedHashMap<String, TaskDataState>();
        pending.forEach((id, future) -> {
            var row = future.toCompletableFuture().join();
            if (!row.get(0).hasValue()) {
                if (row.get(2).hasValue()) throw new IllegalStateException("Task data phase is missing");
                result.put(id, null);
            } else result.put(id, new TaskDataState(TaskDataPhase.valueOf(row.get(0).getValue()),
                    row.get(1).hasValue() ? Long.valueOf(row.get(1).getValue()) : null));
        });
        return result;
    }

    @Override public boolean freezeTaskData(String id, long score) { return retain(id, score, "freeze"); }
    @Override public boolean releaseTaskPayloads(String id, long score) { return retain(id, score, "release"); }
    @Override public boolean beginTaskPurge(String id, long score) { return retain(id, score, "begin"); }
    @Override public boolean purgeTaskData(String id, long score) { return retain(id, score, "purge"); }
    @Override public boolean finishTaskPurge(String id, long score) { return retain(id, score, "finish"); }

    private boolean retain(String taskId, long observedScore, String operation) {
        requireNonBlank(taskId, "taskId");
        // Score interpretation remains with its Owner. The resource script only
        // exact-compares this observation at the atomic phase transition.
        var observed = scoreBand.getScoreStates(List.of(taskId)).get(taskId);
        if (observed != null && (observed.band() != TaskScoreBand.TERMINAL || observed.score() != observedScore)
                || observed == null && !operation.equals("finish"))
            throw new IllegalStateException("Task retention Score fence differs");
        Long changed = commands().eval(RETENTION_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{taskDescriptorKey(taskId), TaskRedisKeys.scores(keyspace), itemsKey(taskId),
                        resultsKey(taskId), TaskRedisKeys.itemScores(keyspace, taskId)},
                taskId, Long.toString(observedScore), operation, Long.toString(currentTimeMillis.getAsLong()));
        if (changed == null) throw new IllegalStateException("Task retention outcome was not confirmed");
        return changed > 0;
    }

    private String taskDescriptorKey(String taskId) {
        return TaskRedisKeys.descriptor(keyspace, taskId);
    }

    private String itemsKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":items";
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

    private record TaskAppendPolicy(
            int maxRetryTimes
    ) {
    }
}
