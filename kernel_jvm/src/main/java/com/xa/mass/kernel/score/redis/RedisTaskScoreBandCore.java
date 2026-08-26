package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RedisTaskScoreBandCore
        implements TaskScoreBandCore, AutoCloseable {

    private static final String CAS_UPDATE_SCRIPT = """
            local key = KEYS[1]
            local task_id = ARGV[1]
            local observed_score = tonumber(ARGV[2])
            local next_score = tonumber(ARGV[3])

            local stored = redis.call("ZSCORE", key, task_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            if stored_score ~= observed_score then
              return {"stale", stored_score}
            end

            redis.call("ZADD", key, next_score, task_id)
            return {"transitioned", next_score}
            """;

    private static final String CLOSE_POSITIVE_SCRIPT = """
            local key = KEYS[1]
            local task_id = ARGV[1]
            local terminal_score = tonumber(ARGV[2])

            local stored = redis.call("ZSCORE", key, task_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            if stored_score < 0 then
              return {"noop", stored_score}
            end

            redis.call("ZADD", key, terminal_score, task_id)
            return {"transitioned", terminal_score}
            """;

    private static final String MINT_FROM_RANGE_SCRIPT = """
            local key = KEYS[1]
            local task_id = ARGV[1]
            local min_expected_score = tonumber(ARGV[2])
            local max_expected_score = tonumber(ARGV[3])
            local target_score_base = tonumber(ARGV[4])
            local target_suffix = tonumber(ARGV[5])
            local suffix_factor = tonumber(ARGV[6])

            local stored = redis.call("ZSCORE", key, task_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            if stored_score < min_expected_score
                or stored_score > max_expected_score then
              return {"stale", stored_score}
            end

            local stored_suffix = stored_score % suffix_factor
            if target_suffix < 0 then
              target_suffix = stored_suffix
            end

            local next_score = target_score_base + target_suffix
            redis.call("ZADD", key, next_score, task_id)
            return {"transitioned", next_score}
            """;

    private static final String TRY_RELEASE_IDLE_PARK_SCRIPT = """
            local key = KEYS[1]
            local task_id = ARGV[1]
            local idle_park_score = tonumber(ARGV[2])
            local running_pause_max_score = tonumber(ARGV[3])
            local slot_millis = tonumber(ARGV[4])
            local suffix_factor = tonumber(ARGV[5])
            local running_min = tonumber(ARGV[6])

            local stored = redis.call("ZSCORE", key, task_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            if stored_score == idle_park_score then
              local redis_time = redis.call("TIME")
              local now_millis = tonumber(redis_time[1]) * 1000
                  + math.floor(tonumber(redis_time[2]) / 1000)
              local now_time_slot = math.floor(now_millis / slot_millis)
              local next_score = running_min
                  + now_time_slot * suffix_factor
              if next_score <= running_min
                  or next_score >= idle_park_score then
                return {"invalid", stored_score}
              end

              redis.call("ZADD", key, next_score, task_id)
              return {"transitioned", next_score}
            end

            if (stored_score > 0 and stored_score < idle_park_score)
                or stored_score > running_pause_max_score then
              return {"noop", stored_score}
            end
            return {"invalid", stored_score}
            """;

    private static final long IDLE_PARK_TIME_SLOT = MAX_TIME_SLOT - 1;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskScoreBandCore(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException(
                    "redisClient must be present"
            );
        }
        this.redisClient = redisClient;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public Map<String, TaskScoreState> getScoreStates(
            List<String> taskIds
    ) {
        if (taskIds == null) {
            throw new IllegalArgumentException("taskIds must be present");
        }
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        taskIds.forEach(taskId -> requireNonBlank(taskId, "taskId"));
        List<Double> loaded = commands().zmscore(
                scoreKey(),
                taskIds.toArray(String[]::new)
        );
        Map<String, TaskScoreState> states = new LinkedHashMap<>();
        for (int index = 0; index < taskIds.size(); index++) {
            Double score = loaded.get(index);
            states.put(
                    taskIds.get(index),
                    score == null
                            ? null
                            : decodeState(taskIds.get(index), score)
            );
        }
        return states;
    }

    @Override
    public List<TaskScoreState> previewScoreStates(int limit) {
        if (limit < 1 || limit > MAX_TASK_SCORE_PREVIEW_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and "
                            + MAX_TASK_SCORE_PREVIEW_LIMIT
            );
        }
        List<ScoredValue<String>> rows = commands().zrevrangeWithScores(
                scoreKey(),
                0,
                limit - 1L
        );
        return rows.stream()
                .map(row -> decodeState(row.getValue(), row.getScore()))
                .toList();
    }

    @Override
    public int countRunningCapacityTasks() {
        throw notImplemented("count_running_capacity_tasks");
    }

    @Override
    public List<String> acquireBandTaskCandidates(
            TaskScoreBand band,
            long beforeTimeMillis,
            int limit
    ) {
        throw notImplemented("acquire_band_task_candidates");
    }

    @Override
    public List<String> acquireDispatchWorkTasks(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long currentTimeSlot = redisTimeMillis() / SLOT_MILLIS;
        long maximumTimeSlot = currentTimeSlot - 1;
        if (maximumTimeSlot < MIN_TIME_SLOT) {
            return List.of();
        }
        long minimumScore = score(
                RUNNING_VISIBLE_TAG,
                MIN_TIME_SLOT,
                MIN_SUFFIX
        );
        long maximumScore = score(
                RUNNING_VISIBLE_TAG,
                maximumTimeSlot,
                MAX_SUFFIX
        );
        return List.copyOf(commands().zrangebyscore(
                scoreKey(),
                minimumScore,
                maximumScore,
                0,
                limit
        ));
    }

    @Override
    public TaskScoreTransitionResult initializeScore(
            String taskId,
            int suffix,
            long leaseDurationMillis
    ) {
        requireNonBlank(taskId, "taskId");
        if (!validSuffix(suffix) || leaseDurationMillis <= 0) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }

        long nowMillis = redisTimeMillis();
        long currentTimeSlot = nowMillis / SLOT_MILLIS;
        if (currentTimeSlot <= MIN_TIME_SLOT) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long leaseUntilMillis;
        try {
            leaseUntilMillis = Math.addExact(
                    nowMillis,
                    leaseDurationMillis
            );
        } catch (ArithmeticException error) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long leaseUntilSlot = leaseUntilMillis / SLOT_MILLIS;
        if (leaseUntilSlot > MAX_TIME_SLOT
                || leaseUntilSlot == IDLE_PARK_TIME_SLOT) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }

        long leaseScore = score(
                PRE_REVIEW_TAG,
                leaseUntilSlot,
                suffix
        );
        Long added = commands().zadd(
                scoreKey(),
                ZAddArgs.Builder.nx(),
                leaseScore,
                taskId
        );
        if (added != null && added == 1L) {
            return transition(
                    TaskScoreTransitionStatus.TRANSITIONED,
                    leaseScore
            );
        }
        Double stored = commands().zscore(scoreKey(), taskId);
        return stored == null
                ? transition(TaskScoreTransitionStatus.STALE)
                : transition(
                        TaskScoreTransitionStatus.NOOP,
                        scoreToLong(stored)
                );
    }

    @Override
    public TaskScoreTransitionResult rewriteScore(
            String taskId,
            TaskScoreBand expectedBand,
            long targetTimeMillis,
            TaskScoreBand targetBand,
            Integer targetSuffix
    ) {
        requireNonBlank(taskId, "taskId");
        if (expectedBand == null
                || expectedBand == TaskScoreBand.TERMINAL
                || targetBand == TaskScoreBand.TERMINAL
                || !validPublicTargetTimeMillis(targetTimeMillis)
                || targetSuffix != null && !validSuffix(targetSuffix)) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }

        int expectedTag = tag(expectedBand);
        int targetTag = tag(targetBand == null ? expectedBand : targetBand);
        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;
        if (targetTag > expectedTag || targetTimeSlot <= MIN_TIME_SLOT) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        return scriptResult(commands().eval(
                MINT_FROM_RANGE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                taskId,
                Long.toString(score(
                        expectedTag,
                        MIN_TIME_SLOT,
                        MIN_SUFFIX
                )),
                Long.toString(score(
                        expectedTag,
                        targetTimeSlot - 1,
                        MAX_SUFFIX
                )),
                Long.toString(score(
                        targetTag,
                        targetTimeSlot,
                        MIN_SUFFIX
                )),
                Integer.toString(
                        targetSuffix == null ? -1 : targetSuffix
                ),
                Long.toString(SUFFIX_FACTOR)
        ));
    }

    @Override
    public TaskScoreTransitionResult rewriteSameBandTimeMillis(
            String taskId,
            TaskScoreBand expectedBand,
            long targetTimeMillis
    ) {
        throw notImplemented("rewrite_same_band_time_millis");
    }

    @Override
    public TaskScoreTransitionResult parkObservedIdleTask(
            String taskId,
            long observedScore
    ) {
        throw notImplemented("park_observed_idle_task");
    }

    @Override
    public TaskScoreTransitionResult tryReleaseIdlePark(String taskId) {
        requireNonBlank(taskId, "taskId");
        return scriptResult(commands().eval(
                TRY_RELEASE_IDLE_PARK_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                taskId,
                Long.toString(idleParkScore()),
                Long.toString(score(
                        RUNNING_VISIBLE_TAG,
                        PAUSE_TIME_SLOT,
                        MAX_SUFFIX
                )),
                Long.toString(SLOT_MILLIS),
                Long.toString(SUFFIX_FACTOR),
                Long.toString(score(
                        RUNNING_VISIBLE_TAG,
                        MIN_TIME_SLOT,
                        MIN_SUFFIX
                ))
        ));
    }

    @Override
    public TaskScoreTransitionResult closeScore(
            String taskId,
            long terminalScore
    ) {
        requireNonBlank(taskId, "taskId");
        if (terminalScore > TERMINAL_SCORE_MAX) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        return scriptResult(commands().eval(
                CLOSE_POSITIVE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                taskId,
                Long.toString(terminalScore)
        ));
    }

    @Override
    public TaskScoreTransitionResult closeObservedScore(
            String taskId,
            long observedScore,
            long terminalScore
    ) {
        throw notImplemented("close_observed_score");
    }

    @Override
    public TaskScoreTransitionResult releaseObservedScoreHold(
            String taskId,
            long observedHoldScore
    ) {
        requireNonBlank(taskId, "taskId");
        if (observedHoldScore == idleParkScore()) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        DecodedPositive observed = decodePositive(observedHoldScore);
        if (observed == null) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }

        long releaseTimeSlot = redisTimeMillis() / SLOT_MILLIS;
        if (releaseTimeSlot > observed.timeSlot()) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long releaseScore = score(
                observed.tag(),
                releaseTimeSlot,
                observed.suffix()
        );
        return scriptResult(commands().eval(
                CAS_UPDATE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                taskId,
                Long.toString(observedHoldScore),
                Long.toString(releaseScore)
        ));
    }

    private TaskScoreState decodeState(String taskId, double rawScore) {
        long storedScore = scoreToLong(rawScore);
        if (storedScore < 0) {
            return new TaskScoreState(
                    taskId,
                    storedScore,
                    TaskScoreBand.TERMINAL,
                    null,
                    null
            );
        }
        DecodedPositive decoded = decodePositive(storedScore);
        if (decoded == null) {
            throw new IllegalStateException("Task score is invalid");
        }
        return new TaskScoreState(
                taskId,
                storedScore,
                band(decoded.tag()),
                decoded.timeSlot() * SLOT_MILLIS,
                decoded.suffix()
        );
    }

    private static DecodedPositive decodePositive(long storedScore) {
        if (storedScore <= 0) {
            return null;
        }
        long tag = storedScore / DEFAULT_TAG_FACTOR;
        long remainder = storedScore % DEFAULT_TAG_FACTOR;
        long timeSlot = remainder / SUFFIX_FACTOR;
        long suffix = remainder % SUFFIX_FACTOR;
        if (!VALID_POSITIVE_TAGS.contains(Math.toIntExact(tag))
                || timeSlot < MIN_TIME_SLOT
                || timeSlot > MAX_TIME_SLOT
                || suffix < MIN_SUFFIX
                || suffix > MAX_SUFFIX) {
            return null;
        }
        return new DecodedPositive(
                Math.toIntExact(tag),
                timeSlot,
                Math.toIntExact(suffix)
        );
    }

    private static int tag(TaskScoreBand band) {
        return switch (band) {
            case RUNNING_VISIBLE -> RUNNING_VISIBLE_TAG;
            case ADMISSION_VISIBLE -> ADMISSION_VISIBLE_TAG;
            case PRE_REVIEW -> PRE_REVIEW_TAG;
            case TERMINAL -> throw new IllegalArgumentException(
                    "terminal band is not positive"
            );
        };
    }

    private static TaskScoreBand band(int tag) {
        return switch (tag) {
            case RUNNING_VISIBLE_TAG -> TaskScoreBand.RUNNING_VISIBLE;
            case ADMISSION_VISIBLE_TAG -> TaskScoreBand.ADMISSION_VISIBLE;
            case PRE_REVIEW_TAG -> TaskScoreBand.PRE_REVIEW;
            default -> throw new IllegalStateException(
                    "Task score tag is invalid"
            );
        };
    }

    private static long score(int tag, long timeSlot, int suffix) {
        return (long) tag * DEFAULT_TAG_FACTOR
                + timeSlot * SUFFIX_FACTOR
                + suffix;
    }

    private static long idleParkScore() {
        return score(
                RUNNING_VISIBLE_TAG,
                IDLE_PARK_TIME_SLOT,
                MAX_SUFFIX
        );
    }

    private static boolean validSuffix(int suffix) {
        return suffix >= MIN_SUFFIX && suffix <= MAX_SUFFIX;
    }

    private static boolean validPublicTargetTimeMillis(long timeMillis) {
        return timeMillis >= MIN_TIME_MILLIS
                && timeMillis <= MAX_TIME_MILLIS
                && timeMillis / SLOT_MILLIS != IDLE_PARK_TIME_SLOT;
    }

    private static TaskScoreTransitionResult scriptResult(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException(
                    "Task score script result is invalid"
            );
        }
        TaskScoreTransitionStatus status = switch (
                String.valueOf(values.get(0))
        ) {
            case "transitioned" -> TaskScoreTransitionStatus.TRANSITIONED;
            case "noop" -> TaskScoreTransitionStatus.NOOP;
            case "stale" -> TaskScoreTransitionStatus.STALE;
            case "invalid" -> TaskScoreTransitionStatus.INVALID;
            default -> throw new IllegalStateException(
                    "Task score script status is invalid"
            );
        };
        Long resultScore = values.size() > 1 && values.get(1) != null
                ? scoreToLong(values.get(1))
                : null;
        return transition(status, resultScore);
    }

    private static TaskScoreTransitionResult transition(
            TaskScoreTransitionStatus status
    ) {
        return transition(status, null);
    }

    private static TaskScoreTransitionResult transition(
            TaskScoreTransitionStatus status,
            Long score
    ) {
        return new TaskScoreTransitionResult(status, score);
    }

    private static long scoreToLong(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            long converted = number.longValue();
            if (!Double.isFinite(value) || value != (double) converted) {
                throw new IllegalStateException(
                        "Task score must be an integer"
                );
            }
            return converted;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "Task score must be an integer",
                    error
            );
        }
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

    private String scoreKey() {
        return keyspace.base() + ":task:score";
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }

    private static KernelOperationNotImplementedException notImplemented(
            String operation
    ) {
        return new KernelOperationNotImplementedException(
                "TaskScoreBandCore",
                operation
        );
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private record DecodedPositive(int tag, long timeSlot, int suffix) {
    }
}
