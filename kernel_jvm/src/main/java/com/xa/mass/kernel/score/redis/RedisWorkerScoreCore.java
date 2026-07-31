package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.score.WorkerScoreCore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RedisWorkerScoreCore
        implements WorkerScoreCore, AutoCloseable {

    private static final String RECONCILE_HOT_ACQUIRE_SCRIPT = """
            local key = KEYS[1]
            local worker_id = ARGV[1]
            local dirty_factor = tonumber(ARGV[2])

            local stored = redis.call("ZSCORE", key, worker_id)
            if not stored then
              return {"stale"}
            end

            local stored_score = tonumber(stored)
            local abs_score = math.abs(stored_score)
            if abs_score <= 0 then
              return {"invalid", stored_score}
            end

            local stored_dirty = abs_score % dirty_factor
            if stored_dirty ~= 0 and stored_dirty ~= 1 then
              return {"invalid", stored_score}
            end
            if stored_score > 0 and stored_dirty == 1 then
              return {"noop", stored_score}
            end

            local target_score = abs_score + (1 - stored_dirty)
            redis.call("ZADD", key, target_score, worker_id)
            return {"transitioned", target_score}
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
            String workerId,
            int laneRank
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        if (laneRank < MIN_LANE_RANK || laneRank > MAX_LANE_RANK) {
            return new WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID,
                    null
            );
        }
        long timeSlot = redisTimeMillis() / SLOT_MILLIS;
        if (timeSlot < MIN_TIME_SLOT || timeSlot > MAX_TIME_SLOT) {
            return new WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID,
                    null
            );
        }
        long initialScore = timeSlot * SLOT_FACTOR
                + (long) laneRank * DIRTY_FACTOR
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
    public WorkerScoreTransitionResult reconcileWorkerHotAcquire(
            String homeBucketId,
            String workerId
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        Object raw = commands().eval(
                RECONCILE_HOT_ACQUIRE_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{scoreKey(homeBucketId)},
                workerId,
                Integer.toString(DIRTY_FACTOR)
        );
        return scriptResult(raw);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> rewriteCurrentScores(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis,
            Integer targetLaneRank
    ) {
        throw notImplemented("rewrite_current_scores");
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
    public Map<String, WorkerScoreTransitionResult>
            demoteObservedWorkerLeasesToRecovery(
                    String homeBucketId,
                    Map<String, Long> observedScores
            ) {
        throw notImplemented(
                "demote_observed_worker_leases_to_recovery"
        );
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
            long observedScore,
            int targetLaneRank
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
    public Map<String, WorkerScoreTransitionResult> releaseScoreHolds(
            String homeBucketId,
            Map<String, Long> observedScores,
            long releaseTimeMillis
    ) {
        throw notImplemented("release_score_holds");
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
