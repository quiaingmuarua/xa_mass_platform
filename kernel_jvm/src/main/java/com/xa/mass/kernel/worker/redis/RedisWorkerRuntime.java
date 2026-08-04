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
    private static final int MAX_DESCRIPTOR_CAS_ATTEMPTS = 8;

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
    public WorkerRuntimeResult registerWorker(
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

        WorkerDescriptor descriptor = new WorkerDescriptor(
                declaration.workerId(),
                declaration.workerGroupId(),
                declaration.endpointManagerId(),
                declaration.workerProperties(),
                Map.of()
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
        boolean descriptorCreated = commands().hsetnx(
                workersKey,
                declaration.workerId(),
                encoded
        );
        if (!descriptorCreated) {
            WorkerDescriptor current = WorkerRedisSupport.decodeWorker(
                    commands().hget(workersKey, declaration.workerId())
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
                            declaration.workerId(),
                            initialLaneRank
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

        if (ownerCreated || descriptorCreated || scoreCreated) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        return new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP);
    }

    @Override
    public WorkerRuntimeResult updateWorkerProperties(
            String workerGroupId,
            String workerId,
            Map<String, Object> workerProperties
    ) {
        if (isBlank(workerGroupId)) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerGroupId"
            );
        }
        if (isBlank(workerId)) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerId"
            );
        }
        if (workerProperties == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerProperties"
            );
        }

        String workerGroupOwner = commands().hget(
                WorkerRedisSupport.workerIdOwnersKey(prefix),
                workerId
        );
        if (workerGroupOwner == null) {
            return result(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker not found"
            );
        }
        if (!workerGroupId.equals(workerGroupOwner)) {
            return result(
                    WorkerRuntimeStatus.CONFLICT,
                    "workerId is owned by another workerGroupId"
            );
        }

        String workersKey = WorkerRedisSupport.workersKey(
                prefix,
                workerGroupId
        );
        for (int attempt = 0;
                attempt < MAX_DESCRIPTOR_CAS_ATTEMPTS;
                attempt++) {
            String observed = commands().hget(workersKey, workerId);
            if (observed == null) {
                return result(
                        WorkerRuntimeStatus.NOT_FOUND,
                        "worker descriptor not found"
                );
            }
            WorkerDescriptor current = WorkerRedisSupport.decodeWorker(
                    observed
            );
            if (current == null) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "stored worker descriptor is invalid"
                );
            }
            if (!workerId.equals(current.workerId())
                    || !workerGroupId.equals(current.workerGroupId())) {
                return result(
                        WorkerRuntimeStatus.CONFLICT,
                        "stored worker identity does not match"
                );
            }
            if (current.workerProperties().equals(workerProperties)) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP);
            }

            WorkerDescriptor updated;
            try {
                updated = new WorkerDescriptor(
                        current.workerId(),
                        current.workerGroupId(),
                        current.endpointManagerId(),
                        workerProperties,
                        current.platformProperties()
                );
            } catch (RuntimeException error) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "invalid workerProperties"
                );
            }
            String encoded = WorkerRedisSupport.encodeWorker(updated);
            if (encoded == null) {
                return result(
                        WorkerRuntimeStatus.INVALID,
                        "invalid descriptor json"
                );
            }
            if (WorkerRedisSupport.compareAndSetHashField(
                    commands(),
                    workersKey,
                    workerId,
                    observed,
                    encoded
            )) {
                return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
            }
        }
        return result(
                WorkerRuntimeStatus.STALE,
                "worker descriptor changed during snapshot refresh"
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
                );
    }

    private static WorkerRuntimeResult result(
            WorkerRuntimeStatus status,
            String reason
    ) {
        return new WorkerRuntimeResult(status, reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
