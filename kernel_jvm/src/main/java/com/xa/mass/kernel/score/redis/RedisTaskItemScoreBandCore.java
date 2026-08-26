package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class RedisTaskItemScoreBandCore
        implements TaskItemScoreBandCore, AutoCloseable {

    private static final int MAX_RETRY_TIMES = 98;
    private static final String PROMOTE_CROSS_BAND_SCRIPT = """
            local key = KEYS[1]
            local message_id = ARGV[1]
            local target_score = tonumber(ARGV[2])
            local max_same_band_score_delta = tonumber(ARGV[3])

            local stored = redis.call("ZSCORE", key, message_id)
            if not stored then
              return {"not_found"}
            end

            local stored_score = tonumber(stored)
            if target_score - stored_score <= max_same_band_score_delta then
              return {"noop", stored_score}
            end

            redis.call("ZADD", key, target_score, message_id)
            return {"transitioned", target_score}
            """;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskItemScoreBandCore(
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
    public Map<String, TaskItemScoreTransitionResult> initializeItemScores(
            String taskId,
            Map<String, Long> initialDueMillisByMessageId,
            int maxRetryTimes
    ) {
        if (initialDueMillisByMessageId == null) {
            throw new IllegalArgumentException(
                    "initialDueMillisByMessageId must be present"
            );
        }
        if (initialDueMillisByMessageId.isEmpty()) {
            return Map.of();
        }
        if (isBlank(taskId)
                || maxRetryTimes < 0
                || maxRetryTimes > MAX_RETRY_TIMES) {
            return uniformResults(
                    initialDueMillisByMessageId.keySet(),
                    TaskItemScoreTransitionStatus.INVALID
            );
        }

        int remainingBudget = 1 + maxRetryTimes;
        LinkedHashMap<String, TaskItemScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        initialDueMillisByMessageId.forEach((messageId, dueMillis) -> {
            if (isBlank(messageId)
                    || dueMillis == null
                    || !validTimeMillis(dueMillis)) {
                immediate.put(
                        messageId,
                        transition(TaskItemScoreTransitionStatus.INVALID)
                );
            } else {
                pending.put(
                        messageId,
                        score(
                                ACTIVE_TAG,
                                dueMillis / SLOT_MILLIS,
                                remainingBudget
                        )
                );
            }
        });

        LinkedHashMap<String, TaskItemScoreTransitionResult> persisted =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            RedisAsyncCommands<String, String> async = connection().async();
            List<RedisFuture<Long>> futures = new ArrayList<>(pending.size());
            String key = scoreKey(taskId);
            pending.forEach((messageId, initialScore) -> futures.add(
                    async.zadd(
                            key,
                            ZAddArgs.Builder.nx(),
                            initialScore.doubleValue(),
                            messageId
                    )
            ));
            int index = 0;
            for (Map.Entry<String, Long> entry : pending.entrySet()) {
                Long added = futures.get(index)
                        .toCompletableFuture()
                        .join();
                persisted.put(
                        entry.getKey(),
                        added != null && added == 1L
                                ? new TaskItemScoreTransitionResult(
                                        TaskItemScoreTransitionStatus
                                                .TRANSITIONED,
                                        entry.getValue()
                                )
                                : transition(
                                        TaskItemScoreTransitionStatus.NOOP
                                )
                );
                index++;
            }
        }
        return mergeResults(
                initialDueMillisByMessageId.keySet(),
                immediate,
                persisted
        );
    }

    @Override
    public Map<String, TaskItemScoreTransitionResult> promoteItemOutcomes(
            String taskId,
            List<String> messageIds,
            TaskItemScoreBand targetBand,
            long targetTimeMillis
    ) {
        if (messageIds == null) {
            throw new IllegalArgumentException("messageIds must be present");
        }
        List<String> orderedMessageIds = new ArrayList<>(
                new LinkedHashSet<>(messageIds)
        );
        if (orderedMessageIds.isEmpty()) {
            return Map.of();
        }
        if (isBlank(taskId)
                || targetBand == null
                || !validTimeMillis(targetTimeMillis)
                || orderedMessageIds.stream().anyMatch(
                        RedisTaskItemScoreBandCore::isBlank
                )) {
            return uniformResults(
                    orderedMessageIds,
                    TaskItemScoreTransitionStatus.INVALID
            );
        }

        long targetScore = score(
                tag(targetBand),
                targetTimeMillis / SLOT_MILLIS,
                FINAL_SUFFIX
        );
        RedisAsyncCommands<String, String> async = connection().async();
        List<RedisFuture<Object>> futures = new ArrayList<>(
                orderedMessageIds.size()
        );
        String key = scoreKey(taskId);
        for (String messageId : orderedMessageIds) {
            futures.add(async.eval(
                    PROMOTE_CROSS_BAND_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    messageId,
                    Long.toString(targetScore),
                    Long.toString(MAX_SAME_BAND_SCORE_DELTA)
            ));
        }

        LinkedHashMap<String, TaskItemScoreTransitionResult> results =
                new LinkedHashMap<>();
        for (int index = 0; index < orderedMessageIds.size(); index++) {
            results.put(
                    orderedMessageIds.get(index),
                    scriptResult(
                            futures.get(index).toCompletableFuture().join()
                    )
            );
        }
        return results;
    }

    @Override
    public Map<String, TaskItemScoreObservation> acquireItemScoreCandidates(
            String taskId,
            int limit
    ) {
        throw notImplemented("acquire_item_score_candidates");
    }

    @Override
    public Map<String, Boolean> hasDueActiveItems(List<String> taskIds) {
        throw notImplemented("has_due_active_items");
    }

    @Override
    public Map<String, Boolean> hasActiveItems(List<String> taskIds) {
        throw notImplemented("has_active_items");
    }

    @Override
    public Map<String, TaskItemScoreTransitionResult>
            rewriteObservedItemScores(
                    String taskId,
                    Map<String, Long> observedScores,
                    long targetTimeMillis,
                    int remainingBudgetDelta
            ) {
        throw notImplemented("rewrite_observed_item_scores");
    }

    @Override
    public Map<String, TaskItemScoreState> getItemScoreStates(
            String taskId,
            List<String> messageIds
    ) {
        throw notImplemented("get_item_score_states");
    }

    private static int tag(TaskItemScoreBand band) {
        return switch (band) {
            case ACTIVE -> ACTIVE_TAG;
            case FINAL_FAILED -> FINAL_FAILED_TAG;
            case FINAL_SUCCESS -> FINAL_SUCCESS_TAG;
        };
    }

    private static long score(int tag, long timeSlot, int suffix) {
        return (long) tag * TAG_FACTOR
                + timeSlot * SUFFIX_FACTOR
                + suffix;
    }

    private static boolean validTimeMillis(long timeMillis) {
        return timeMillis >= MIN_TIME_MILLIS
                && timeMillis <= MAX_TIME_MILLIS;
    }

    private static Map<String, TaskItemScoreTransitionResult> uniformResults(
            Iterable<String> messageIds,
            TaskItemScoreTransitionStatus status
    ) {
        LinkedHashMap<String, TaskItemScoreTransitionResult> results =
                new LinkedHashMap<>();
        messageIds.forEach(messageId -> results.put(
                messageId,
                transition(status)
        ));
        return results;
    }

    private static Map<String, TaskItemScoreTransitionResult> mergeResults(
            Iterable<String> messageIds,
            Map<String, TaskItemScoreTransitionResult> immediate,
            Map<String, TaskItemScoreTransitionResult> persisted
    ) {
        LinkedHashMap<String, TaskItemScoreTransitionResult> results =
                new LinkedHashMap<>();
        messageIds.forEach(messageId -> results.put(
                messageId,
                immediate.containsKey(messageId)
                        ? immediate.get(messageId)
                        : persisted.get(messageId)
        ));
        return results;
    }

    private static TaskItemScoreTransitionResult transition(
            TaskItemScoreTransitionStatus status
    ) {
        return new TaskItemScoreTransitionResult(status, null);
    }

    private static TaskItemScoreTransitionResult scriptResult(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException(
                    "TaskItem score script result is invalid"
            );
        }
        TaskItemScoreTransitionStatus status = switch (
                String.valueOf(values.get(0))
        ) {
            case "transitioned" ->
                    TaskItemScoreTransitionStatus.TRANSITIONED;
            case "noop" -> TaskItemScoreTransitionStatus.NOOP;
            case "not_found" -> TaskItemScoreTransitionStatus.NOT_FOUND;
            default -> throw new IllegalStateException(
                    "TaskItem score script status is invalid"
            );
        };
        Long score = values.size() > 1 && values.get(1) != null
                ? scoreToLong(values.get(1))
                : null;
        return new TaskItemScoreTransitionResult(status, score);
    }

    private static long scoreToLong(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            long converted = number.longValue();
            if (!Double.isFinite(value) || value != (double) converted) {
                throw new IllegalStateException(
                        "TaskItem score must be an integer"
                );
            }
            return converted;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "TaskItem score must be an integer",
                    error
            );
        }
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

    private String scoreKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":item_score";
    }

    private static KernelOperationNotImplementedException notImplemented(
            String operation
    ) {
        return new KernelOperationNotImplementedException(
                "TaskItemScoreBandCore",
                operation
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
