package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public final class RedisTaskItemScoreBandCore
        implements TaskItemScoreBandCore, AutoCloseable {

    private static final int MAX_RETRY_TIMES = 98;
    private static final String OBSERVE_COUNTS_SCRIPT = """
            local counts = {redis.call('ZCARD', KEYS[1])}
            for tag = 1, 9 do
              counts[#counts + 1] = redis.call('ZCOUNT', KEYS[1], ARGV[tag * 2 - 1], ARGV[tag * 2])
            end
            return counts
            """;
    private static final String CAS_UPDATE_BATCH_SCRIPT = """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN' then return {} end
            local results = {}
            for i = 1, #ARGV, 3 do
              local id = ARGV[i]
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if not stored then
                results[#results + 1] = 'not_found'
              elseif tonumber(stored) ~= tonumber(ARGV[i + 1]) then
                results[#results + 1] = 'stale'
              else
                redis.call('ZADD', KEYS[1], ARGV[i + 2], id)
                results[#results + 1] = 'transitioned'
              end
            end
            return results
            """;
    private static final String PROMOTE_OUTCOMES_SCRIPT = """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN' then return {} end
            local min_valid, max_valid = tonumber(ARGV[1]), tonumber(ARGV[2])
            local terminal_min, suffix_factor = tonumber(ARGV[3]), tonumber(ARGV[4])
            local results = {}
            for i = 5, #ARGV, 2 do
              local target_score = tonumber(ARGV[i + 1])
              local stored = redis.call('ZSCORE', KEYS[1], ARGV[i])
              if not stored then
                results[#results + 1] = {'not_found'}
              else
                local current = tonumber(stored)
                if not current or current < min_valid or current > max_valid
                    or current ~= math.floor(current)
                    or (current >= terminal_min and current % suffix_factor ~= 0) then
                  results[#results + 1] = {'corrupt'}
                elseif target_score > current then
                  redis.call('ZADD', KEYS[1], target_score, ARGV[i])
                  results[#results + 1] = {'transitioned', target_score}
                else
                  results[#results + 1] = {'noop', current}
                end
              end
            end
            return results
            """;
    private static final String INITIALIZE_BATCH_SCRIPT = """
            if redis.call('HGET', KEYS[2], 'dataPhase') ~= 'OPEN' then return {} end
            local rpc = redis.call('HGET', KEYS[2], 'idleDisposition') == 'PARK_WHEN_IDLE'
            local results = {}
            for i = 1, #ARGV, 2 do
              local score, id = ARGV[i], ARGV[i + 1]
              if rpc then
                if redis.call('ZSCORE', KEYS[1], id) then
                  results[#results + 1] = 0
                elseif redis.call('HEXISTS', KEYS[3], id) == 0 then
                  results[#results + 1] = -1
                else
                  results[#results + 1] = redis.call('ZADD', KEYS[1], 'NX', score, id)
                end
              else
                results[#results + 1] = redis.call('ZADD', KEYS[1], 'NX', score, id)
              end
            end
            return results
            """;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private final LongSupplier currentTimeMillis;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskItemScoreBandCore(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this(redisClient, keyspace, System::currentTimeMillis);
    }

    public RedisTaskItemScoreBandCore(
            RedisClient redisClient,
            RedisKeyspace keyspace,
            LongSupplier currentTimeMillis
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        this.redisClient = redisClient;
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
    public Map<String, TaskItemScoreCounts> observeItemScoreCounts(List<String> taskIds) {
        if (taskIds == null || taskIds.size() > 100 || taskIds.stream().anyMatch(RedisTaskItemScoreBandCore::isBlank)) {
            throw new IllegalArgumentException("Expected at most 100 non-blank Task IDs");
        }
        if (taskIds.isEmpty()) return Map.of();
        var ids = List.copyOf(new LinkedHashSet<>(taskIds));
        var ranges = new ArrayList<String>(18);
        for (int tag = 1; tag <= 9; tag++) {
            ranges.add(Long.toString(tag * TAG_FACTOR));
            ranges.add("(" + ((tag + 1) * TAG_FACTOR));
        }
        var async = connection().async();
        var pending = new ArrayList<RedisFuture<List<Long>>>();
        for (String id : ids) {
            pending.add(async.eval(OBSERVE_COUNTS_SCRIPT, ScriptOutputType.MULTI,
                    new String[]{scoreKey(id)}, ranges.toArray(String[]::new)));
        }
        var results = new LinkedHashMap<String, TaskItemScoreCounts>();
        for (int i = 0; i < ids.size(); i++) {
            var counts = pending.get(i).toCompletableFuture().join();
            var tags = new LinkedHashMap<Integer, Long>();
            for (int tag = 1; tag <= 9; tag++) tags.put(tag, counts.get(tag));
            results.put(ids.get(i), new TaskItemScoreCounts(counts.getFirst(), tags));
        }
        return java.util.Collections.unmodifiableMap(results);
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
            List<String> arguments = new ArrayList<>(pending.size() * 2);
            pending.forEach((messageId, initialScore) -> {
                arguments.add(Long.toString(initialScore));
                arguments.add(messageId);
            });
            List<?> replies = commands().eval(
                    INITIALIZE_BATCH_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            scoreKey(taskId),
                            TaskRedisKeys.descriptor(keyspace, taskId),
                            TaskRedisKeys.items(keyspace, taskId)
                    },
                    arguments.toArray(String[]::new)
            );
            if (replies == null || (!replies.isEmpty() && replies.size() != pending.size())) {
                throw new IllegalStateException("TaskItem initialization batch result is invalid");
            }
            if (replies.isEmpty()) {
                pending.keySet().forEach(id -> persisted.put(id, transition(TaskItemScoreTransitionStatus.NOT_FOUND)));
            } else {
                int index = 0;
                for (Map.Entry<String, Long> entry : pending.entrySet()) {
                    long added = ((Number) replies.get(index++)).longValue();
                    persisted.put(
                            entry.getKey(),
                            added == 1L
                                    ? new TaskItemScoreTransitionResult(
                                            TaskItemScoreTransitionStatus.TRANSITIONED,
                                            entry.getValue())
                                    : transition(added == -1L
                                            ? TaskItemScoreTransitionStatus.NOT_FOUND
                                            : TaskItemScoreTransitionStatus.NOOP)
                    );
                }
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
        if (isBlank(taskId)) {
            return uniformResults(
                    targets.keySet(),
                    TaskItemScoreTransitionStatus.INVALID
            );
        }
        List<String> orderedMessageIds = new ArrayList<>();
        Map<String, TaskItemScoreTransitionResult> immediate = new LinkedHashMap<>();
        List<String> arguments = new ArrayList<>(targets.size() * 2 + 4);
        arguments.add(Long.toString(score(ACTIVE_TAG, MIN_TIME_SLOT, MIN_REMAINING_BUDGET)));
        arguments.add(Long.toString(score(MAX_TERMINAL_TAG, MAX_TIME_SLOT, (int) (SUFFIX_FACTOR - 1))));
        arguments.add(Long.toString(score(MIN_TERMINAL_TAG, MIN_TIME_SLOT, TERMINAL_SUFFIX)));
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
                new String[]{scoreKey(taskId), TaskRedisKeys.descriptor(keyspace, taskId)},
                arguments.toArray(String[]::new)
        );
        if (replies == null || (!replies.isEmpty() && replies.size() != orderedMessageIds.size())) {
            throw new IllegalStateException("TaskItem outcome batch result is invalid");
        }

        LinkedHashMap<String, TaskItemScoreTransitionResult> results =
                new LinkedHashMap<>();
        if (replies.isEmpty()) {
            orderedMessageIds.forEach(id -> results.put(id, transition(TaskItemScoreTransitionStatus.NOT_FOUND)));
        } else {
            for (int index = 0; index < orderedMessageIds.size(); index++) {
                results.put(orderedMessageIds.get(index), scriptResult(replies.get(index)));
            }
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
        long nowMillis = currentTimeMillis.getAsLong();
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
        long nowMillis = currentTimeMillis.getAsLong();
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
            List<String> arguments = new ArrayList<>(pending.size() * 3);
            pending.forEach((messageId, update) -> {
                arguments.add(messageId);
                arguments.add(Long.toString(update.observed()));
                arguments.add(Long.toString(update.next()));
            });
            List<?> replies = commands().eval(
                    CAS_UPDATE_BATCH_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{scoreKey(taskId), TaskRedisKeys.descriptor(keyspace, taskId)},
                    arguments.toArray(String[]::new)
            );
            if (replies == null || (!replies.isEmpty() && replies.size() != pending.size())) {
                throw new IllegalStateException("TaskItem score CAS batch result is invalid");
            }
            if (replies.isEmpty()) {
                pending.keySet().forEach(id -> persisted.put(id, transition(TaskItemScoreTransitionStatus.NOT_FOUND)));
            } else {
                int index = 0;
                for (Map.Entry<String, ScoreUpdate> entry : pending.entrySet()) {
                    String status = String.valueOf(replies.get(index++));
                    persisted.put(entry.getKey(), switch (status) {
                        case "transitioned" -> new TaskItemScoreTransitionResult(
                                TaskItemScoreTransitionStatus.TRANSITIONED, entry.getValue().next());
                        case "stale" -> transition(TaskItemScoreTransitionStatus.STALE);
                        case "not_found" -> transition(TaskItemScoreTransitionStatus.NOT_FOUND);
                        default -> throw new IllegalStateException("TaskItem score script status is invalid");
                    });
                }
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

    @Override
    public TerminalItemPage scanTerminalItems(String taskId, int tag, long atOrBeforeMillis, String cursor, int limit) {
        if (isBlank(taskId) || tag < MIN_TERMINAL_TAG || tag > MAX_TERMINAL_TAG
                || !validTimeMillis(atOrBeforeMillis) || cursor == null || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid terminal Item page");
        long minimum = score(tag, MIN_TIME_SLOT, TERMINAL_SUFFIX);
        // Include corrupt suffixes in the final slot; only valid terminal evidence is returned.
        long maximum = score(tag, atOrBeforeMillis / SLOT_MILLIS, (int) (SUFFIX_FACTOR - 1));
        double afterScore = minimum;
        String member = "";
        if (!cursor.equals("0")) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
                int split = decoded.indexOf('\n');
                afterScore = Double.parseDouble(decoded.substring(0, split));
                member = decoded.substring(split + 1);
                if (!Double.isFinite(afterScore) || afterScore < minimum || afterScore > maximum)
                    throw new IllegalArgumentException("Cursor outside terminal Item window");
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid terminal Item cursor", invalid); }
        }
        List<?> rows = commands().eval(ScoreRedisScripts.KEYSET_PAGE_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey(taskId)}, Double.toString(afterScore), member, Long.toString(maximum),
                Integer.toString(limit), cursor.equals("0") ? "0" : "1", "0");
        var observed = new LinkedHashMap<String, Long>();
        for (int i = 0; i < rows.size(); i += 2) {
            String id = String.valueOf(rows.get(i));
            try {
                if (isBlank(id)) continue;
                long raw = scoreToLong(rows.get(i + 1));
                var state = decodeScore(raw);
                if (state != null && state.tag() == tag && state.timeSlot() * SLOT_MILLIS <= atOrBeforeMillis)
                    observed.put(id, raw);
            } catch (IllegalStateException corrupt) { /* Corrupt rows consume budget without blocking progress. */ }
        }
        String next = rows.size() == limit * 2
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (rows.getLast() + "\n" + rows.get(rows.size() - 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : "0";
        return new TerminalItemPage(observed, next, rows.size() / 2);
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
