package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
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

public final class RedisTaskItemScoreBandCore
        implements TaskItemScoreBandCore, AutoCloseable {

    private static final int MAX_RETRY_TIMES = 98;
    private static final String CAS_UPDATE_SCRIPT = """
            local key = KEYS[1]
            local message_id = ARGV[1]
            local observed_score = tonumber(ARGV[2])
            local next_score = tonumber(ARGV[3])

            local stored = redis.call("ZSCORE", key, message_id)
            if not stored then
              return {"not_found"}
            end
            if tonumber(stored) ~= observed_score then
              return {"stale"}
            end
            redis.call("ZADD", key, next_score, message_id)
            return {"transitioned", next_score}
            """;
    private static final String PROMOTE_OUTCOMES_SCRIPT = """
            local key = KEYS[1]
            local tag_factor = tonumber(ARGV[1])
            local suffix_factor = tonumber(ARGV[2])
            local results = {}
            for i = 3, #ARGV, 2 do
              local target_score = tonumber(ARGV[i + 1])
              local stored = redis.call("ZSCORE", key, ARGV[i])
              if not stored then
                results[#results + 1] = {"not_found"}
              else
                local current = tonumber(stored)
                if not current or current < tag_factor
                    or current >= 10 * tag_factor
                    or current ~= math.floor(current)
                    or (current >= 2 * tag_factor
                        and current % suffix_factor ~= 0) then
                  results[#results + 1] = {"corrupt"}
                elseif target_score > current then
                  redis.call("ZADD", key, target_score, ARGV[i])
                  results[#results + 1] = {"transitioned", target_score}
                else
                  results[#results + 1] = {"noop", current}
                end
              end
            end
            return results
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
            Map<String, TaskItemOutcomeTarget> targets
    ) {
        if (targets == null) {
            throw new IllegalArgumentException("targets must be present");
        }
        if (targets.isEmpty()) {
            return Map.of();
        }
        if (isBlank(taskId) || targets.size() > MAX_ITEM_BATCH_SIZE) {
            return uniformResults(
                    targets.keySet(),
                    TaskItemScoreTransitionStatus.INVALID
            );
        }
        List<String> orderedMessageIds = new ArrayList<>();
        Map<String, TaskItemScoreTransitionResult> immediate = new LinkedHashMap<>();
        List<String> arguments = new ArrayList<>(targets.size() * 2 + 2);
        arguments.add(Long.toString(TAG_FACTOR));
        arguments.add(Long.toString(SUFFIX_FACTOR));
        targets.forEach((messageId, target) -> {
            if (isBlank(messageId) || target == null
                    || target.tag() < MIN_TERMINAL_TAG || target.tag() > MAX_TERMINAL_TAG
                    || !validTimeMillis(target.timeMillis())) {
                immediate.put(messageId, transition(TaskItemScoreTransitionStatus.INVALID));
            } else {
                orderedMessageIds.add(messageId);
                arguments.add(messageId);
                arguments.add(Long.toString(score(
                        target.tag(), target.timeMillis() / SLOT_MILLIS, TERMINAL_SUFFIX)));
            }
        });
        if (orderedMessageIds.isEmpty()) {
            return immediate;
        }
        List<?> replies = commands().eval(
                PROMOTE_OUTCOMES_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey(taskId)},
                arguments.toArray(String[]::new)
        );
        if (replies == null || replies.size() != orderedMessageIds.size()) {
            throw new IllegalStateException("TaskItem outcome batch result is invalid");
        }

        LinkedHashMap<String, TaskItemScoreTransitionResult> results =
                new LinkedHashMap<>();
        for (int index = 0; index < orderedMessageIds.size(); index++) {
            results.put(
                    orderedMessageIds.get(index),
                    scriptResult(
                            replies.get(index)
                    )
            );
        }
        return mergeResults(targets.keySet(), immediate, results);
    }

    @Override
    public Map<String, TaskItemScoreObservation> acquireItemScoreCandidates(
            String taskId,
            int limit
    ) {
        if (isBlank(taskId) || limit <= 0) {
            return Map.of();
        }
        long nowMillis = redisTimeMillis();
        if (!validTimeMillis(nowMillis)) {
            return Map.of();
        }
        long minimumScore = score(
                ACTIVE_TAG,
                MIN_TIME_SLOT,
                MIN_REMAINING_BUDGET
        );
        long maximumScore = score(
                ACTIVE_TAG,
                nowMillis / SLOT_MILLIS,
                MAX_REMAINING_BUDGET
        );
        List<ScoredValue<String>> rows = commands().zrevrangebyscoreWithScores(
                scoreKey(taskId),
                maximumScore,
                minimumScore,
                0,
                limit
        );
        LinkedHashMap<String, TaskItemScoreObservation> observations =
                new LinkedHashMap<>();
        for (ScoredValue<String> row : rows) {
            long raw = scoreToLong(row.getScore());
            DecodedScore decoded = decodeScore(raw);
            if (decoded != null && decoded.tag() == ACTIVE_TAG) {
                observations.put(
                        row.getValue(),
                        new TaskItemScoreObservation(raw, decoded.suffix())
                );
            }
        }
        return observations;
    }

    @Override
    public Map<String, Boolean> hasDueActiveItems(List<String> taskIds) {
        if (taskIds == null) {
            throw new IllegalArgumentException("taskIds must be present");
        }
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        long nowMillis = redisTimeMillis();
        if (!validTimeMillis(nowMillis)) {
            LinkedHashMap<String, Boolean> unavailable = new LinkedHashMap<>();
            taskIds.forEach(taskId -> unavailable.put(taskId, false));
            return unavailable;
        }
        return hasItemsInRange(
                taskIds,
                score(ACTIVE_TAG, MIN_TIME_SLOT, MIN_REMAINING_BUDGET),
                score(
                        ACTIVE_TAG,
                        nowMillis / SLOT_MILLIS,
                        MAX_REMAINING_BUDGET
                )
        );
    }

    @Override
    public Map<String, Boolean> hasActiveItems(List<String> taskIds) {
        if (taskIds == null) {
            throw new IllegalArgumentException("taskIds must be present");
        }
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return hasItemsInRange(
                taskIds,
                score(ACTIVE_TAG, MIN_TIME_SLOT, MIN_REMAINING_BUDGET),
                score(
                        ACTIVE_TAG,
                        MAX_TIME_SLOT,
                        MAX_REMAINING_BUDGET
                )
        );
    }

    @Override
    public Map<String, TaskItemScoreTransitionResult>
            rewriteObservedItemScores(
                    String taskId,
                    Map<String, Long> observedScores,
                    long targetTimeMillis,
                    int remainingBudgetDelta
            ) {
        if (observedScores == null) {
            throw new IllegalArgumentException(
                    "observedScores must be present"
            );
        }
        if (observedScores.isEmpty()) {
            return Map.of();
        }
        if (isBlank(taskId)
                || !validTimeMillis(targetTimeMillis)
                || remainingBudgetDelta != -1
                && remainingBudgetDelta != 0) {
            return uniformResults(
                    observedScores.keySet(),
                    TaskItemScoreTransitionStatus.INVALID
            );
        }
        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;
        LinkedHashMap<String, TaskItemScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, ScoreUpdate> pending = new LinkedHashMap<>();
        observedScores.forEach((messageId, observedScore) -> {
            DecodedScore decoded = observedScore == null
                    ? null
                    : decodeScore(observedScore);
            if (isBlank(messageId)
                    || decoded == null
                    || decoded.tag() != ACTIVE_TAG) {
                immediate.put(
                        messageId,
                        transition(TaskItemScoreTransitionStatus.INVALID)
                );
                return;
            }
            int targetBudget = decoded.suffix() + remainingBudgetDelta;
            if (targetBudget < MIN_REMAINING_BUDGET
                    || targetBudget > decoded.suffix()
                    || targetTimeSlot <= decoded.timeSlot()) {
                immediate.put(
                        messageId,
                        transition(TaskItemScoreTransitionStatus.INVALID)
                );
                return;
            }
            pending.put(
                    messageId,
                    new ScoreUpdate(
                            observedScore,
                            score(ACTIVE_TAG, targetTimeSlot, targetBudget)
                    )
            );
        });

        LinkedHashMap<String, TaskItemScoreTransitionResult> persisted =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            RedisAsyncCommands<String, String> async = connection().async();
            List<RedisFuture<Object>> futures = new ArrayList<>(pending.size());
            String key = scoreKey(taskId);
            pending.forEach((messageId, update) -> futures.add(async.eval(
                    CAS_UPDATE_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    messageId,
                    Long.toString(update.observed()),
                    Long.toString(update.next())
            )));
            int index = 0;
            for (String messageId : pending.keySet()) {
                persisted.put(
                        messageId,
                        scriptResult(futures.get(index++)
                                .toCompletableFuture().join())
                );
            }
        }
        return mergeResults(observedScores.keySet(), immediate, persisted);
    }

    @Override
    public Map<String, TaskItemScoreState> getItemScoreStates(
            String taskId,
            List<String> messageIds
    ) {
        if (isBlank(taskId) || messageIds == null
                || messageIds.size() > MAX_ITEM_BATCH_SIZE
                || messageIds.stream().anyMatch(RedisTaskItemScoreBandCore::isBlank)) {
            throw new IllegalArgumentException("TaskItem score query is invalid");
        }
        List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(messageIds));
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        List<Double> scores = commands().zmscore(
                scoreKey(taskId), uniqueIds.toArray(String[]::new)
        );
        Map<String, TaskItemScoreState> states = new LinkedHashMap<>();
        for (int index = 0; index < uniqueIds.size(); index++) {
            Double stored = scores.get(index);
            TaskItemScoreState state = null;
            if (stored != null) {
                long raw = scoreToLong(stored);
                DecodedScore decoded = decodeScore(raw);
                if (decoded == null) {
                    throw new IllegalStateException("TaskItem score is invalid");
                }
                boolean active = decoded.tag() == ACTIVE_TAG;
                state = new TaskItemScoreState(
                        raw,
                        active ? TaskItemScoreBand.ACTIVE : TaskItemScoreBand.TERMINAL,
                        decoded.tag(),
                        decoded.timeSlot() * SLOT_MILLIS,
                        active ? decoded.suffix() : null
                );
            }
            states.put(uniqueIds.get(index), state);
        }
        return states;
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
            case "stale" -> TaskItemScoreTransitionStatus.STALE;
            case "corrupt" -> TaskItemScoreTransitionStatus.CORRUPT;
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

    private Map<String, Boolean> hasItemsInRange(
            List<String> taskIds,
            long minimumScore,
            long maximumScore
    ) {
        taskIds.forEach(taskId -> {
            if (isBlank(taskId)) {
                throw new IllegalArgumentException(
                        "taskId must be non-blank"
                );
            }
        });
        RedisAsyncCommands<String, String> async = connection().async();
        List<RedisFuture<List<String>>> futures = new ArrayList<>(
                taskIds.size()
        );
        for (String taskId : taskIds) {
            futures.add(async.zrangebyscore(
                    scoreKey(taskId),
                    io.lettuce.core.Range.create(minimumScore, maximumScore),
                    io.lettuce.core.Limit.create(0, 1)
            ));
        }
        LinkedHashMap<String, Boolean> results = new LinkedHashMap<>();
        for (int index = 0; index < taskIds.size(); index++) {
            results.put(
                    taskIds.get(index),
                    !futures.get(index).toCompletableFuture().join().isEmpty()
            );
        }
        return results;
    }

    private static DecodedScore decodeScore(long rawScore) {
        if (rawScore <= 0) {
            return null;
        }
        long tag = rawScore / TAG_FACTOR;
        long remainder = rawScore % TAG_FACTOR;
        long timeSlot = remainder / SUFFIX_FACTOR;
        long suffix = remainder % SUFFIX_FACTOR;
        if (tag < ACTIVE_TAG || tag > MAX_TERMINAL_TAG
                || timeSlot < MIN_TIME_SLOT
                || timeSlot > MAX_TIME_SLOT
                || tag == ACTIVE_TAG
                && (suffix < MIN_REMAINING_BUDGET
                || suffix > MAX_REMAINING_BUDGET)
                || tag != ACTIVE_TAG && suffix != TERMINAL_SUFFIX) {
            return null;
        }
        return new DecodedScore(
                Math.toIntExact(tag),
                timeSlot,
                Math.toIntExact(suffix)
        );
    }

    private long redisTimeMillis() {
        List<String> parts = commands().time();
        return Long.parseLong(parts.get(0)) * 1_000L
                + Long.parseLong(parts.get(1)) / 1_000L;
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

    private String scoreKey(String taskId) {
        return keyspace.base() + ":task:" + taskId + ":item_score";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DecodedScore(int tag, long timeSlot, int suffix) {
    }

    private record ScoreUpdate(long observed, long next) {
    }
}
