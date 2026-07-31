package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerRuntime;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.Map;

public final class RedisWorkerRuntime
        implements WorkerRuntime, AutoCloseable {

    public static final int DEFAULT_INITIAL_LANE_RANK = 50;

    private final RedisClient redisClient;
    private final WorkerScoreCore scoreCore;
    private final String prefix;
    private final int initialLaneRank;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerRuntime(
            RedisClient redisClient,
            WorkerScoreCore scoreCore,
            String prefix
    ) {
        this(
                redisClient,
                scoreCore,
                prefix,
                DEFAULT_INITIAL_LANE_RANK
        );
    }

    public RedisWorkerRuntime(
            RedisClient redisClient,
            WorkerScoreCore scoreCore,
            String prefix,
            int initialLaneRank
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        if (scoreCore == null) {
            throw new IllegalArgumentException("scoreCore must be present");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        if (initialLaneRank < WorkerScoreCore.MIN_LANE_RANK
                || initialLaneRank > WorkerScoreCore.MAX_LANE_RANK) {
            throw new IllegalArgumentException(
                    "initialLaneRank is out of range"
            );
        }
        this.redisClient = redisClient;
        this.scoreCore = scoreCore;
        this.prefix = prefix;
        this.initialLaneRank = initialLaneRank;
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
                WorkerRedisSupport.groupsKey(prefix),
                declaration.workerGroupId()
        ) == null) {
            return result(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker group not found"
            );
        }

        String ownerKey = WorkerRedisSupport.workerIdOwnersKey(prefix);
        commands().hsetnx(
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

        WorkerDescriptor descriptor = new WorkerDescriptor(
                declaration.workerId(),
                declaration.workerGroupId(),
                declaration.endpointManagerId(),
                declaration.attributes(),
                Map.of(),
                declaration.dynamicAttributeNames()
        );
        String encoded = WorkerRedisSupport.encodeWorker(descriptor);
        if (encoded == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid descriptor json"
            );
        }

        String workersKey = WorkerRedisSupport.workersKey(
                prefix,
                declaration.workerGroupId()
        );
        if (!commands().hsetnx(
                workersKey,
                declaration.workerId(),
                encoded
        )) {
            WorkerDescriptor current = WorkerRedisSupport.decodeWorker(
                    commands().hget(
                            workersKey,
                            declaration.workerId()
                    )
            );
            if (current == null) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "stored worker descriptor is invalid"
                );
            }
            if (!sameIdentity(current, declaration)) {
                return result(
                        WorkerRuntimeStatus.CONFLICT,
                        "worker identity declaration is immutable"
                );
            }
            descriptor = new WorkerDescriptor(
                    current.workerId(),
                    current.workerGroupId(),
                    current.endpointManagerId(),
                    declaration.attributes(),
                    current.platformAttributes(),
                    current.dynamicAttributeNames()
            );
            encoded = WorkerRedisSupport.encodeWorker(descriptor);
            if (encoded == null) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "invalid descriptor json"
                );
            }
            commands().hset(
                    workersKey,
                    declaration.workerId(),
                    encoded
            );
        }

        WorkerScoreState scoreState = scoreCore.getScoreStates(
                declaration.workerGroupId(),
                java.util.List.of(declaration.workerId())
        ).get(declaration.workerId());
        if (scoreState == null) {
            WorkerScoreTransitionResult initialization =
                    scoreCore.initializeHotAcquireScore(
                            declaration.workerGroupId(),
                            declaration.workerId(),
                            initialLaneRank
                    );
            if (initialization.status()
                    == WorkerScoreTransitionStatus.TRANSITIONED) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
            }
            if (initialization.status()
                    == WorkerScoreTransitionStatus.INVALID) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "worker score initialization was rejected"
                );
            }
            if (initialization.status()
                    != WorkerScoreTransitionStatus.NOOP) {
                return result(
                        WorkerRuntimeStatus.STALE,
                        "worker score initialization could not be confirmed"
                );
            }
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

        WorkerScoreTransitionResult reconciliation =
                scoreCore.reconcileWorkerHotAcquire(
                        declaration.workerGroupId(),
                        declaration.workerId()
                );
        if (reconciliation.status()
                == WorkerScoreTransitionStatus.TRANSITIONED
                || reconciliation.status()
                == WorkerScoreTransitionStatus.NOOP) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        if (reconciliation.status()
                == WorkerScoreTransitionStatus.INVALID) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "worker HOT_ACQUIRE reconciliation was rejected"
            );
        }
        return result(
                WorkerRuntimeStatus.STALE,
                "worker HOT_ACQUIRE reconciliation could not observe score"
        );
    }

    private static boolean sameIdentity(
            WorkerDescriptor current,
            WorkerDeclaration declaration
    ) {
        return current.workerId().equals(declaration.workerId())
                && current.workerGroupId().equals(
                        declaration.workerGroupId()
                )
                && current.endpointManagerId().equals(
                        declaration.endpointManagerId()
                )
                && current.dynamicAttributeNames().equals(
                        declaration.dynamicAttributeNames()
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
