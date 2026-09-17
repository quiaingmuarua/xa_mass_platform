package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
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
import java.util.function.LongSupplier;

public final class RedisWorkerScoreCore
        implements WorkerScoreCore, AutoCloseable {

    private static final String REDIS_TIME_FUNCTION = """
            local function redis_now_millis()
              local clock = redis.call('TIME')
              return tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            end
            """;
    private static final String EXACT_FUNCTIONS = """
            local function read_exact(key, id, expected, counterpart)
              local stored = redis.call('ZSCORE', key, id)
              if not stored then return nil, {'stale'} end
              local score = tonumber(stored)
              if score ~= expected and score ~= counterpart then
                return nil, {'stale', score}, stored
              end
              return score, nil, stored
            end
            local function write_changed(key, id, current, target)
              if target == current then return {'noop', current} end
              redis.call('ZADD', key, target, id)
              return {'transitioned', target}
            end
            local function append_result(results, id, row)
              results[#results + 1] = id
              results[#results + 1] = row[1]
              results[#results + 1] = row[2] or ''
            end
            """;
    private static final String INITIALIZE_REGISTERED_SCRIPT = """
            local created = {}
            for i = 2, #ARGV do
              if redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[i]) == 1 then
                created[#created + 1] = ARGV[i]
              end
            end
            return created
            """;
    private static final String ADVANCE_AND_SEAL_SCRIPT = EXACT_FUNCTIONS + """
            local target_base, factor = tonumber(ARGV[2]), tonumber(ARGV[3])
            local stored = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not stored then return {'stale'} end
            local current = tonumber(stored)
            local absolute = math.abs(current)
            if absolute <= 0 then return {'invalid', current} end
            if absolute > target_base then return {'stale', current} end
            local mark = absolute % factor
            -- Legal marks become 1; retain the existing corrupt fractional suffix behavior.
            local target = target_base + (mark == 0 and 1 or mark)
            return write_changed(KEYS[1], ARGV[1], current, current / absolute * target)
            """;
    private static final String SEAL_CURRENT_SCRIPT = EXACT_FUNCTIONS + """
            local maximum, factor = tonumber(ARGV[1]), tonumber(ARGV[2])
            local function mark(id)
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if not stored then return {'stale'} end
              local current = tonumber(stored)
              local absolute = math.abs(current)
              if absolute == 0 or absolute > maximum or absolute ~= math.floor(absolute) then
                return {'invalid'}
              end
              local target = absolute - absolute % factor + 1
              return write_changed(KEYS[1], id, current, current > 0 and target or -target)
            end
            local results = {}
            for i = 3, #ARGV do append_result(results, ARGV[i], mark(ARGV[i])) end
            return results
            """;

    private static final String CAS_UPDATE_SCRIPT = EXACT_FUNCTIONS + """
            local current, rejected = read_exact(KEYS[1], ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[4]))
            if rejected then return rejected end
            return write_changed(KEYS[1], ARGV[1], current, tonumber(ARGV[3]))
            """;
    private static final String DUE_HOT_HEAD_SCRIPT = REDIS_TIME_FUNCTION + """
            local minimum,limit=tonumber(ARGV[1]),tonumber(ARGV[2])
            local millis,factor=tonumber(ARGV[3]),tonumber(ARGV[4])
            local now=math.floor(redis_now_millis()/millis)
            local maximum=now*factor-1
            if maximum<minimum then return {} end
            return redis.call('ZRANGE',KEYS[1],minimum,maximum,'BYSCORE','LIMIT',0,limit,'WITHSCORES')
            """;
    private static final String LEASE_CLOCK = EXACT_FUNCTIONS + REDIS_TIME_FUNCTION + """
            local requested_base = tonumber(ARGV[1])
            local millis, factor = tonumber(ARGV[2]), tonumber(ARGV[3])
            local now_base = math.floor(redis_now_millis() / millis) * factor
            """;
    private static final String ACQUIRE_DUE_SCRIPT = LEASE_CLOCK + """
            local function acquire(id, observed)
              if requested_base <= now_base then return {'invalid'} end
              local current, rejected = read_exact(KEYS[1], id, observed)
              if rejected then return rejected end
              if current >= now_base then return {'stale'} end
              return write_changed(KEYS[1], id, current, requested_base)
            end
            local results = {}
            for i = 4, #ARGV, 2 do
              append_result(results, ARGV[i], acquire(ARGV[i], tonumber(ARGV[i+1])))
            end
            return results
            """;
    private static final String ACTIVE_TRANSFER_FUNCTION = LEASE_CLOCK + """
            local seal = tonumber(ARGV[4])
            local function transfer_active(id, current)
              if current % factor ~= 0 or current < now_base then
                return {'stale', current}
              end
              local target = math.max(requested_base, current) + seal
              return write_changed(KEYS[1], id, current, target)
            end
            """;
    private static final String TRANSFER_OBSERVED_SCRIPT = ACTIVE_TRANSFER_FUNCTION + """
            local function transfer(id, expected)
              if requested_base <= now_base then return {'invalid'} end
              local current, rejected = read_exact(KEYS[1], id, expected)
              if rejected then return rejected end
              return transfer_active(id, current)
            end
            local results = {}
            for i = 5, #ARGV, 2 do
              append_result(results, ARGV[i], transfer(ARGV[i], tonumber(ARGV[i+1])))
            end
            return results
            """;
    private static final String TRANSFER_CURRENT_SCRIPT = ACTIVE_TRANSFER_FUNCTION + """
            local maximum = tonumber(ARGV[5])
            local function transfer(id)
              if requested_base <= now_base then return {'invalid'} end
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if not stored then return {'stale'} end
              local current = tonumber(stored)
              if not current then return {'invalid'} end
              local absolute = math.abs(current)
              if absolute == 0 or absolute > maximum or absolute ~= math.floor(absolute) then
                return {'invalid'}
              end
              return transfer_active(id, current)
            end
            local results = {}
            for i = 6, #ARGV do append_result(results, ARGV[i], transfer(ARGV[i])) end
            return results
            """;
    private static final String DEFER_DUE_SCRIPT = EXACT_FUNCTIONS + REDIS_TIME_FUNCTION + """
            local millis, factor, maximum = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
            local delay = tonumber(ARGV[4])
            local now_millis = redis_now_millis()
            local now = math.floor(now_millis / millis)
            local now_base = now * factor
            local target_slot = math.floor((now_millis + delay) / millis)
            local target_base = target_slot * factor
            local function defer(id, observed, mark)
              local current, rejected, stored = read_exact(KEYS[1], id, observed)
              if rejected then rejected[2] = stored; return rejected end
              local target_absolute = target_base + mark
              if now < 0 or now > maximum or target_slot > maximum or target_absolute <= 0 then
                return {'invalid', stored}
              end
              if math.abs(current) >= now_base then return {'stale', stored} end
              return write_changed(KEYS[1], id, current, -target_absolute)
            end
            local results = {}
            for i = 5, #ARGV, 3 do
              append_result(results, ARGV[i], defer(ARGV[i], tonumber(ARGV[i+1]),
                  tonumber(ARGV[i+2])))
            end
            return results
            """;

    private static final String CURRENT_POLARITY_SCRIPT = EXACT_FUNCTIONS + REDIS_TIME_FUNCTION + """
            local sign, refresh_past = tonumber(ARGV[1]), ARGV[2] == 'true'
            local millis, factor, maximum = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])
            local now = math.floor(redis_now_millis() / millis)
            local function rewrite(id, supplied_slot)
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if not stored then return {'stale'} end
              local current = tonumber(stored)
              local absolute = math.abs(current)
              local slot = math.floor(absolute / factor)
              if now < 0 or now > maximum or absolute <= 0 or slot > maximum then
                return {'invalid', stored}
              end
              if slot < now and slot > supplied_slot then return {'stale', stored} end
              if refresh_past and slot < now and slot < supplied_slot then
                absolute = supplied_slot * factor + absolute % factor
              end
              local result = write_changed(KEYS[1], id, current, sign * absolute)
              if result[1] == 'noop' then result[2] = stored end
              return result
            end
            local results = {}
            for i = 6, #ARGV, 2 do
              append_result(results, ARGV[i], rewrite(ARGV[i], tonumber(ARGV[i+1])))
            end
            return results
            """;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private final LongSupplier currentTimeMillis;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerScoreCore(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this(redisClient, keyspace, System::currentTimeMillis);
    }

    RedisWorkerScoreCore(
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
        this.currentTimeMillis = java.util.Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public WorkerSchedulingObservation observeSchedulingStates(
            String homeBucketId,
            List<String> workerIds
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null || workerIds.isEmpty() || workerIds.size() > MAX_SCORE_BATCH_SIZE
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique values");
        }
        workerIds.forEach(workerId ->
                requireNonBlank(workerId, "workerId"));
        Map<String, WorkerScoreState> states = readScoreStates(homeBucketId, workerIds);
        long readAtMillis = currentTimeMillis.getAsLong();
        var projected = new LinkedHashMap<String, SchedulingState>();
        states.forEach((id, state) -> projected.put(id, schedulingState(state, readAtMillis)));
        return new WorkerSchedulingObservation(readAtMillis, projected);
    }

    @Override
    public WorkerSchedulingChangeStatus pauseScheduling(String homeBucketId, String workerId) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        var result = advanceCurrentScoresAndSeal(homeBucketId, List.of(workerId), PAUSE_TIME_MILLIS).get(workerId);
        return switch (result.status()) {
            case TRANSITIONED -> WorkerSchedulingChangeStatus.APPLIED;
            case NOOP -> WorkerSchedulingChangeStatus.UNCHANGED;
            case STALE -> result.score() == null
                    ? WorkerSchedulingChangeStatus.MISSING : WorkerSchedulingChangeStatus.UNCHANGED;
            case INVALID -> WorkerSchedulingChangeStatus.CONFLICT;
        };
    }

    @Override
    public WorkerSchedulingChangeStatus resumeScheduling(String homeBucketId, String workerId) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        var state = readScoreStates(homeBucketId, List.of(workerId)).get(workerId);
        if (state == null) return WorkerSchedulingChangeStatus.MISSING;
        if (state.timeMillis() != PAUSE_TIME_MILLIS) return WorkerSchedulingChangeStatus.UNCHANGED;
        var result = releaseScoreHolds(homeBucketId, Map.of(workerId, state.score()),
                currentTimeMillis.getAsLong()).get(workerId);
        return switch (result.status()) {
            case TRANSITIONED -> WorkerSchedulingChangeStatus.APPLIED;
            case NOOP -> WorkerSchedulingChangeStatus.UNCHANGED;
            case STALE, INVALID -> WorkerSchedulingChangeStatus.CONFLICT;
        };
    }

    private Map<String, WorkerScoreState> readScoreStates(String homeBucketId, List<String> workerIds) {
        List<Double> loaded = readScores(homeBucketId, workerIds);
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        for (int index = 0; index < workerIds.size(); index++) {
            Double rawScore = loaded.get(index);
            states.put(
                    workerIds.get(index),
                    rawScore == null
                            ? null
                            : decodeState(workerIds.get(index), rawScore)
            );
        }
        return states;
    }

    @Override
    public Map<String, Long> observeDueHotScoreCandidates(
            String workerGroupId,
            Long hotEligibilityFloorMillis,
            int limit
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("candidate observation requires limit 1..100");
        }
        long minimumScore;
        if (hotEligibilityFloorMillis == null) {
            minimumScore = MIN_BASE;
        } else if (!validTimeMillis(hotEligibilityFloorMillis)) {
            return Map.of();
        } else {
            minimumScore = Math.max(
                    MIN_BASE,
                    absoluteScore(
                            hotEligibilityFloorMillis / SLOT_MILLIS,
                            SOFT_MARK
                    )
            );
        }
        List<String> rows = readDueHead(workerGroupId, minimumScore, limit);
        LinkedHashMap<String, Long> candidates = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i += 2) {
            try {
                var state = decodeState(rows.get(i), Double.parseDouble(rows.get(i + 1)));
                candidates.put(state.workerId(), state.score());
            } catch (IllegalStateException | NumberFormatException corrupt) {
                // The raw-row budget includes corrupt scores; observation does not repair or skip ahead.
            }
        }
        return java.util.Collections.unmodifiableMap(candidates);
    }

    @Override
    public Map<String, Long> observeHotCandidatesBefore(
            String homeBucketId,
            long hotCutoffMillis,
            int limit
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit <= 0 || !validTimeMillis(hotCutoffMillis)) {
            return Map.of();
        }
        long cutoffScore = absoluteScore(
                hotCutoffMillis / SLOT_MILLIS,
                SOFT_MARK
        );
        if (cutoffScore <= MIN_BASE) {
            return Map.of();
        }
        return rangeWorkerCandidates(
                homeBucketId,
                MIN_BASE,
                cutoffScore - 1,
                limit
        );
    }

    @Override
    public Map<String, Long> observeRecoveryRecheckCandidates(
            String homeBucketId,
            int limit
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit <= 0) {
            return Map.of();
        }
        long currentTimeSlot = redisTimeMillis() / SLOT_MILLIS;
        long dueTimeSlot = currentTimeSlot - 1;
        if (dueTimeSlot < MIN_TIME_SLOT) {
            return Map.of();
        }
        long windowStart = COLD_PARK_TIME_SLOT + 1;
        if (dueTimeSlot < windowStart) {
            return Map.of();
        }
        long maximumScore = -absoluteScore(
                windowStart,
                SOFT_MARK
        );
        long minimumScore = -absoluteScore(
                dueTimeSlot,
                SEALED_MARK
        );
        return rangeWorkerCandidates(
                homeBucketId,
                minimumScore,
                maximumScore,
                limit
        );
    }

    @Override
    public Set<String> initializeRegisteredScores(String homeBucketId, List<String> workerIds) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null || workerIds.isEmpty() || workerIds.size() > MAX_REGISTRATION_BATCH_SIZE
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique IDs");
        }
        workerIds.forEach(id -> requireNonBlank(id, "workerId"));
        long coldScore = -absoluteScore(COLD_PARK_TIME_SLOT, SOFT_MARK);
        return initializeAbsent(homeBucketId, workerIds, coldScore);
    }

    @Override
    public List<String> sampleRegisteredWorkerIds(String homeBucketId, int limit) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit < 1 || limit > MAX_REGISTERED_WORKER_SAMPLE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and "
                    + MAX_REGISTERED_WORKER_SAMPLE_LIMIT);
        }
        return sampleMembers(homeBucketId, limit);
    }

    private Map<String, WorkerScoreTransitionResult> advanceCurrentScoresAndSeal(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null) {
            throw new IllegalArgumentException(
                    "workerIds must be present"
            );
        }
        LinkedHashSet<String> uniqueWorkerIds = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            requireNonBlank(workerId, "workerId");
            uniqueWorkerIds.add(workerId);
        }
        if (uniqueWorkerIds.isEmpty()) {
            return Map.of();
        }
        if (!validTimeMillis(targetTimeMillis)) {
            return uniformResults(
                    uniqueWorkerIds,
                    WorkerScoreTransitionStatus.INVALID
            );
        }

        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;
        long targetMinAbsoluteScore = absoluteScore(
                targetTimeSlot,
                SOFT_MARK
        );
        return advanceCurrentTimeAndSeal(homeBucketId, uniqueWorkerIds, targetMinAbsoluteScore);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            acquireObservedHotScoreLeases(
                    String homeBucketId,
                    Map<String, Long> observedScores,
                    long targetTimeMillis
            ) {
        return updateObservedHotLeases(
                homeBucketId,
                observedScores,
                targetTimeMillis,
                false,
                false
        );
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            transferObservedHotScoreLeases(
                    String homeBucketId,
                    Map<String, Long> expectedScores,
                    long targetTimeMillis,
                    boolean seal
            ) {
        return updateObservedHotLeases(
                homeBucketId,
                expectedScores,
                targetTimeMillis,
                true,
                seal
        );
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> transferCurrentHotScoreLeases(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis,
            boolean seal
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null) {
            throw new IllegalArgumentException("workerIds must be present");
        }
        List<String> ordered = new ArrayList<>(workerIds);
        ordered.forEach(id -> requireNonBlank(id, "workerId"));
        if (new LinkedHashSet<>(ordered).size() != ordered.size()) {
            throw new IllegalArgumentException("workerIds must be unique");
        }
        if (ordered.isEmpty()) {
            return Map.of();
        }
        if (!validTimeMillis(targetTimeMillis)) {
            return uniformResults(ordered, WorkerScoreTransitionStatus.INVALID);
        }
        long requestedBase = absoluteScore(targetTimeMillis / SLOT_MILLIS, SOFT_MARK);
        LinkedHashMap<String, WorkerScoreTransitionResult> results = new LinkedHashMap<>();
        for (int offset = 0; offset < ordered.size(); offset += MAX_SCORE_BATCH_SIZE) {
            List<String> batch = ordered.subList(offset, Math.min(offset + MAX_SCORE_BATCH_SIZE, ordered.size()));
            List<String> arguments = new ArrayList<>(5 + batch.size());
            arguments.addAll(List.of(Long.toString(requestedBase), Long.toString(SLOT_MILLIS),
                    Integer.toString(SLOT_FACTOR), Integer.toString(seal ? SEALED_MARK : SOFT_MARK),
                    Long.toString(absoluteScore(MAX_TIME_SLOT, SEALED_MARK))));
            arguments.addAll(batch);
            results.putAll(executeBatch(homeBucketId, batch, TRANSFER_CURRENT_SCRIPT, arguments, "current_hot_leases"));
        }
        return results;
    }

    private Map<String, WorkerScoreTransitionResult> updateObservedHotLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis,
            boolean transfer,
            boolean seal
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (observedScores == null) {
            throw new IllegalArgumentException(
                    "observedScores must be present"
            );
        }
        if (observedScores.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> ordered = new LinkedHashMap<>();
        observedScores.forEach((workerId, observedScore) -> {
            requireNonBlank(workerId, "workerId");
            if (observedScore == null) {
                throw new IllegalArgumentException(
                        "observedScore must be present"
                );
            }
            ordered.put(workerId, observedScore);
        });
        if (!validTimeMillis(targetTimeMillis)) {
            return uniformResults(
                    ordered.keySet(),
                    WorkerScoreTransitionStatus.INVALID
            );
        }
        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        ordered.forEach((workerId, observedScore) -> {
            WorkerScoreState state;
            try {
                state = decodeState(workerId, observedScore.doubleValue());
            } catch (IllegalStateException error) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            if (state.polarity() != WorkerScorePolarity.HOT_ACQUIRE) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            pending.put(workerId, observedScore);
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(pending.keySet());
        long requestedBase = absoluteScore(targetTimeSlot, SOFT_MARK);
        for (int offset = 0; offset < ids.size(); offset += MAX_SCORE_BATCH_SIZE) {
            List<String> batch = ids.subList(offset, Math.min(offset + MAX_SCORE_BATCH_SIZE, ids.size()));
            List<String> arguments = new ArrayList<>((transfer ? 4 : 3) + batch.size() * 2);
            arguments.addAll(List.of(Long.toString(requestedBase), Long.toString(SLOT_MILLIS),
                    Integer.toString(SLOT_FACTOR)));
            if (transfer) {
                arguments.add(Integer.toString(seal ? SEALED_MARK : SOFT_MARK));
            }
            batch.forEach(id -> {
                long observed = pending.get(id);
                arguments.add(id);
                arguments.add(Long.toString(observed));
            });
            transitioned.putAll(executeBatch(homeBucketId, batch,
                    transfer ? TRANSFER_OBSERVED_SCRIPT : ACQUIRE_DUE_SCRIPT, arguments, "exact_hot_leases"));
        }
        return mergeOrderedResults(ordered.keySet(), immediate, transitioned);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> sealCurrentScoreHolds(
            String homeBucketId,
            List<String> workerIds
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null || workerIds.isEmpty() || workerIds.size() > 100
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique IDs");
        }
        workerIds.forEach(id -> requireNonBlank(id, "workerId"));
        List<String> arguments = new ArrayList<>(workerIds.size() + 2);
        arguments.add(Long.toString(absoluteScore(MAX_TIME_SLOT, SEALED_MARK)));
        arguments.add(Integer.toString(SLOT_FACTOR));
        arguments.addAll(workerIds);
        return executeBatch(homeBucketId, workerIds, SEAL_CURRENT_SCRIPT,
                arguments, "seal_current_score_holds");
    }

    @Override
    public WorkerScoreTransitionResult toggleCurrentPolarity(
            String homeBucketId,
            String workerId,
            long observedScore
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        WorkerScoreState observed;
        try {
            observed = decodeState(workerId, (double) observedScore);
        } catch (IllegalStateException error) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
        return compareAndSet(homeBucketId, workerId, observedScore, -observed.score());
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> deferObservedToRecovery(
            String homeBucketId, Map<String, Long> observedScores, long delayMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        LinkedHashMap<String, Long> ordered = boundedWorkerValues(
                observedScores,
                "observedScores"
        );
        if (ordered.isEmpty()) {
            return Map.of();
        }
        if (delayMillis <= 0 || delayMillis >= MAX_TIME_MILLIS) {
            return uniformResults(ordered.keySet(), WorkerScoreTransitionStatus.INVALID);
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, WorkerScoreState> pending = new LinkedHashMap<>();
        ordered.forEach((workerId, observedScore) -> {
            WorkerScoreState state;
            try {
                state = decodeState(workerId, (double) observedScore);
            } catch (IllegalStateException error) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            if (state.polarity() == WorkerScorePolarity.RECOVERY_RECHECK
                    && state.timeMillis() <= COLD_PARK_TIME_SLOT * SLOT_MILLIS) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            pending.put(workerId, state);
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            List<String> arguments = new ArrayList<>();
            arguments.add(Long.toString(SLOT_MILLIS));
            arguments.add(Integer.toString(SLOT_FACTOR));
            arguments.add(Long.toString(MAX_TIME_SLOT));
            arguments.add(Long.toString(delayMillis));
            pending.forEach((workerId, state) -> {
                arguments.add(workerId);
                arguments.add(Long.toString(state.score()));
                arguments.add(Integer.toString(state.mark()));
            });
            transitioned.putAll(executeBatch(homeBucketId, pending.keySet(),
                    DEFER_DUE_SCRIPT, arguments, "Worker score defer"));
        }
        return mergeOrderedResults(ordered.keySet(), immediate, transitioned);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            rewriteCurrentPolarityWithinTimeFence(
                    String homeBucketId,
                    Map<String, Long> suppliedTimeMillisByWorkerId,
                    WorkerScorePolarity targetPolarity,
                    boolean refreshPastTime
            ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (targetPolarity == null) {
            throw new IllegalArgumentException(
                    "targetPolarity must be present"
            );
        }
        LinkedHashMap<String, Long> ordered = boundedWorkerValues(
                suppliedTimeMillisByWorkerId,
                "suppliedTimeMillisByWorkerId"
        );
        if (ordered.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        ordered.forEach((workerId, suppliedTimeMillis) -> {
            if (suppliedTimeMillis <= 0
                    || !validTimeMillis(suppliedTimeMillis)) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            pending.put(
                    workerId,
                    suppliedTimeMillis / SLOT_MILLIS
            );
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            List<String> arguments = new ArrayList<>();
            arguments.add(Integer.toString(polarityValue(targetPolarity)));
            arguments.add(Boolean.toString(refreshPastTime));
            arguments.add(Long.toString(SLOT_MILLIS));
            arguments.add(Integer.toString(SLOT_FACTOR));
            arguments.add(Long.toString(MAX_TIME_SLOT));
            pending.forEach((workerId, suppliedTimeSlot) -> {
                arguments.add(workerId);
                arguments.add(Long.toString(suppliedTimeSlot));
            });
            transitioned.putAll(executeBatch(homeBucketId, pending.keySet(),
                    CURRENT_POLARITY_SCRIPT, arguments, "Worker score polarity"));
        }
        return mergeOrderedResults(ordered.keySet(), immediate, transitioned);
    }

    @Override
    public WorkerScoreTransitionResult parkObservedRecoveryScore(
            String homeBucketId,
            String workerId,
            long observedScore
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        WorkerScoreState observed;
        try {
            observed = decodeState(workerId, (double) observedScore);
        } catch (IllegalStateException error) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
        if (observed.polarity()
                != WorkerScorePolarity.RECOVERY_RECHECK) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
        long nextScore = -absoluteScore(
                COLD_PARK_TIME_SLOT,
                observed.mark()
        );
        return compareAndSet(homeBucketId, workerId, observedScore, nextScore);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> releaseScoreHolds(
            String homeBucketId, Map<String, Long> observedScores, long releaseTimeMillis
    ) {
        return releaseObservedScores(homeBucketId, observedScores, releaseTimeMillis, false);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> releaseObservedHotScoreHolds(
            String homeBucketId, Map<String, Long> observedHotScores, long releaseTimeMillis
    ) {
        return releaseObservedScores(homeBucketId, observedHotScores, releaseTimeMillis, true);
    }

    private Map<String, WorkerScoreTransitionResult> releaseObservedScores(
            String group, Map<String, Long> observations, long releaseTimeMillis, boolean acceptCounterpart
    ) {
        requireNonBlank(group, "homeBucketId");
        if (observations == null) {
            throw new IllegalArgumentException(acceptCounterpart
                    ? "observedHotScores must be present" : "observedScores must be present");
        }
        if (observations.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> ordered = new LinkedHashMap<>();
        observations.forEach((id, score) -> {
            requireNonBlank(id, "workerId");
            if (score == null) {
                throw new IllegalArgumentException(acceptCounterpart
                        ? "observedHotScore must be present" : "observedScore must be present");
            }
            ordered.put(id, score);
        });
        if (!validTimeMillis(releaseTimeMillis)) {
            return uniformResults(ordered.keySet(), WorkerScoreTransitionStatus.INVALID);
        }
        // Keep the release paths' one TIME sample before per-observation validation.
        long currentSlotStartMillis = redisTimeMillis() / SLOT_MILLIS * SLOT_MILLIS;
        if (releaseTimeMillis < currentSlotStartMillis) {
            return uniformResults(ordered.keySet(), WorkerScoreTransitionStatus.INVALID);
        }
        long releaseSlot = releaseTimeMillis / SLOT_MILLIS;
        long releaseBase = absoluteScore(releaseSlot, SOFT_MARK);
        LinkedHashMap<String, WorkerScoreTransitionResult> immediate = new LinkedHashMap<>();
        LinkedHashMap<String, long[]> targets = new LinkedHashMap<>();
        ordered.forEach((id, observed) -> {
            boolean valid = observed != Long.MIN_VALUE;
            if (acceptCounterpart) {
                try {
                    valid = observed > 0 && decodeState(id, observed.doubleValue()).polarity()
                            == WorkerScorePolarity.HOT_ACQUIRE;
                } catch (IllegalStateException invalid) {
                    valid = false;
                }
            }
            if (!valid || releaseBase >= Math.abs(observed)) {
                immediate.put(id, transition(WorkerScoreTransitionStatus.INVALID));
            } else {
                targets.put(id, new long[]{observed, replaceTime(observed, releaseSlot)});
            }
        });
        Map<String, WorkerScoreTransitionResult> changed = compareAndSet(group, targets, acceptCounterpart);
        if (acceptCounterpart && !changed.isEmpty()) {
            changed.replaceAll((id, result) -> result.status() == WorkerScoreTransitionStatus.NOOP
                    ? new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, result.score())
                    : result);
        }
        return mergeOrderedResults(ordered.keySet(), immediate, changed);
    }

    // Fixed Redis operations; public entry points above only prepare and combine inputs/results.
    private Map<String, WorkerScoreTransitionResult> advanceCurrentTimeAndSeal(
            String homeBucketId, Set<String> uniqueWorkerIds, long targetMinAbsoluteScore
    ) {
        RedisAsyncCommands<String, String> async = connection().async();
        List<RedisFuture<Object>> futures = new ArrayList<>(
                uniqueWorkerIds.size()
        );
        String key = scoreKey(homeBucketId);
        for (String workerId : uniqueWorkerIds) {
            futures.add(async.eval(
                    ADVANCE_AND_SEAL_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    workerId,
                    Long.toString(targetMinAbsoluteScore),
                    Integer.toString(SLOT_FACTOR)
            ));
        }
        return collectScriptResults(uniqueWorkerIds, futures);
    }

    private List<Double> readScores(String group, List<String> workerIds) {
        return commands().zmscore(scoreKey(group), workerIds.toArray(String[]::new));
    }

    private List<String> readDueHead(String group, long minimumScore, int limit) {
        return commands().eval(DUE_HOT_HEAD_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey(group)}, Long.toString(minimumScore), Integer.toString(limit),
                Long.toString(SLOT_MILLIS), Integer.toString(SLOT_FACTOR));
    }

    private Set<String> initializeAbsent(String group, List<String> workerIds, long initialScore) {
        List<String> arguments = new ArrayList<>(workerIds.size() + 1);
        arguments.add(Long.toString(initialScore));
        arguments.addAll(workerIds);
        List<String> created = commands().eval(INITIALIZE_REGISTERED_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey(group)}, arguments.toArray(String[]::new));
        return new LinkedHashSet<>(created);
    }

    private List<String> sampleMembers(String group, int limit) {
        return commands().zrandmember(scoreKey(group), limit);
    }

    private Map<String, WorkerScoreTransitionResult> executeBatch(
            String group, Iterable<String> ids, String script, List<String> arguments, String operation
    ) {
        return batchScriptResults(ids, commands().eval(script, ScriptOutputType.MULTI,
                new String[]{scoreKey(group)}, arguments.toArray(String[]::new)), operation);
    }

    private WorkerScoreTransitionResult compareAndSet(
            String group, String id, long observed, long target
    ) {
        return scriptResult(commands().eval(CAS_UPDATE_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey(group)}, id, Long.toString(observed), Long.toString(target), ""));
    }

    /** Each tuple contains the exact observation and the complete Java-computed target. */
    private Map<String, WorkerScoreTransitionResult> compareAndSet(
            String group, Map<String, long[]> targets, boolean acceptCounterpart
    ) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        RedisAsyncCommands<String, String> async = connection().async();
        List<RedisFuture<Object>> futures = new ArrayList<>(targets.size());
        String[] keys = {scoreKey(group)};
        targets.forEach((id, pair) -> futures.add(async.eval(
                CAS_UPDATE_SCRIPT, ScriptOutputType.MULTI, keys, id,
                Long.toString(pair[0]), Long.toString(pair[1]),
                acceptCounterpart ? Long.toString(-pair[0]) : "")));
        return collectScriptResults(targets.keySet(), futures);
    }

    private static Map<String, WorkerScoreTransitionResult>
            collectScriptResults(
                    Iterable<String> workerIds,
                    List<RedisFuture<Object>> futures
            ) {
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        int index = 0;
        for (String workerId : workerIds) {
            results.put(
                    workerId,
                    scriptResult(
                            futures.get(index).toCompletableFuture().join()
                    )
            );
            index++;
        }
        return results;
    }

    private static Map<String, WorkerScoreTransitionResult>
            batchScriptResults(
                    Iterable<String> workerIds,
                    Object raw,
                    String operation
            ) {
        List<String> expectedIds = new ArrayList<>();
        workerIds.forEach(expectedIds::add);
        if (!(raw instanceof List<?> values)
                || values.size() != expectedIds.size() * 3) {
            throw new IllegalStateException(
                    operation + " batch result is invalid"
            );
        }
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index += 3) {
            String workerId = String.valueOf(values.get(index));
            WorkerScoreTransitionResult result = scriptResult(List.of(
                    values.get(index + 1),
                    values.get(index + 2)
            ));
            if (results.put(workerId, result) != null) {
                throw new IllegalStateException(
                        operation + " batch contains duplicate ids"
                );
            }
        }
        if (!List.copyOf(results.keySet()).equals(expectedIds)) {
            throw new IllegalStateException(
                    operation + " batch identities are invalid"
            );
        }
        return results;
    }

    private static Map<String, WorkerScoreTransitionResult>
            mergeOrderedResults(
                    Iterable<String> workerIds,
                    Map<String, WorkerScoreTransitionResult> immediate,
                    Map<String, WorkerScoreTransitionResult> transitioned
            ) {
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        workerIds.forEach(workerId -> results.put(
                workerId,
                immediate.containsKey(workerId)
                        ? immediate.get(workerId)
                        : transitioned.get(workerId)
        ));
        return results;
    }

    private static <T> LinkedHashMap<String, T> boundedWorkerValues(
            Map<String, T> values,
            String name
    ) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        if (values.size() > MAX_SCORE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    name + " must contain at most "
                            + MAX_SCORE_BATCH_SIZE + " workers"
            );
        }
        LinkedHashMap<String, T> ordered = new LinkedHashMap<>();
        values.forEach((workerId, value) -> {
            requireNonBlank(workerId, "workerId");
            if (value == null) {
                throw new IllegalArgumentException(
                        name + " must not contain null values"
                );
            }
            ordered.put(workerId, value);
        });
        return ordered;
    }

    private static Map<String, WorkerScoreTransitionResult> uniformResults(
            Iterable<String> workerIds,
            WorkerScoreTransitionStatus status
    ) {
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        workerIds.forEach(workerId -> results.put(
                workerId,
                transition(status)
        ));
        return results;
    }

    private static WorkerScoreTransitionResult transition(
            WorkerScoreTransitionStatus status
    ) {
        return new WorkerScoreTransitionResult(status, null);
    }

    private Map<String, Long> rangeWorkerCandidates(
            String homeBucketId,
            long minimumScore,
            long maximumScore,
            int limit
    ) {
        List<ScoredValue<String>> rows = commands()
                .zrevrangebyscoreWithScores(
                        scoreKey(homeBucketId),
                        maximumScore,
                        minimumScore,
                        0,
                        limit
                );
        var observations = new LinkedHashMap<String, Long>();
        for (ScoredValue<String> row : rows) {
            observations.put(
                    row.getValue(),
                    scoreToLong(row.getScore())
            );
        }
        return java.util.Collections.unmodifiableMap(observations);
    }

    private static WorkerScoreTransitionResult scriptResult(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException(
                    "Worker score script result is invalid"
            );
        }
        WorkerScoreTransitionStatus status = switch (
                String.valueOf(values.get(0))
        ) {
            case "transitioned" ->
                    WorkerScoreTransitionStatus.TRANSITIONED;
            case "noop" -> WorkerScoreTransitionStatus.NOOP;
            case "stale" -> WorkerScoreTransitionStatus.STALE;
            case "invalid" -> WorkerScoreTransitionStatus.INVALID;
            default -> throw new IllegalStateException(
                    "Worker score script status is invalid"
            );
        };
        Object rawScore = values.size() > 1 ? values.get(1) : null;
        Long score = rawScore != null && !String.valueOf(rawScore).isEmpty()
                ? scoreToLong(rawScore)
                : null;
        return new WorkerScoreTransitionResult(status, score);
    }

    private long redisTimeMillis() {
        List<String> parts = commands().time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }

    private String scoreKey(String homeBucketId) {
        return keyspace.base() + ":worker:score:" + homeBucketId;
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

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
