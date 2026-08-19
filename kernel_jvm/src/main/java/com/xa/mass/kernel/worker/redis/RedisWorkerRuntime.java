package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.redis.WorkerRedisSupport.WorkerMetadata;
import com.xa.mass.kernel.worker.redis.WorkerRedisSupport.WorkerPropertiesEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.Map;

public final class RedisWorkerRuntime
        implements WorkerRuntime, AutoCloseable {

    private static final int MAX_PROPERTIES_CAS_ATTEMPTS = 8;
    private static final String REPLACE_WORKER_PROPERTIES_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if not current then
                return 0
            end
            local decoded_ok, decoded = pcall(cjson.decode, current)
            if not decoded_ok
                    or type(decoded) ~= 'table'
                    or type(decoded.updatedAtMillis) ~= 'number'
                    or type(decoded.properties) ~= 'table' then
                return -2
            end
            if decoded.updatedAtMillis >= tonumber(ARGV[2]) then
                return -1
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """;

    private final RedisClient redisClient;
    private final WorkerScoreCore scoreCore;
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerRuntime(
            RedisClient redisClient,
            WorkerScoreCore scoreCore,
            String prefix
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
        this.redisClient = redisClient;
        this.scoreCore = scoreCore;
        this.prefix = prefix;
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
        String validatedProperties = WorkerRedisSupport.encodeWorkerProperties(
                new WorkerPropertiesEnvelope(
                        1L,
                        declaration.workerProperties()
                )
        );
        if (validatedProperties == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerProperties json"
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

        WorkerMetadata metadata = new WorkerMetadata(
                declaration.workerId(),
                declaration.workerGroupId(),
                declaration.endpointManagerId(),
                Map.of()
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
                prefix,
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

        String propertiesKey = WorkerRedisSupport.workerPropertiesKey(
                prefix,
                declaration.workerGroupId()
        );
        Boolean propertiesChanged = upsertProperties(
                propertiesKey,
                declaration.workerId(),
                declaration.workerProperties()
        );
        if (propertiesChanged == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker properties are invalid"
            );
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
                || propertiesChanged
                || scoreCreated) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        return new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP);
    }

    @Override
    public WorkerRuntimeResult replaceWorkerProperties(
            String workerGroupId,
            String workerId,
            long updatedAtMillis,
            Map<String, Object> properties
    ) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            return result(WorkerRuntimeStatus.INVALID, "invalid workerGroupId");
        }
        if (workerId == null || workerId.isBlank()) {
            return result(WorkerRuntimeStatus.INVALID, "invalid workerId");
        }
        if (updatedAtMillis <= 0) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "updatedAtMillis must be positive"
            );
        }
        if (properties == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerProperties"
            );
        }
        String encoded;
        try {
            encoded = WorkerRedisSupport.encodeWorkerProperties(
                    new WorkerPropertiesEnvelope(
                            updatedAtMillis,
                            properties
                    )
            );
        } catch (IllegalArgumentException error) {
            encoded = null;
        }
        if (encoded == null) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "invalid workerProperties json"
            );
        }
        WorkerMetadata metadata = WorkerRedisSupport.decodeWorkerMetadata(
                commands().hget(
                        WorkerRedisSupport.workerMetadataKey(
                                prefix,
                                workerGroupId
                        ),
                        workerId
                )
        );
        if (metadata == null) {
            return result(WorkerRuntimeStatus.NOT_FOUND, "worker not found");
        }
        if (!workerId.equals(metadata.workerId())
                || !workerGroupId.equals(metadata.workerGroupId())) {
            return result(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker identity does not match"
            );
        }
        Number outcome = commands().eval(
                REPLACE_WORKER_PROPERTIES_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{WorkerRedisSupport.workerPropertiesKey(
                        prefix,
                        workerGroupId
                )},
                workerId,
                Long.toString(updatedAtMillis),
                encoded
        );
        long code = outcome == null ? -2L : outcome.longValue();
        if (code == 1L) {
            return new WorkerRuntimeResult(WorkerRuntimeStatus.OK);
        }
        if (code == -1L) {
            return result(
                    WorkerRuntimeStatus.STALE,
                    "worker properties observation is not newer"
            );
        }
        if (code == 0L) {
            return result(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker properties not found"
            );
        }
        return result(
                WorkerRuntimeStatus.INVALID,
                "stored worker properties are invalid"
        );
    }

    private Boolean upsertProperties(
            String propertiesKey,
            String workerId,
            Map<String, Object> properties
    ) {
        for (int attempt = 0;
                attempt < MAX_PROPERTIES_CAS_ATTEMPTS;
                attempt++) {
            String observed = commands().hget(propertiesKey, workerId);
            if (observed == null) {
                String encoded = WorkerRedisSupport.encodeWorkerProperties(
                        new WorkerPropertiesEnvelope(
                                Math.max(1L, System.currentTimeMillis()),
                                properties
                        )
                );
                if (encoded == null) {
                    return null;
                }
                if (commands().hsetnx(propertiesKey, workerId, encoded)) {
                    return true;
                }
                continue;
            }
            WorkerPropertiesEnvelope current =
                    WorkerRedisSupport.decodeWorkerProperties(observed);
            if (current == null) {
                return null;
            }
            if (current.properties().equals(properties)) {
                return false;
            }
            String encoded = WorkerRedisSupport.encodeWorkerProperties(
                    new WorkerPropertiesEnvelope(
                            Math.max(
                                    System.currentTimeMillis(),
                                    current.updatedAtMillis() + 1L
                            ),
                            properties
                    )
            );
            if (encoded == null) {
                return null;
            }
            if (WorkerRedisSupport.compareAndSetHashField(
                    commands(),
                    propertiesKey,
                    workerId,
                    observed,
                    encoded
            )) {
                return true;
            }
        }
        return null;
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
