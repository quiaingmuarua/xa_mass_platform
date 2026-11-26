package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public final class RedisTaskScoreBandCore
        implements TaskScoreBandCore, AutoCloseable {

    /** Tuples (id, expected, target): exact match writes the Java target. */
    private static final String EXACT_CAS_BATCH_SCRIPT = """
            local results = {}
            for i = 1, #ARGV, 3 do
              local id, status, echo = ARGV[i], 'stale', ''
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if stored then
                local current = tonumber(stored)
                echo = stored
                if current == tonumber(ARGV[i + 1]) then
                  redis.call('ZADD', KEYS[1], ARGV[i + 2], id)
                  status, echo = 'transitioned', ARGV[i + 2]
                end
              end
              results[#results + 1] = id
              results[#results + 1] = status
              results[#results + 1] = echo
            end
            return results
            """;

    /**
     * ARGV: id, defaultStatus, then 5-tuples (min|'', max|'', action, value, mod).
     * First inclusive integer interval containing the stored score decides:
     * w writes value, m writes value + (current % mod), n is noop, t is stale, i is invalid.
     */
    private static final String INTERVAL_REWRITE_SCRIPT = """
            local stored = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not stored then
              return {'stale'}
            end
            local current = tonumber(stored)
            if not current or current ~= math.floor(current) then
              return {'invalid'}
            end
            for i = 3, #ARGV, 5 do
              local lo, hi = ARGV[i], ARGV[i + 1]
              if (lo == '' or current >= tonumber(lo)) and (hi == '' or current <= tonumber(hi)) then
                local action = ARGV[i + 2]
                if action == 'w' then
                  redis.call('ZADD', KEYS[1], ARGV[i + 3], ARGV[1])
                  return {'transitioned', ARGV[i + 3]}
                elseif action == 'm' then
                  local next_score = tonumber(ARGV[i + 3]) + (current % tonumber(ARGV[i + 4]))
                  redis.call('ZADD', KEYS[1], next_score, ARGV[1])
                  return {'transitioned', string.format('%.0f', next_score)}
                elseif action == 'n' then
                  return {'noop', stored}
                elseif action == 't' then
                  return {'stale', stored}
                else
                  return {'invalid', stored}
                end
              end
            end
            return {ARGV[2], stored}
            """;

    private static final String WRITE = "w";
    private static final String WRITE_MOD = "m";
    private static final String NOOP = "n";
    private static final String INVALID = "i";

    private static final long IDLE_PARK_TIME_SLOT = MAX_TIME_SLOT - 1;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private final LongSupplier currentTimeMillis;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisTaskScoreBandCore(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this(redisClient, keyspace, System::currentTimeMillis);
    }

    public RedisTaskScoreBandCore(
            RedisClient redisClient,
            RedisKeyspace keyspace,
            LongSupplier currentTimeMillis
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
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
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
    public int countRunningTasks() {
        long count = commands().zcount(
                scoreKey(),
                score(RUNNING_VISIBLE_TAG, MIN_TIME_SLOT, MIN_SUFFIX),
                score(RUNNING_VISIBLE_TAG, MAX_TIME_SLOT, MAX_SUFFIX)
        );
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    @Override
    public Map<String, Long> acquireSchedulingTasks(int limit) {
        if (limit == 0) {
            return Map.of();
        }
        if (limit < 0 || limit > MAX_TASK_SCHEDULING_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and "
                            + MAX_TASK_SCHEDULING_BATCH_SIZE
            );
        }
        long dueTimeSlot = currentTimeMillis.getAsLong() / SLOT_MILLIS - 1;
        if (dueTimeSlot < MIN_TIME_SLOT) {
            return Map.of();
        }
        long maximumScore = score(
                RUNNING_VISIBLE_TAG,
                dueTimeSlot,
                MAX_SUFFIX
        );
        List<ScoredValue<String>> rows = commands()
                .zrevrangebyscoreWithScores(
                        scoreKey(),
                        maximumScore,
                        0,
                        0,
                        limit
        );
        LinkedHashMap<String, Long> taskScores = new LinkedHashMap<>();
        for (ScoredValue<String> row : rows) {
            try {
                taskScores.put(
                        row.getValue(),
                        scoreToLong(row.getScore())
                );
            } catch (IllegalStateException invalidScore) {
                // Malformed score evidence is skipped without refilling.
            }
        }
        return Collections.unmodifiableMap(taskScores);
    }

    @Override
    public Map<String, Long> observeRunningTasksAscending(int limit) {
        return observeAscending(
                score(RUNNING_VISIBLE_TAG, INITIAL_TIME_SLOT, MIN_SUFFIX),
                idleParkScore(),
                limit
        );
    }

    @Override
    public Map<String, Long> observeNormalRunningTasksAscending(int limit) {
        return observeAscending(
                score(RUNNING_VISIBLE_TAG, NORMAL_TIME_SLOT_MIN, MIN_SUFFIX),
                idleParkScore() - 1,
                limit
        );
    }

    private Map<String, Long> observeAscending(
            long minimumScore,
            long maximumScore,
            int limit
    ) {
        if (limit < 1 || limit > MAX_TASK_SCHEDULING_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and "
                            + MAX_TASK_SCHEDULING_BATCH_SIZE
            );
        }
        List<ScoredValue<String>> rows = commands()
                .zrangebyscoreWithScores(
                        scoreKey(),
                        minimumScore,
                        maximumScore,
                        0,
                        limit
                );
        LinkedHashMap<String, Long> taskScores = new LinkedHashMap<>();
        for (ScoredValue<String> row : rows) {
            try {
                taskScores.put(
                        row.getValue(),
                        scoreToLong(row.getScore())
                );
            } catch (IllegalStateException invalidScore) {
                // Malformed score evidence is skipped without refilling.
            }
        }
        return Collections.unmodifiableMap(taskScores);
    }

    @Override
    public Map<String, Long> filterInitialTaskScores(
            Map<String, Long> observedTaskScores
    ) {
        if (observedTaskScores == null) {
            throw new IllegalArgumentException(
                    "observedTaskScores must be present"
            );
        }
        if (observedTaskScores.size() > MAX_TASK_SCHEDULING_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "observedTaskScores must contain at most "
                            + MAX_TASK_SCHEDULING_BATCH_SIZE + " tasks"
            );
        }
        LinkedHashMap<String, Long> initialScores = new LinkedHashMap<>();
        observedTaskScores.forEach((taskId, observedScore) -> {
            requireNonBlank(taskId, "taskId");
            if (observedScore == null) {
                throw new IllegalArgumentException(
                        "observedTaskScores must not contain null scores"
                );
            }
            DecodedPositive decoded = decodePositive(observedScore);
            if (decoded != null
                    && decoded.tag() == RUNNING_VISIBLE_TAG
                    && decoded.timeSlot() == INITIAL_TIME_SLOT) {
                initialScores.put(taskId, observedScore);
            }
        });
        return Collections.unmodifiableMap(initialScores);
    }

    @Override
    public Map<String, TaskScoreTransitionResult> promoteObservedInitialTasks(
            Map<String, Long> observedInitialScores
    ) {
        if (observedInitialScores == null) {
            throw new IllegalArgumentException("observedInitialScores must be present");
        }
        if (observedInitialScores.isEmpty()) {
            return Map.of();
        }
        if (observedInitialScores.size() > MAX_TASK_SCHEDULING_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "observedInitialScores must contain at most " + MAX_TASK_SCHEDULING_BATCH_SIZE + " tasks");
        }
        long nextTimeSlot = Math.max(currentTimeMillis.getAsLong() / SLOT_MILLIS, NORMAL_TIME_SLOT_MIN);
        long nextScore = score(RUNNING_VISIBLE_TAG, nextTimeSlot, MIN_SUFFIX);
        if (nextScore >= idleParkScore()) {
            return uniformResults(observedInitialScores.keySet(), TaskScoreTransitionStatus.INVALID);
        }
        long initialMin = score(RUNNING_VISIBLE_TAG, INITIAL_TIME_SLOT, MIN_SUFFIX);
        long initialMax = score(RUNNING_VISIBLE_TAG, INITIAL_TIME_SLOT, MAX_SUFFIX);
        LinkedHashMap<String, TaskScoreTransitionResult> immediate = new LinkedHashMap<>();
        LinkedHashMap<String, long[]> pending = new LinkedHashMap<>();
        observedInitialScores.forEach((taskId, observedScore) -> {
            requireNonBlank(taskId, "taskId");
            if (observedScore == null) {
                throw new IllegalArgumentException("observedInitialScores must not contain null scores");
            }
            if (observedScore < initialMin || observedScore > initialMax) {
                immediate.put(taskId, transition(TaskScoreTransitionStatus.INVALID));
            } else {
                pending.put(taskId, new long[]{observedScore, nextScore});
            }
        });
        Map<String, TaskScoreTransitionResult> persisted = pending.isEmpty() ? Map.of() : exactCasBatch(pending);
        LinkedHashMap<String, TaskScoreTransitionResult> results = new LinkedHashMap<>();
        observedInitialScores.keySet().forEach(taskId ->
                results.put(taskId, immediate.containsKey(taskId) ? immediate.get(taskId) : persisted.get(taskId)));
        return Collections.unmodifiableMap(results);
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

        long nowMillis = currentTimeMillis.getAsLong();
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
    public TaskScoreTransitionResult startObservedPreReviewTask(
            String taskId,
            long observedPreReviewScore,
            int priority
    ) {
        requireNonBlank(taskId, "taskId");
        DecodedPositive observed = decodePositive(observedPreReviewScore);
        if (observed == null || observed.tag() != PRE_REVIEW_TAG || !validSuffix(priority)) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long initialScore = score(RUNNING_VISIBLE_TAG, INITIAL_TIME_SLOT, MAX_SUFFIX - priority);
        return exactCasSingle(taskId, observedPreReviewScore, initialScore);
    }

    @Override
    public TaskScoreTransitionResult rewriteSameBandTimeMillis(
            String taskId,
            TaskScoreBand expectedBand,
            long targetTimeMillis
    ) {
        requireNonBlank(taskId, "taskId");
        if (expectedBand == null
                || expectedBand == TaskScoreBand.TERMINAL
                || !validPublicTargetTimeMillis(targetTimeMillis)) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;
        if (targetTimeSlot <= MIN_TIME_SLOT) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long minimumExpectedTimeSlot = expectedBand == TaskScoreBand.RUNNING_VISIBLE
                ? NORMAL_TIME_MIN_MILLIS / SLOT_MILLIS
                : MIN_TIME_SLOT;
        if (targetTimeSlot < minimumExpectedTimeSlot) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        int expectedTag = tag(expectedBand);
        return rewriteInterval(taskId, "stale", new IntervalRules().add(
                Long.toString(score(expectedTag, minimumExpectedTimeSlot, MIN_SUFFIX)),
                Long.toString(score(expectedTag, targetTimeSlot - 1, MAX_SUFFIX)),
                WRITE_MOD,
                Long.toString(score(expectedTag, targetTimeSlot, MIN_SUFFIX)),
                Long.toString(SUFFIX_FACTOR)));
    }

    @Override
    public TaskScoreTransitionResult parkObservedIdleTask(String taskId, long observedScore) {
        requireNonBlank(taskId, "taskId");
        DecodedPositive observed = decodePositive(observedScore);
        if (observed == null
                || observed.tag() != RUNNING_VISIBLE_TAG
                || observed.suffix() != MIN_SUFFIX
                || observed.timeSlot() < NORMAL_TIME_MIN_MILLIS / SLOT_MILLIS
                || observed.timeSlot() >= IDLE_PARK_TIME_SLOT) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        return exactCasSingle(taskId, observedScore, idleParkScore());
    }

    @Override
    public TaskScoreTransitionResult tryReleaseIdlePark(String taskId) {
        requireNonBlank(taskId, "taskId");
        long runningMin = score(RUNNING_VISIBLE_TAG, MIN_TIME_SLOT, MIN_SUFFIX);
        long nextScore = runningMin + currentTimeMillis.getAsLong() / SLOT_MILLIS * SUFFIX_FACTOR;
        long park = idleParkScore();
        boolean validNext = nextScore > runningMin && nextScore < park;
        long pauseMax = score(RUNNING_VISIBLE_TAG, PAUSE_TIME_SLOT, MAX_SUFFIX);
        var rules = new IntervalRules()
                .add(Long.toString(park), Long.toString(park),
                        validNext ? WRITE : INVALID, validNext ? Long.toString(nextScore) : "", "")
                .add("1", Long.toString(park - 1), NOOP, "", "")
                .add(Long.toString(pauseMax + 1), "", NOOP, "", "");
        return rewriteInterval(taskId, "invalid", rules);
    }

    @Override
    public TaskScoreTransitionResult closeScore(String taskId) {
        requireNonBlank(taskId, "taskId");
        long endedAt = currentTimeMillis.getAsLong();
        if (endedAt <= 1 || endedAt > MAX_TIME_MILLIS) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        var rules = new IntervalRules()
                .add("", "-1", NOOP, "", "")
                .add("0", "", WRITE, Long.toString(-endedAt), "");
        return rewriteInterval(taskId, "invalid", rules);
    }

    @Override
    public TaskScoreTransitionResult closeObservedScore(String taskId, long observedScore) {
        requireNonBlank(taskId, "taskId");
        long endedAt = currentTimeMillis.getAsLong();
        if (endedAt <= 1 || endedAt > MAX_TIME_MILLIS || decodePositive(observedScore) == null) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        return exactCasSingle(taskId, observedScore, -endedAt);
    }

    @Override
    public TaskScoreTransitionResult releaseObservedScoreHold(String taskId, long observedHoldScore) {
        requireNonBlank(taskId, "taskId");
        if (observedHoldScore == idleParkScore()) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        DecodedPositive observed = decodePositive(observedHoldScore);
        if (observed == null) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long releaseTimeSlot = currentTimeMillis.getAsLong() / SLOT_MILLIS;
        if (releaseTimeSlot > observed.timeSlot()) {
            return transition(TaskScoreTransitionStatus.INVALID);
        }
        long releaseScore = score(observed.tag(), releaseTimeSlot, observed.suffix());
        return exactCasSingle(taskId, observedHoldScore, releaseScore);
    }

    private TaskScoreState decodeState(String taskId, double rawScore) {
        return decodeState(taskId, scoreToLong(rawScore));
    }

    private TaskScoreState decodeState(String taskId, long storedScore) {
        if (storedScore < 0) {
            if (storedScore == -1 || storedScore < -MAX_TIME_MILLIS)
                throw new IllegalStateException("Task termination time is invalid");
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

    @Override
    public TerminalTaskPage scanTerminalTasks(long terminatedBeforeMillis, String cursor, int limit) {
        if (terminatedBeforeMillis < 0 || terminatedBeforeMillis > MAX_TIME_MILLIS
                || cursor == null || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid terminal Task page");
        if (terminatedBeforeMillis <= 1) return new TerminalTaskPage(List.of(), "0");
        double afterScore = -terminatedBeforeMillis;
        String afterMember = "";
        if (!cursor.equals("0")) {
            try {
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
                int split = decoded.indexOf('\n');
                afterScore = Double.parseDouble(decoded.substring(0, split));
                afterMember = decoded.substring(split + 1);
                if (!Double.isFinite(afterScore) || afterScore < -terminatedBeforeMillis || afterScore >= 0 || afterMember.isBlank())
                    throw new IllegalArgumentException("Cursor outside terminal window");
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid terminal cursor", invalid); }
        }
        List<?> rows = commands().eval(ScoreRedisScripts.KEYSET_PAGE_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey()}, Double.toString(afterScore), afterMember, "0",
                Integer.toString(limit), "1", "1");
        var tasks = new ArrayList<TaskScoreState>();
        for (int i = 0; i < rows.size(); i += 2) {
            try { tasks.add(decodeState(String.valueOf(rows.get(i)), scoreToLong(rows.get(i + 1)))); }
            catch (IllegalStateException corrupt) { /* Malformed rows consume budget without stalling later identities. */ }
        }
        String next = "0";
        if (rows.size() == limit * 2) {
            next = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (rows.getLast() + "\n" + rows.get(rows.size() - 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new TerminalTaskPage(tasks, next);
    }

    @Override
    public ParkedTaskPage scanParkedTasks(String cursor, int limit) {
        if (cursor == null || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid parked Task page");
        String member = "";
        if (!cursor.equals("0")) {
            try {
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
                int split = decoded.indexOf('\n');
                if (Double.parseDouble(decoded.substring(0, split)) != idleParkScore())
                    throw new IllegalArgumentException("Cursor outside parked Task window");
                member = decoded.substring(split + 1);
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid parked Task cursor", invalid); }
        }
        List<?> rows = commands().eval(ScoreRedisScripts.KEYSET_PAGE_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey()}, Long.toString(idleParkScore()), member, Long.toString(idleParkScore()),
                Integer.toString(limit), cursor.equals("0") ? "0" : "1", "0");
        var tasks = new ArrayList<TaskScoreState>();
        for (int i = 0; i < rows.size(); i += 2) {
            String taskId = String.valueOf(rows.get(i));
            try {
                if (taskId.isBlank()) continue;
                var state = decodeState(taskId, scoreToLong(rows.get(i + 1)));
                if (state.isIdleParked()) tasks.add(state);
            } catch (IllegalStateException corrupt) { /* Corrupt rows consume budget without blocking progress. */ }
        }
        String next = rows.size() == limit * 2
                ? java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (rows.getLast() + "\n" + rows.get(rows.size() - 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : "0";
        return new ParkedTaskPage(tasks, next, rows.size() / 2);
    }

    @Override
    public NonTerminalTaskPage scanNonTerminalTasks(String cursor, int limit) {
        if (cursor == null || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid non-terminal Task page");
        }
        long maxNonTerminalScore = (PRE_REVIEW_TAG + 1L) * DEFAULT_TAG_FACTOR - 1L;
        double afterScore = MUTABLE_SCORE_MIN;
        String afterMember = "";
        boolean exclusiveMin = false;
        if (!cursor.equals("0")) {
            try {
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
                int split = decoded.indexOf('\n');
                afterScore = Double.parseDouble(decoded.substring(0, split));
                afterMember = decoded.substring(split + 1);
                if (!Double.isFinite(afterScore) || afterScore < MUTABLE_SCORE_MIN
                        || afterScore > maxNonTerminalScore || afterMember.isBlank()) {
                    throw new IllegalArgumentException("Cursor outside non-terminal Task window");
                }
                exclusiveMin = true;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid non-terminal Task cursor", invalid);
            }
        }
        List<?> rows = commands().eval(
                ScoreRedisScripts.KEYSET_PAGE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                Double.toString(afterScore),
                afterMember,
                Long.toString(maxNonTerminalScore),
                Integer.toString(limit),
                exclusiveMin ? "1" : "0",
                "0"
        );
        var tasks = new ArrayList<TaskScoreState>();
        for (int i = 0; i < rows.size(); i += 2) {
            String taskId = String.valueOf(rows.get(i));
            try {
                if (taskId.isBlank()) continue;
                var state = decodeState(taskId, scoreToLong(rows.get(i + 1)));
                if (state.band() != TaskScoreBand.TERMINAL) {
                    tasks.add(state);
                }
            } catch (IllegalStateException corrupt) {
                /* Corrupt rows consume budget without blocking progress. */
            }
        }
        String next = rows.size() == limit * 2
                ? java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (rows.getLast() + "\n" + rows.get(rows.size() - 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : "0";
        return new NonTerminalTaskPage(tasks, next, rows.size() / 2);
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
            case PRE_REVIEW -> PRE_REVIEW_TAG;
            case TERMINAL -> throw new IllegalArgumentException(
                    "terminal band is not positive"
            );
        };
    }

    private static TaskScoreBand band(int tag) {
        return switch (tag) {
            case RUNNING_VISIBLE_TAG -> TaskScoreBand.RUNNING_VISIBLE;
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

    private TaskScoreTransitionResult exactCasSingle(String taskId, long observedScore, long nextScore) {
        var targets = new LinkedHashMap<String, long[]>();
        targets.put(taskId, new long[]{observedScore, nextScore});
        return exactCasBatch(targets).get(taskId);
    }

    private Map<String, TaskScoreTransitionResult> exactCasBatch(Map<String, long[]> targets) {
        List<String> arguments = new ArrayList<>(targets.size() * 3);
        targets.forEach((taskId, pair) -> {
            arguments.add(taskId);
            arguments.add(Long.toString(pair[0]));
            arguments.add(Long.toString(pair[1]));
        });
        Object raw = commands().eval(
                EXACT_CAS_BATCH_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                arguments.toArray(String[]::new)
        );
        if (!(raw instanceof List<?> values) || values.size() != targets.size() * 3) {
            throw new IllegalStateException("Task score exact CAS batch result is invalid");
        }
        LinkedHashMap<String, TaskScoreTransitionResult> results = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index += 3) {
            String taskId = String.valueOf(values.get(index));
            TaskScoreTransitionStatus status = transitionStatus(values.get(index + 1));
            Object rawScore = values.get(index + 2);
            Long score = rawScore == null || String.valueOf(rawScore).isEmpty() ? null : scoreToLong(rawScore);
            if (results.put(taskId, transition(status, score)) != null) {
                throw new IllegalStateException("Task score exact CAS batch contains duplicate ids");
            }
        }
        if (!List.copyOf(results.keySet()).equals(List.copyOf(targets.keySet()))) {
            throw new IllegalStateException("Task score exact CAS batch identities are invalid");
        }
        return results;
    }

    private TaskScoreTransitionResult rewriteInterval(String taskId, String defaultStatus, IntervalRules rules) {
        List<String> arguments = new ArrayList<>(2 + rules.values.size());
        arguments.add(taskId);
        arguments.add(defaultStatus);
        arguments.addAll(rules.values);
        return scriptResult(commands().eval(
                INTERVAL_REWRITE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey()},
                arguments.toArray(String[]::new)
        ));
    }

    private static TaskScoreTransitionResult scriptResult(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException(
                    "Task score script result is invalid"
            );
        }
        TaskScoreTransitionStatus status = transitionStatus(values.get(0));
        Long resultScore = values.size() > 1
                && values.get(1) != null
                && !String.valueOf(values.get(1)).isEmpty()
                ? scoreToLong(values.get(1))
                : null;
        return transition(status, resultScore);
    }

    private static TaskScoreTransitionStatus transitionStatus(Object raw) {
        return switch (String.valueOf(raw)) {
            case "transitioned" -> TaskScoreTransitionStatus.TRANSITIONED;
            case "noop" -> TaskScoreTransitionStatus.NOOP;
            case "stale" -> TaskScoreTransitionStatus.STALE;
            case "invalid" -> TaskScoreTransitionStatus.INVALID;
            default -> throw new IllegalStateException(
                    "Task score script status is invalid"
            );
        };
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

    private static Map<String, TaskScoreTransitionResult> uniformResults(
            Iterable<String> taskIds,
            TaskScoreTransitionStatus status
    ) {
        LinkedHashMap<String, TaskScoreTransitionResult> results =
                new LinkedHashMap<>();
        taskIds.forEach(taskId -> results.put(taskId, transition(status)));
        return Collections.unmodifiableMap(results);
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

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private record DecodedPositive(int tag, long timeSlot, int suffix) {
    }

    private static final class IntervalRules {
        private final List<String> values = new ArrayList<>();

        IntervalRules add(String min, String max, String action, String value, String mod) {
            values.add(min);
            values.add(max);
            values.add(action);
            values.add(value);
            values.add(mod);
            return this;
        }
    }
}
