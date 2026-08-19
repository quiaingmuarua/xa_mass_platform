package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.score.WorkerScoreCore;
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

public final class RedisWorkerScoreCore
        implements WorkerScoreCore, AutoCloseable {

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

    private final RedisClient redisClient;
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerScoreCore(RedisClient redisClient, String prefix) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.redisClient = redisClient;
        this.prefix = prefix;
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
    public Map<String, Long> acquireHotAcquireCandidates(
            String homeBucketId,
            int limit
    ) {
        throw notImplemented("acquire_hot_acquire_candidates");
    }

    @Override
    public Map<String, Long> observeDueHotScores(
            String homeBucketId,
            List<String> workerIds
    ) {
        throw notImplemented("observe_due_hot_scores");
    }

    @Override
    public List<WorkerScoreObservation> acquireRecoveryRecheckCandidates(
            String homeBucketId,
            int limit
    ) {
        throw notImplemented("acquire_recovery_recheck_candidates");
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
        throw notImplemented("acquire_observed_hot_score_leases");
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            renewActiveHotScoreLeases(
                    String homeBucketId,
                    Map<String, Long> observedScores,
                    long targetTimeMillis
            ) {
        throw notImplemented("renew_active_hot_score_leases");
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
        throw notImplemented("toggle_current_polarity");
    }

    @Override
    public WorkerScoreTransitionResult exhaustRecoveryRecheck(
            String homeBucketId,
            String workerId,
            long observedScore
    ) {
        throw notImplemented("exhaust_recovery_recheck");
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            applyWorkerServiceabilityChecks(
                    String homeBucketId,
                    Map<String, WorkerServiceabilityCheck> checksByWorkerId,
                    int maxRecoveryAttempts
            ) {
        throw notImplemented("apply_worker_serviceability_checks");
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
        Long score = values.size() > 1 && values.get(1) != null
                ? scoreToLong(values.get(1))
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
        return "wr:" + prefix + ":score:" + homeBucketId;
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
