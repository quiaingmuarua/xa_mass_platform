package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionCommand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionResult;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionRules;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionStatus;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis-backed score-band worker slot state machine.
 */
public final class RedisWorkerScoreBandSlotRuntime implements WorkerScoreBandSlotRuntime, AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_WATCH_RETRIES = 32;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisWorkerScoreBandSlotKeyspace keyspace;
    private final boolean ownsClient;

    public RedisWorkerScoreBandSlotRuntime(String redisUri, String namespace) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                new RedisWorkerScoreBandSlotKeyspace(namespace),
                true);
    }

    public RedisWorkerScoreBandSlotRuntime(RedisClient redisClient,
                                           RedisWorkerScoreBandSlotKeyspace keyspace,
                                           boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                keyspace,
                ownsClient);
    }

    public RedisWorkerScoreBandSlotRuntime(StatefulRedisConnection<String, String> connection,
                                           RedisWorkerScoreBandSlotKeyspace keyspace) {
        this(null, connection, keyspace, false);
    }

    private RedisWorkerScoreBandSlotRuntime(RedisClient redisClient,
                                            StatefulRedisConnection<String, String> connection,
                                            RedisWorkerScoreBandSlotKeyspace keyspace,
                                            boolean ownsClient) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.keyspace = keyspace != null ? keyspace : new RedisWorkerScoreBandSlotKeyspace();
        this.ownsClient = ownsClient;
    }

    public RedisWorkerScoreBandSlotKeyspace keyspace() {
        return keyspace;
    }

    @Override
    public synchronized void upsert(WorkerScoreBandSlotMetadata metadata,
                                    long initialScore,
                                    String reasonCode,
                                    long observedAtMillis) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        String homeBucketId = metadata.homeBucketId();
        commands.hset(keyspace.metadataHash(homeBucketId), metadata.workerId(), GSON.toJson(metadata));
        commands.zadd(keyspace.scoreZset(homeBucketId), initialScore, metadata.workerId());
    }

    @Override
    public synchronized Optional<WorkerScoreBandSlot> slot(String homeBucketId, String workerId) {
        return readSlot(homeBucketId, workerId);
    }

    @Override
    public synchronized List<WorkerScoreBandSlot> acquire(WorkerScoreBandAcquireRequest request) {
        if (request == null || request.maxCount() <= 0 || request.homeBucketIds().isEmpty()) {
            return List.of();
        }
        if (request.nowMillis() < WorkerScoreBand.TIME_SCORE_FLOOR) {
            return List.of();
        }
        if (request.targetWorkerId() != null) {
            return acquireTarget(request);
        }
        ArrayList<WorkerScoreBandSlot> candidates = new ArrayList<>();
        for (String homeBucketId : request.homeBucketIds()) {
            List<String> workerIds = commands.zrangebyscore(
                    keyspace.scoreZset(homeBucketId),
                    Range.create((double) WorkerScoreBand.TIME_SCORE_FLOOR, (double) request.nowMillis()),
                    Limit.create(0, request.maxCount())
            );
            for (String workerId : workerIds) {
                readSlot(homeBucketId, workerId).ifPresent(candidates::add);
            }
        }
        candidates.sort(Comparator
                .comparingLong(WorkerScoreBandSlot::score)
                .thenComparing(WorkerScoreBandSlot::workerId));
        if (candidates.size() <= request.maxCount()) {
            return List.copyOf(candidates);
        }
        return List.copyOf(candidates.subList(0, request.maxCount()));
    }

    @Override
    public synchronized WorkerScoreBandTransitionResult transition(WorkerScoreBandTransitionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String scoreKey = keyspace.scoreZset(command.homeBucketId());
        String metadataKey = keyspace.metadataHash(command.homeBucketId());
        for (int attempt = 0; attempt < DEFAULT_WATCH_RETRIES; attempt++) {
            commands.watch(scoreKey, metadataKey);
            Optional<WorkerScoreBandSlot> current = readSlot(command.homeBucketId(), command.workerId());
            if (current.isEmpty()) {
                commands.unwatch();
                return WorkerScoreBandTransitionResult.rejected(
                        WorkerScoreBandTransitionStatus.MISSING_SLOT,
                        null,
                        "slot not found"
                );
            }
            WorkerScoreBandSlot before = current.get();
            WorkerScoreBandTransitionStatus status =
                    WorkerScoreBandTransitionRules.validate(before.score(), command);
            if (status != WorkerScoreBandTransitionStatus.ACCEPTED) {
                commands.unwatch();
                return WorkerScoreBandTransitionResult.rejected(status, before, status.name());
            }
            if (!WorkerScoreBandTransitionRules.writesScore(command)) {
                commands.unwatch();
                return WorkerScoreBandTransitionResult.accepted(before, before);
            }
            commands.multi();
            commands.zadd(scoreKey, command.targetScore(), command.workerId());
            TransactionResult result = commands.exec();
            if (result == null) {
                continue;
            }
            WorkerScoreBandSlot after = new WorkerScoreBandSlot(before.metadata(), command.targetScore());
            return WorkerScoreBandTransitionResult.accepted(before, after);
        }
        throw new IllegalStateException("Could not update score-band slot after concurrent modifications");
    }

    @Override
    public synchronized void remove(String homeBucketId, String workerId, String reasonCode, long observedAtMillis) {
        if (homeBucketId == null || homeBucketId.isBlank() || workerId == null || workerId.isBlank()) {
            return;
        }
        String normalizedHomeBucketId = homeBucketId.trim();
        String normalizedWorkerId = workerId.trim();
        commands.hdel(keyspace.metadataHash(normalizedHomeBucketId), normalizedWorkerId);
        commands.zrem(keyspace.scoreZset(normalizedHomeBucketId), normalizedWorkerId);
    }

    @Override
    public synchronized void close() {
        if (connection.isOpen()) {
            connection.close();
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    private Optional<WorkerScoreBandSlot> readSlot(String homeBucketId, String workerId) {
        if (homeBucketId == null || homeBucketId.isBlank() || workerId == null || workerId.isBlank()) {
            return Optional.empty();
        }
        String normalizedHomeBucketId = homeBucketId.trim();
        String normalizedWorkerId = workerId.trim();
        String metadataJson = commands.hget(keyspace.metadataHash(normalizedHomeBucketId), normalizedWorkerId);
        Double score = commands.zscore(keyspace.scoreZset(normalizedHomeBucketId), normalizedWorkerId);
        if (metadataJson == null || score == null) {
            return Optional.empty();
        }
        WorkerScoreBandSlotMetadata metadata = GSON.fromJson(metadataJson, WorkerScoreBandSlotMetadata.class);
        return Optional.of(new WorkerScoreBandSlot(metadata, score.longValue()));
    }

    private List<WorkerScoreBandSlot> acquireTarget(WorkerScoreBandAcquireRequest request) {
        for (String homeBucketId : request.homeBucketIds()) {
            Optional<WorkerScoreBandSlot> candidate = readSlot(homeBucketId, request.targetWorkerId());
            if (candidate.isPresent()
                    && WorkerScoreBand.isAcquireVisible(candidate.get().score(), request.nowMillis())) {
                return List.of(candidate.get());
            }
        }
        return List.of();
    }
}
