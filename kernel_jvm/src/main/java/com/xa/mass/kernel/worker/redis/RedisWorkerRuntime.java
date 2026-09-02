package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.redis.WorkerRedisSupport.WorkerMetadata;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;

public final class RedisWorkerRuntime
        implements WorkerRuntime, AutoCloseable {

    private final RedisClient redisClient;
    private final WorkerScoreCore scoreCore;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerRuntime(
            RedisClient redisClient,
            WorkerScoreCore scoreCore,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        if (scoreCore == null) {
            throw new IllegalArgumentException("scoreCore must be present");
        }
        this.redisClient = redisClient;
        this.scoreCore = scoreCore;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public WorkerRuntimeResult upsertWorker(
            WorkerDeclaration declaration
    ) {
        if (declaration == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid worker declaration"
            );
        }
        if (commands().hget(
                WorkerRedisSupport.groupsKey(keyspace),
                declaration.workerGroupId()
        ) == null) {
            return result(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker group not found"
            );
        }

        String ownerKey = WorkerRedisSupport.workerIdOwnersKey(keyspace);
        boolean ownerCreated = commands().hsetnx(
                ownerKey,
                declaration.workerId(),
                declaration.workerGroupId()
        );
        String workerGroupOwner = commands().hget(
                ownerKey,
                declaration.workerId()
        );
        if (!declaration.workerGroupId().equals(workerGroupOwner)) {
            return result(
                    WorkerRuntimeStatus.CONFLICT,
                    "workerId is already owned by another workerGroupId"
            );
        }

        WorkerMetadata metadata = new WorkerMetadata(
                declaration.workerId(),
                declaration.workerGroupId(),
                declaration.endpointManagerId()
        );
        String encodedMetadata = WorkerRedisSupport.encodeWorkerMetadata(
                metadata
        );
        if (encodedMetadata == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid worker metadata json"
            );
        }

        String metadataKey = WorkerRedisSupport.workerMetadataKey(
                keyspace,
                declaration.workerGroupId()
        );
        boolean metadataCreated = commands().hsetnx(
                metadataKey,
                declaration.workerId(),
                encodedMetadata
        );
        if (!metadataCreated) {
            WorkerMetadata current = WorkerRedisSupport.decodeWorkerMetadata(
                    commands().hget(metadataKey, declaration.workerId())
            );
            if (current == null) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "stored worker metadata is invalid"
                );
            }
            if (!sameIdentity(current, declaration)) {
                return result(
                        WorkerRuntimeStatus.CONFLICT,
                        "worker identity declaration is immutable"
                );
            }
        }

        WorkerScoreState scoreState = scoreCore.getScoreStates(
                declaration.workerGroupId(),
                java.util.List.of(declaration.workerId())
        ).get(declaration.workerId());
        boolean scoreCreated = false;
        if (scoreState == null) {
            WorkerScoreTransitionResult initialization =
                    scoreCore.initializeHotAcquireScore(
                            declaration.workerGroupId(),
                            declaration.workerId()
                    );
            if (initialization.status()
                    == WorkerScoreTransitionStatus.TRANSITIONED) {
                scoreCreated = true;
            } else if (initialization.status()
                    == WorkerScoreTransitionStatus.INVALID) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "worker score initialization was rejected"
                );
            } else if (initialization.status()
                    != WorkerScoreTransitionStatus.NOOP) {
                return result(
                        WorkerRuntimeStatus.STALE,
                        "worker score initialization could not be confirmed"
                );
            }
            if (!scoreCreated) {
                scoreState = scoreCore.getScoreStates(
                        declaration.workerGroupId(),
                        java.util.List.of(declaration.workerId())
                ).get(declaration.workerId());
                if (scoreState == null) {
                    return result(
                            WorkerRuntimeStatus.STALE,
                            "worker score initialization could not be observed"
                    );
                }
            }
        }

        if (ownerCreated
                || metadataCreated
                || scoreCreated) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        return new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP);
    }

    private static boolean sameIdentity(
            WorkerMetadata current,
            WorkerDeclaration declaration
    ) {
        return current.workerId().equals(declaration.workerId())
                && current.workerGroupId().equals(
                        declaration.workerGroupId()
                )
                && current.endpointManagerId().equals(
                        declaration.endpointManagerId()
                );
    }

    private static WorkerRuntimeResult result(
            WorkerRuntimeStatus status,
            String reason
    ) {
        return new WorkerRuntimeResult(status, reason);
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
}
