package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.score.WorkerScoreCore;
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

public final class RedisWorkerScoreCore
        implements WorkerScoreCore, AutoCloseable {

    private static final long COLD_PARK_TIME_SLOT = MIN_TIME_SLOT + 1;
    private static final long RECOVERY_LOOKBACK_MILLIS = 86_400_000;
    private static final String CURRENT_REWRITE_SCRIPT = """
            local key = KEYS[1]
            local worker_id = ARGV[1]
            local target_min_abs_score = tonumber(ARGV[2])
            local target_lane_rank = tonumber(ARGV[3])
            local slot_factor = tonumber(ARGV[4])
            local dirty_factor = tonumber(ARGV[5])

            local stored = redis.call("ZSCORE", key, worker_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            local abs_score = math.abs(stored_score)
            if abs_score <= 0 then
              return {"invalid", stored_score}
            end
            local sign = stored_score / abs_score

            local slot_remainder = abs_score % slot_factor
            local stored_lane_rank = math.floor(
              slot_remainder / dirty_factor
            )
            local stored_dirty = slot_remainder % dirty_factor

            if abs_score >= target_min_abs_score then
              return {"stale", stored_score}
            end

            if target_lane_rank < 0 then
              target_lane_rank = stored_lane_rank
            end

            local target_abs_score =
              target_min_abs_score
              + target_lane_rank * dirty_factor
              + stored_dirty
            if target_abs_score <= 0 then
              return {"invalid", stored_score}
            end
            local target_score = sign * target_abs_score
            redis.call("ZADD", key, target_score, worker_id)
            return {"transitioned", target_score}
            """;

    private static final String CAS_UPDATE_SCRIPT = """
            local key = KEYS[1]
            local worker_id = ARGV[1]
            local observed_score = tonumber(ARGV[2])
            local next_score = tonumber(ARGV[3])

            local stored = redis.call("ZSCORE", key, worker_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            if stored_score ~= observed_score then
              return {"stale", stored_score}
            end

            if next_score == stored_score then
              return {"noop", stored_score}
            end

            redis.call("ZADD", key, next_score, worker_id)
            return {"transitioned", next_score}
            """;

    private static final String SERVICEABILITY_HOLD_SCRIPT = """
            local key = KEYS[1]
            local slot_millis = tonumber(ARGV[1])
            local slot_factor = tonumber(ARGV[2])
            local max_time_slot = tonumber(ARGV[3])
            local redis_time = redis.call("TIME")
            local now_millis = tonumber(redis_time[1]) * 1000
                + math.floor(tonumber(redis_time[2]) / 1000)
            local now_time_slot = math.floor(now_millis / slot_millis)
            local result = {}

            for index = 4, #ARGV, 3 do
              local worker_id = ARGV[index]
              local observed_score = tonumber(ARGV[index + 1])
              local target_low_bits = tonumber(ARGV[index + 2])
              local status = "stale"
              local result_score = ""
              local stored = redis.call("ZSCORE", key, worker_id)
              if stored then
                local stored_score = tonumber(stored)
                result_score = stored
                if stored_score == observed_score then
                  local target_abs_score = now_time_slot * slot_factor
                      + target_low_bits
                  if now_time_slot < 0
                      or now_time_slot > max_time_slot
                      or target_low_bits < 0
                      or target_low_bits >= slot_factor
                      or target_abs_score <= 0 then
                    status = "invalid"
                  else
                    local target_score = -target_abs_score
                    if target_score == stored_score then
                      status = "noop"
                    else
                      redis.call("ZADD", key, target_score, worker_id)
                      status = "transitioned"
                      result_score = target_score
                    end
                  end
                end
              end
              result[#result + 1] = worker_id
              result[#result + 1] = status
              result[#result + 1] = result_score
            end
            return result
            """;

    private static final String SERVICEABILITY_EVIDENCE_SCRIPT = """
            local key = KEYS[1]
            local target_sign = tonumber(ARGV[1])
            local slot_millis = tonumber(ARGV[2])
            local slot_factor = tonumber(ARGV[3])
            local max_time_slot = tonumber(ARGV[4])
            local redis_time = redis.call("TIME")
            local now_millis = tonumber(redis_time[1]) * 1000
                + math.floor(tonumber(redis_time[2]) / 1000)
            local now_time_slot = math.floor(now_millis / slot_millis)
            local result = {}

            for index = 5, #ARGV, 2 do
              local worker_id = ARGV[index]
              local evidence_time_slot = tonumber(ARGV[index + 1])
              local status = "stale"
              local result_score = ""
              local stored = redis.call("ZSCORE", key, worker_id)
              if stored then
                local stored_score = tonumber(stored)
                local stored_abs_score = math.abs(stored_score)
                local stored_time_slot = math.floor(
                  stored_abs_score / slot_factor
                )
                result_score = stored
                if target_sign ~= 1 and target_sign ~= -1
                    or now_time_slot < 0
                    or now_time_slot > max_time_slot
                    or stored_abs_score <= 0
                    or stored_time_slot < 0
                    or stored_time_slot > max_time_slot
                    or evidence_time_slot < 0
                    or evidence_time_slot > max_time_slot then
                  status = "invalid"
                elseif stored_time_slot > now_time_slot
                    or stored_time_slot <= evidence_time_slot then
                  local target_abs_score = stored_abs_score
                  if target_sign == 1
                      and stored_time_slot <= now_time_slot
                      and stored_time_slot < evidence_time_slot then
                    local low_bits = stored_abs_score % slot_factor
                    target_abs_score = evidence_time_slot * slot_factor
                        + low_bits
                  end
                  local target_score = target_sign * target_abs_score
                  if target_score == stored_score then
                    status = "noop"
                  else
                    redis.call("ZADD", key, target_score, worker_id)
                    status = "transitioned"
                    result_score = target_score
                  end
                end
              end
              result[#result + 1] = worker_id
              result[#result + 1] = status
              result[#result + 1] = result_score
            end
            return result
            """;

    private static final String COMPLETED_HOT_RELEASE_SCRIPT = """
            -- worker_score_release_completed_hot_hold
            local key = KEYS[1]
            local worker_id = ARGV[1]
            local observed_hot_score = tonumber(ARGV[2])
            local release_slot_base = tonumber(ARGV[3])
            local slot_factor = tonumber(ARGV[4])

            if not observed_hot_score or observed_hot_score <= 0 then
              return {"invalid"}
            end
            if release_slot_base >= observed_hot_score then
              return {"invalid"}
            end

            local recovery_counterpart = -observed_hot_score

            local stored = redis.call("ZSCORE", key, worker_id)
            if not stored then
              return {"stale"}
            end
            local stored_score = tonumber(stored)
            if stored_score ~= observed_hot_score
                and stored_score ~= recovery_counterpart then
              return {"stale", stored_score}
            end

            local stored_abs_score = math.abs(stored_score)
            if release_slot_base >= stored_abs_score then
              return {"invalid", stored_score}
            end
            local stored_low_bits = stored_abs_score % slot_factor
            local next_score = release_slot_base + stored_low_bits
            redis.call("ZADD", key, next_score, worker_id)
            return {"transitioned", next_score}
            """;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerScoreCore(
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
    public Map<String, WorkerScoreState> getScoreStates(
            String homeBucketId,
            List<String> workerIds
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null) {
            throw new IllegalArgumentException(
                    "workerIds must be present"
            );
        }
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        workerIds.forEach(workerId ->
                requireNonBlank(workerId, "workerId"));
        List<Double> loaded = commands().zmscore(
                scoreKey(homeBucketId),
                workerIds.toArray(String[]::new)
        );
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
            String homeBucketId,
            Long hotEligibilityFloorMillis,
            int limit
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit <= 0) {
            return Map.of();
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
                            MIN_LANE_RANK,
                            MIN_DIRTY
                    )
            );
        }
        long dueTimeSlot = redisTimeMillis() / SLOT_MILLIS - 1;
        if (dueTimeSlot < MIN_TIME_SLOT) {
            return Map.of();
        }
        List<ScoredValue<String>> rows = commands().zrangebyscoreWithScores(
                scoreKey(homeBucketId),
                minimumScore,
                absoluteScore(
                        dueTimeSlot,
                        MAX_LANE_RANK,
                        MAX_DIRTY
                ),
                0,
                limit
        );
        LinkedHashMap<String, Long> candidates = new LinkedHashMap<>();
        rows.forEach(row -> candidates.put(
                row.getValue(),
                scoreToLong(row.getScore())
        ));
        return candidates;
    }

    @Override
    public Map<String, Long> observeDueHotScores(
            String homeBucketId,
            List<String> workerIds,
            Long hotEligibilityFloorMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null) {
            throw new IllegalArgumentException(
                    "workerIds must be present"
            );
        }
        List<String> uniqueWorkerIds = new ArrayList<>(
                new LinkedHashSet<>(workerIds)
        );
        if (uniqueWorkerIds.isEmpty()) {
            return Map.of();
        }
        uniqueWorkerIds.forEach(workerId ->
                requireNonBlank(workerId, "workerId"));
        long floorTimeSlot;
        if (hotEligibilityFloorMillis == null) {
            floorTimeSlot = MIN_TIME_SLOT;
        } else if (!validTimeMillis(hotEligibilityFloorMillis)) {
            return Map.of();
        } else {
            floorTimeSlot = hotEligibilityFloorMillis / SLOT_MILLIS;
        }
        List<Double> scores = commands().zmscore(
                scoreKey(homeBucketId),
                uniqueWorkerIds.toArray(String[]::new)
        );
        long dueTimeSlot = redisTimeMillis() / SLOT_MILLIS - 1;
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (int index = 0; index < uniqueWorkerIds.size(); index++) {
            Double raw = scores.get(index);
            if (raw == null) {
                continue;
            }
            WorkerScoreState state;
            try {
                state = decodeState(uniqueWorkerIds.get(index), raw);
            } catch (IllegalStateException error) {
                continue;
            }
            long timeSlot = state.timeMillis() / SLOT_MILLIS;
            if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE
                    && timeSlot >= floorTimeSlot
                    && timeSlot <= dueTimeSlot) {
                observed.put(state.workerId(), state.score());
            }
        }
        return observed;
    }

    @Override
    public Map<String, Long> observeActiveHotScoreLeases(
            String homeBucketId,
            List<String> workerIds,
            long expectedLeaseUntilMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null) {
            throw new IllegalArgumentException(
                    "workerIds must be present"
            );
        }
        if (workerIds.size() > MAX_SERVICEABILITY_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "workerIds must contain at most "
                            + MAX_SERVICEABILITY_BATCH_SIZE + " workers"
            );
        }
        LinkedHashSet<String> uniqueWorkerIds = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            requireNonBlank(workerId, "workerId");
            if (!uniqueWorkerIds.add(workerId)) {
                throw new IllegalArgumentException(
                        "workerIds must be unique"
                );
            }
        }
        if (uniqueWorkerIds.isEmpty()
                || !validTimeMillis(expectedLeaseUntilMillis)) {
            return Map.of();
        }

        long nowTimeSlot = redisTimeMillis() / SLOT_MILLIS;
        long expectedTimeSlot = expectedLeaseUntilMillis / SLOT_MILLIS;
        if (expectedTimeSlot <= nowTimeSlot) {
            return Map.of();
        }

        List<String> orderedWorkerIds = List.copyOf(uniqueWorkerIds);
        List<Double> scores = commands().zmscore(
                scoreKey(homeBucketId),
                orderedWorkerIds.toArray(String[]::new)
        );
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (int index = 0; index < orderedWorkerIds.size(); index++) {
            Double raw = scores.get(index);
            if (raw == null) {
                continue;
            }
            WorkerScoreState state;
            try {
                state = decodeState(orderedWorkerIds.get(index), raw);
            } catch (IllegalStateException error) {
                continue;
            }
            if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE
                    && state.dirty() == MIN_DIRTY
                    && state.timeMillis() / SLOT_MILLIS
                    == expectedTimeSlot) {
                observed.put(state.workerId(), state.score());
            }
        }
        return observed;
    }

    @Override
    public List<WorkerScoreObservation> acquireHotCandidatesBefore(
            String homeBucketId,
            long hotCutoffMillis,
            long maximumScoreExclusive,
            int limit
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit <= 0
                || maximumScoreExclusive < ZERO_SCORE
                || !validTimeMillis(hotCutoffMillis)) {
            return List.of();
        }
        long cutoffScore = absoluteScore(
                hotCutoffMillis / SLOT_MILLIS,
                MIN_LANE_RANK,
                MIN_DIRTY
        );
        if (cutoffScore <= MIN_BASE) {
            return List.of();
        }
        long pageMaximumExclusive = maximumScoreExclusive == ZERO_SCORE
                ? cutoffScore
                : Math.min(cutoffScore, maximumScoreExclusive);
        if (pageMaximumExclusive <= MIN_BASE) {
            return List.of();
        }
        return rangeWorkerCandidates(
                homeBucketId,
                MIN_BASE,
                pageMaximumExclusive - 1,
                limit
        );
    }

    @Override
    public List<WorkerScoreObservation> acquireRecoveryRecheckCandidates(
            String homeBucketId,
            long maximumScoreExclusive,
            int limit
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit <= 0 || maximumScoreExclusive > ZERO_SCORE) {
            return List.of();
        }
        long currentTimeSlot = redisTimeMillis() / SLOT_MILLIS;
        long dueTimeSlot = currentTimeSlot - 1;
        if (dueTimeSlot < MIN_TIME_SLOT) {
            return List.of();
        }
        long recoveryLookbackSlots = RECOVERY_LOOKBACK_MILLIS / SLOT_MILLIS;
        long windowStart = Math.max(
                COLD_PARK_TIME_SLOT + 1,
                currentTimeSlot - recoveryLookbackSlots
        );
        if (dueTimeSlot < windowStart) {
            return List.of();
        }
        long maximumScore = -absoluteScore(
                windowStart,
                MIN_LANE_RANK,
                MIN_DIRTY
        );
        long minimumScore = -absoluteScore(
                dueTimeSlot,
                MAX_LANE_RANK,
                MAX_DIRTY
        );
        long pageMaximumScore = maximumScoreExclusive == ZERO_SCORE
                ? maximumScore
                : Math.min(maximumScore, maximumScoreExclusive - 1);
        if (pageMaximumScore < minimumScore) {
            return List.of();
        }
        return rangeWorkerCandidates(
                homeBucketId,
                minimumScore,
                pageMaximumScore,
                limit
        );
    }

    @Override
    public WorkerScoreTransitionResult initializeHotAcquireScore(
            String homeBucketId,
            String workerId
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        long timeSlot = redisTimeMillis() / SLOT_MILLIS;
        if (timeSlot < MIN_TIME_SLOT || timeSlot > MAX_TIME_SLOT) {
            return new WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID,
                    null
            );
        }
        long initialScore = timeSlot * SLOT_FACTOR
                + (long) MIN_LANE_RANK * DIRTY_FACTOR
                + MIN_DIRTY;
        Long added = commands().zadd(
                scoreKey(homeBucketId),
                ZAddArgs.Builder.nx(),
                initialScore,
                workerId
        );
        if (added != null && added == 1L) {
            return new WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.TRANSITIONED,
                    initialScore
            );
        }
        Double stored = commands().zscore(
                scoreKey(homeBucketId),
                workerId
        );
        if (stored == null) {
            return new WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.STALE,
                    null
            );
        }
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.NOOP,
                scoreToLong(stored)
        );
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> rewriteCurrentScores(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis,
            Integer targetLaneRank
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
        if (!validTimeMillis(targetTimeMillis)
                || targetLaneRank != null
                && !validLaneRank(targetLaneRank)) {
            return uniformResults(
                    uniqueWorkerIds,
                    WorkerScoreTransitionStatus.INVALID
            );
        }

        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;
        long targetMinAbsoluteScore = absoluteScore(
                targetTimeSlot,
                MIN_LANE_RANK,
                MIN_DIRTY
        );
        RedisAsyncCommands<String, String> async = connection().async();
        List<RedisFuture<Object>> futures = new ArrayList<>(
                uniqueWorkerIds.size()
        );
        String key = scoreKey(homeBucketId);
        for (String workerId : uniqueWorkerIds) {
            futures.add(async.eval(
                    CURRENT_REWRITE_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    workerId,
                    Long.toString(targetMinAbsoluteScore),
                    Integer.toString(
                            targetLaneRank == null ? -1 : targetLaneRank
                    ),
                    Integer.toString(SLOT_FACTOR),
                    Integer.toString(DIRTY_FACTOR)
            ));
        }
        return collectScriptResults(uniqueWorkerIds, futures);
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
                false
        );
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            renewActiveHotScoreLeases(
                    String homeBucketId,
                    Map<String, Long> observedScores,
                    long targetTimeMillis
            ) {
        return updateObservedHotLeases(
                homeBucketId,
                observedScores,
                targetTimeMillis,
                true
        );
    }

    private Map<String, WorkerScoreTransitionResult> updateObservedHotLeases(
            String homeBucketId,
            Map<String, Long> observedScores,
            long targetTimeMillis,
            boolean renewal
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
        long currentTimeMillis = redisTimeMillis();
        long currentTimeSlot = currentTimeMillis / SLOT_MILLIS;
        long targetTimeSlot = targetTimeMillis / SLOT_MILLIS;
        if (targetTimeMillis <= currentTimeMillis
                || targetTimeSlot <= currentTimeSlot) {
            return uniformResults(
                    ordered.keySet(),
                    WorkerScoreTransitionStatus.INVALID
            );
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, long[]> pending = new LinkedHashMap<>();
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
            long observedTimeSlot = state.timeMillis() / SLOT_MILLIS;
            if (state.polarity() != WorkerScorePolarity.HOT_ACQUIRE) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            if (!renewal && observedTimeSlot >= currentTimeSlot
                    || renewal && (state.dirty() != MIN_DIRTY
                    || observedTimeSlot < currentTimeSlot)) {
                immediate.put(
                        workerId,
                        new WorkerScoreTransitionResult(
                                WorkerScoreTransitionStatus.STALE,
                                renewal ? observedScore : null
                        )
                );
                return;
            }
            long nextScore = renewal
                    && targetTimeSlot <= observedTimeSlot
                    ? observedScore
                    : absoluteScore(
                            targetTimeSlot,
                            state.laneRank(),
                            MIN_DIRTY
                    );
            pending.put(
                    workerId,
                    new long[]{observedScore, nextScore}
            );
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            RedisAsyncCommands<String, String> async = connection().async();
            List<RedisFuture<Object>> futures = new ArrayList<>(pending.size());
            String key = scoreKey(homeBucketId);
            pending.forEach((workerId, values) -> futures.add(async.eval(
                    CAS_UPDATE_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    workerId,
                    Long.toString(values[0]),
                    Long.toString(values[1])
            )));
            transitioned.putAll(collectScriptResults(
                    pending.keySet(),
                    futures
            ));
        }
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        ordered.keySet().forEach(workerId -> results.put(
                workerId,
                immediate.containsKey(workerId)
                        ? immediate.get(workerId)
                        : transitioned.get(workerId)
        ));
        return results;
    }

    @Override
    public WorkerScoreTransitionResult markCurrentLeaseDirty(
            String homeBucketId,
            String workerId
    ) {
        throw notImplemented("mark_current_lease_dirty");
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
        long timeSlot = observed.timeMillis() / SLOT_MILLIS;
        long nextAbsoluteScore = absoluteScore(
                timeSlot,
                MIN_LANE_RANK,
                observed.dirty()
        );
        if (nextAbsoluteScore < MIN_BASE) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
        long nextScore = observed.polarity()
                == WorkerScorePolarity.HOT_ACQUIRE
                ? -nextAbsoluteScore
                : nextAbsoluteScore;
        return scriptResult(commands().eval(
                CAS_UPDATE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey(homeBucketId)},
                workerId,
                Long.toString(observedScore),
                Long.toString(nextScore)
        ));
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            holdObservedHotForServiceabilityProbes(
                    String homeBucketId,
                    Map<String, Long> observedHotScores
            ) {
        return updateObservedServiceabilityChecks(
                homeBucketId,
                observedHotScores,
                WorkerScorePolarity.HOT_ACQUIRE
        );
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            advanceObservedRecoveryRechecks(
                    String homeBucketId,
                    Map<String, Long> observedRecoveryScores
            ) {
        return updateObservedServiceabilityChecks(
                homeBucketId,
                observedRecoveryScores,
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    private Map<String, WorkerScoreTransitionResult>
            updateObservedServiceabilityChecks(
                    String homeBucketId,
                    Map<String, Long> observedScores,
                    WorkerScorePolarity expectedPolarity
            ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        LinkedHashMap<String, Long> ordered = boundedWorkerValues(
                observedScores,
                "observedScores"
        );
        if (ordered.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, long[]> pending = new LinkedHashMap<>();
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
            if (state.polarity() != expectedPolarity) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            int targetLaneRank = expectedPolarity
                    == WorkerScorePolarity.HOT_ACQUIRE
                    ? MIN_LANE_RANK
                    : state.laneRank() + 1;
            if (!validLaneRank(targetLaneRank)) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            long targetLowBits = (long) targetLaneRank * DIRTY_FACTOR
                    + state.dirty();
            pending.put(
                    workerId,
                    new long[]{observedScore, targetLowBits}
            );
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            List<String> arguments = new ArrayList<>();
            arguments.add(Long.toString(SLOT_MILLIS));
            arguments.add(Integer.toString(SLOT_FACTOR));
            arguments.add(Long.toString(MAX_TIME_SLOT));
            pending.forEach((workerId, values) -> {
                arguments.add(workerId);
                arguments.add(Long.toString(values[0]));
                arguments.add(Long.toString(values[1]));
            });
            transitioned.putAll(batchScriptResults(
                    pending.keySet(),
                    commands().eval(
                            SERVICEABILITY_HOLD_SCRIPT,
                            ScriptOutputType.MULTI,
                            new String[]{scoreKey(homeBucketId)},
                            arguments.toArray(String[]::new)
                    ),
                    "Worker serviceability hold"
            ));
        }
        return mergeOrderedResults(ordered.keySet(), immediate, transitioned);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            applyServiceabilityEvidence(
                    String homeBucketId,
                    Map<String, Long> evidenceTimeMillisByWorkerId,
                    WorkerScorePolarity targetPolarity
            ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (targetPolarity == null) {
            throw new IllegalArgumentException(
                    "targetPolarity must be present"
            );
        }
        LinkedHashMap<String, Long> ordered = boundedWorkerValues(
                evidenceTimeMillisByWorkerId,
                "evidenceTimeMillisByWorkerId"
        );
        if (ordered.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        ordered.forEach((workerId, evidenceTimeMillis) -> {
            if (evidenceTimeMillis <= 0
                    || !validTimeMillis(evidenceTimeMillis)) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            pending.put(
                    workerId,
                    evidenceTimeMillis / SLOT_MILLIS
            );
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            List<String> arguments = new ArrayList<>();
            arguments.add(Integer.toString(targetPolarity.value()));
            arguments.add(Long.toString(SLOT_MILLIS));
            arguments.add(Integer.toString(SLOT_FACTOR));
            arguments.add(Long.toString(MAX_TIME_SLOT));
            pending.forEach((workerId, evidenceTimeSlot) -> {
                arguments.add(workerId);
                arguments.add(Long.toString(evidenceTimeSlot));
            });
            transitioned.putAll(batchScriptResults(
                    pending.keySet(),
                    commands().eval(
                            SERVICEABILITY_EVIDENCE_SCRIPT,
                            ScriptOutputType.MULTI,
                            new String[]{scoreKey(homeBucketId)},
                            arguments.toArray(String[]::new)
                    ),
                    "Worker serviceability evidence"
            ));
        }
        return mergeOrderedResults(ordered.keySet(), immediate, transitioned);
    }

    @Override
    public WorkerScoreTransitionResult exhaustRecoveryRecheck(
            String homeBucketId,
            String workerId,
            long observedScore,
            int maxRecoveryAttempts
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        if (maxRecoveryAttempts <= MIN_LANE_RANK
                || maxRecoveryAttempts > MAX_LANE_RANK) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
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
                maxRecoveryAttempts,
                observed.dirty()
        );
        return scriptResult(commands().eval(
                CAS_UPDATE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey(homeBucketId)},
                workerId,
                Long.toString(observedScore),
                Long.toString(nextScore)
        ));
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> releaseScoreHolds(
            String homeBucketId,
            Map<String, Long> observedScores,
            long releaseTimeMillis
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
        LinkedHashMap<String, Long> orderedObservedScores =
                new LinkedHashMap<>();
        observedScores.forEach((workerId, observedScore) -> {
            requireNonBlank(workerId, "workerId");
            if (observedScore == null) {
                throw new IllegalArgumentException(
                        "observedScore must be present"
                );
            }
            orderedObservedScores.put(workerId, observedScore);
        });
        if (!validTimeMillis(releaseTimeMillis)) {
            return uniformResults(
                    orderedObservedScores.keySet(),
                    WorkerScoreTransitionStatus.INVALID
            );
        }

        long releaseTimeSlot = releaseTimeMillis / SLOT_MILLIS;
        long currentSlotStartMillis = redisTimeMillis()
                / SLOT_MILLIS
                * SLOT_MILLIS;
        if (releaseTimeMillis < currentSlotStartMillis) {
            return uniformResults(
                    orderedObservedScores.keySet(),
                    WorkerScoreTransitionStatus.INVALID
            );
        }
        long releaseSlotBase = absoluteScore(
                releaseTimeSlot,
                MIN_LANE_RANK,
                MIN_DIRTY
        );

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, long[]> pending = new LinkedHashMap<>();
        orderedObservedScores.forEach((workerId, observedScore) -> {
            if (observedScore == Long.MIN_VALUE) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            long observedAbsoluteScore = Math.abs(observedScore);
            if (releaseSlotBase >= observedAbsoluteScore) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
                return;
            }
            long observedLowBits = observedAbsoluteScore % SLOT_FACTOR;
            long nextAbsoluteScore = releaseSlotBase + observedLowBits;
            long nextScore = observedScore > 0
                    ? nextAbsoluteScore
                    : -nextAbsoluteScore;
            pending.put(
                    workerId,
                    new long[]{observedScore, nextScore}
            );
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            RedisAsyncCommands<String, String> async = connection().async();
            List<RedisFuture<Object>> futures = new ArrayList<>(
                    pending.size()
            );
            String key = scoreKey(homeBucketId);
            pending.forEach((workerId, scores) -> futures.add(async.eval(
                    CAS_UPDATE_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    workerId,
                    Long.toString(scores[0]),
                    Long.toString(scores[1])
            )));
            transitioned.putAll(collectScriptResults(
                    pending.keySet(),
                    futures
            ));
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        orderedObservedScores.keySet().forEach(workerId -> results.put(
                workerId,
                immediate.containsKey(workerId)
                        ? immediate.get(workerId)
                        : transitioned.get(workerId)
        ));
        return results;
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
    releaseCompletedHotScoreHolds(
            String homeBucketId,
            Map<String, Long> observedHotScores,
            long releaseTimeMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (observedHotScores == null) {
            throw new IllegalArgumentException(
                    "observedHotScores must be present"
            );
        }
        if (observedHotScores.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> orderedObservedScores =
                new LinkedHashMap<>();
        observedHotScores.forEach((workerId, observedScore) -> {
            requireNonBlank(workerId, "workerId");
            if (observedScore == null) {
                throw new IllegalArgumentException(
                        "observedHotScore must be present"
                );
            }
            orderedObservedScores.put(workerId, observedScore);
        });
        if (!validTimeMillis(releaseTimeMillis)) {
            return uniformResults(
                    orderedObservedScores.keySet(),
                    WorkerScoreTransitionStatus.INVALID
            );
        }

        long currentSlotStartMillis = redisTimeMillis()
                / SLOT_MILLIS
                * SLOT_MILLIS;
        if (releaseTimeMillis < currentSlotStartMillis) {
            return uniformResults(
                    orderedObservedScores.keySet(),
                    WorkerScoreTransitionStatus.INVALID
            );
        }
        long releaseSlotBase = absoluteScore(
                releaseTimeMillis / SLOT_MILLIS,
                MIN_LANE_RANK,
                MIN_DIRTY
        );

        LinkedHashMap<String, WorkerScoreTransitionResult> immediate =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        orderedObservedScores.forEach((workerId, observedScore) -> {
            boolean validHotScore;
            try {
                validHotScore = observedScore > 0
                        && decodeState(workerId, observedScore.doubleValue())
                                .polarity()
                        == WorkerScorePolarity.HOT_ACQUIRE;
            } catch (IllegalStateException error) {
                validHotScore = false;
            }
            if (!validHotScore || releaseSlotBase >= observedScore) {
                immediate.put(
                        workerId,
                        transition(WorkerScoreTransitionStatus.INVALID)
                );
            } else {
                pending.put(workerId, observedScore);
            }
        });

        LinkedHashMap<String, WorkerScoreTransitionResult> transitioned =
                new LinkedHashMap<>();
        if (!pending.isEmpty()) {
            RedisAsyncCommands<String, String> async = connection().async();
            List<RedisFuture<Object>> futures = new ArrayList<>(
                    pending.size()
            );
            String key = scoreKey(homeBucketId);
            pending.forEach((workerId, observedScore) -> futures.add(
                    async.eval(
                            COMPLETED_HOT_RELEASE_SCRIPT,
                            ScriptOutputType.MULTI,
                            new String[]{key},
                            workerId,
                            Long.toString(observedScore),
                            Long.toString(releaseSlotBase),
                            Long.toString(SLOT_FACTOR)
                    )
            ));
            transitioned.putAll(collectScriptResults(
                    pending.keySet(),
                    futures
            ));
        }

        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        orderedObservedScores.keySet().forEach(workerId -> results.put(
                workerId,
                immediate.containsKey(workerId)
                        ? immediate.get(workerId)
                        : transitioned.get(workerId)
        ));
        return results;
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

    private static LinkedHashMap<String, Long> boundedWorkerValues(
            Map<String, Long> values,
            String name
    ) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        if (values.size() > MAX_SERVICEABILITY_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    name + " must contain at most "
                            + MAX_SERVICEABILITY_BATCH_SIZE + " workers"
            );
        }
        LinkedHashMap<String, Long> ordered = new LinkedHashMap<>();
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

    private static long absoluteScore(
            long timeSlot,
            int laneRank,
            int dirty
    ) {
        return timeSlot * SLOT_FACTOR
                + (long) laneRank * DIRTY_FACTOR
                + dirty;
    }

    private List<WorkerScoreObservation> rangeWorkerCandidates(
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
        List<WorkerScoreObservation> observations = new ArrayList<>(
                rows.size()
        );
        for (ScoredValue<String> row : rows) {
            observations.add(new WorkerScoreObservation(
                    row.getValue(),
                    scoreToLong(row.getScore())
            ));
        }
        return List.copyOf(observations);
    }

    private static boolean validTimeMillis(long timeMillis) {
        return timeMillis >= MIN_TIME_MILLIS
                && timeMillis <= MAX_TIME_MILLIS;
    }

    private static boolean validLaneRank(int laneRank) {
        return laneRank >= MIN_LANE_RANK
                && laneRank <= MAX_LANE_RANK;
    }

    private WorkerScoreState decodeState(
            String workerId,
            double rawScore
    ) {
        long score = scoreToLong(rawScore);
        if (score == ZERO_SCORE || score == Long.MIN_VALUE) {
            throw new IllegalStateException("Worker score is invalid");
        }
        long absolute = Math.abs(score);
        long timeSlot = absolute / SLOT_FACTOR;
        long slotRemainder = absolute % SLOT_FACTOR;
        int laneRank = Math.toIntExact(
                slotRemainder / DIRTY_FACTOR
        );
        int dirty = Math.toIntExact(slotRemainder % DIRTY_FACTOR);
        if (timeSlot < MIN_TIME_SLOT
                || timeSlot > MAX_TIME_SLOT
                || laneRank < MIN_LANE_RANK
                || laneRank > MAX_LANE_RANK
                || dirty < MIN_DIRTY
                || dirty > MAX_DIRTY) {
            throw new IllegalStateException("Worker score is invalid");
        }
        return new WorkerScoreState(
                workerId,
                score,
                score > 0
                        ? WorkerScorePolarity.HOT_ACQUIRE
                        : WorkerScorePolarity.RECOVERY_RECHECK,
                timeSlot * SLOT_MILLIS,
                laneRank,
                dirty
        );
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

    private static long scoreToLong(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            long converted = number.longValue();
            if (!Double.isFinite(value)
                    || value != (double) converted) {
                throw new IllegalStateException(
                        "Worker score must be an integer"
                );
            }
            return converted;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "Worker score must be an integer",
                    error
            );
        }
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

    private static KernelOperationNotImplementedException notImplemented(
            String operation
    ) {
        return new KernelOperationNotImplementedException(
                "WorkerScoreCore",
                operation
        );
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
