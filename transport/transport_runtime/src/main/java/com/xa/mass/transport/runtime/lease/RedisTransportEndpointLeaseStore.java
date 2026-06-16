package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseConsumerEvidence;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseMaintenance;
import com.xa.mass.transport.lease.TransportEndpointLeaseMetadata;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed endpoint lease store keyed by delivery bucket and selected worker.
 */
public final class RedisTransportEndpointLeaseStore implements TransportEndpointLeaseStore,
        TransportEndpointLeaseMaintenance,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.ENDPOINT_LEASE;
    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private static final String VERSION = "v1";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();
    private static final String CLAIM_SCRIPT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
            return 1
            """;
    private static final String REFRESH_IF_MATCH_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current ~= ARGV[2] then
              return 0
            end
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
            return 1
            """;
    private static final String REMOVE_IF_MATCH_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current ~= ARGV[2] then
              return 0
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            if redis.call('HLEN', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
            end
            if redis.call('ZCARD', KEYS[2]) == 0 then
              redis.call('DEL', KEYS[2])
            end
            return 1
            """;
    private static final String REMOVE_IF_DEADLINE_DUE_SCRIPT = """
            local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if (not score) or tonumber(score) > tonumber(ARGV[2]) then
              return 0
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            if redis.call('HLEN', KEYS[1]) == 0 then
              redis.call('DEL', KEYS[1])
            end
            if redis.call('ZCARD', KEYS[2]) == 0 then
              redis.call('DEL', KEYS[2])
            end
            return 1
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final long leaseMillis;
    private final String runtimeNodeId;
    private final boolean ownsClient;

    public RedisTransportEndpointLeaseStore(String redisUri, long leaseMillis) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, leaseMillis);
    }

    public RedisTransportEndpointLeaseStore(String redisUri, String namespacePrefix, long leaseMillis) {
        this(redisUri, namespacePrefix, leaseMillis, UUID.randomUUID().toString());
    }

    public RedisTransportEndpointLeaseStore(String redisUri,
                                            String namespacePrefix,
                                            long leaseMillis,
                                            String runtimeNodeId) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                leaseMillis,
                runtimeNodeId,
                true);
    }

    RedisTransportEndpointLeaseStore(RedisClient redisClient,
                                     String namespacePrefix,
                                     long leaseMillis,
                                     String runtimeNodeId,
                                     boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                leaseMillis,
                runtimeNodeId,
                ownsClient);
    }

    RedisTransportEndpointLeaseStore(StatefulRedisConnection<String, String> connection,
                                     String namespacePrefix,
                                     long leaseMillis,
                                     String runtimeNodeId) {
        this(null, connection, namespacePrefix, leaseMillis, runtimeNodeId, false);
    }

    private RedisTransportEndpointLeaseStore(RedisClient redisClient,
                                             StatefulRedisConnection<String, String> connection,
                                             String namespacePrefix,
                                             long leaseMillis,
                                             String runtimeNodeId,
                                             boolean ownsClient) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = requireText(namespacePrefix, "namespacePrefix");
        this.leaseMillis = leaseMillis;
        this.runtimeNodeId = requireText(runtimeNodeId, "runtimeNodeId");
        this.ownsClient = ownsClient;
    }

    @Override
    public TransportEndpointLeaseConsumerEvidence claimEndpointLease(TransportEndpointLeaseClaim claim) {
        Objects.requireNonNull(claim, "claim");
        long deadline = System.currentTimeMillis() + leaseMillis;
        TransportEndpointLeaseMetadata metadata = metadataFrom(claim);
        claim(metadata, deadline);
        return consumerEvidence(metadata, deadline);
    }

    @Override
    public Optional<TransportEndpointLeaseConsumerEvidence> refreshEndpointLease(
            TransportEndpointLeaseHeartbeat heartbeat) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        TransportEndpointLeaseMetadata current = readMetadata(heartbeat.deliveryBucketId(), heartbeat.workerId());
        if (current == null || !matches(heartbeat, current)) {
            return Optional.empty();
        }
        long deadline = System.currentTimeMillis() + leaseMillis;
        if (!refreshIfCurrent(current, deadline)) {
            return Optional.empty();
        }
        return Optional.of(consumerEvidence(current, deadline));
    }

    @Override
    public boolean releaseEndpointLease(TransportEndpointLeaseRelease release) {
        Objects.requireNonNull(release, "release");
        TransportEndpointLeaseMetadata current = readMetadata(release.deliveryBucketId(), release.workerId());
        if (current == null || !matches(release, current)) {
            return false;
        }
        return removeIfCurrent(current);
    }

    @Override
    public Optional<TransportEndpointLeaseViewRecord> currentEndpointLease(String deliveryBucketId, String workerId) {
        TransportEndpointLeaseMetadata metadata = readMetadata(deliveryBucketId, workerId);
        if (metadata == null) {
            return Optional.empty();
        }
        Double deadline = commands.zscore(deadlineKey(deliveryBucketId), requireText(workerId, "workerId"));
        if (deadline == null || deadline <= System.currentTimeMillis()) {
            removeIfCurrent(metadata);
            return Optional.empty();
        }
        return Optional.of(new TransportEndpointLeaseViewRecord(metadata));
    }

    @Override
    public int pruneExpired(String deliveryBucketId, int maxItems) {
        String normalizedBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        int limit = Math.max(0, maxItems);
        if (limit == 0) {
            return 0;
        }
        List<String> due = commands.zrangebyscore(deadlineKey(normalizedBucketId),
                Range.create(Double.NEGATIVE_INFINITY, (double) System.currentTimeMillis()));
        int pruned = 0;
        for (String workerId : due) {
            if (pruned >= limit) {
                break;
            }
            if (removeIfDeadlineDue(normalizedBucketId, workerId, System.currentTimeMillis())) {
                pruned++;
            }
        }
        return pruned;
    }

    @Override
    public long getLeaseMillis() {
        return leaseMillis;
    }

    public String getNamespacePrefix() {
        return namespacePrefix;
    }

    public String getRuntimeNodeId() {
        return runtimeNodeId;
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (connection.isOpen()) {
            connection.close();
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    private void claim(TransportEndpointLeaseMetadata metadata, long leaseExpireAtEpochMillis) {
        commands.eval(CLAIM_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{bucketWorkersKey(metadata.deliveryBucketId()), deadlineKey(metadata.deliveryBucketId())},
                metadata.workerId(),
                encodeMetadata(metadata),
                Long.toString(leaseExpireAtEpochMillis));
    }

    private boolean refreshIfCurrent(TransportEndpointLeaseMetadata metadata, long leaseExpireAtEpochMillis) {
        Long updated = commands.eval(REFRESH_IF_MATCH_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{bucketWorkersKey(metadata.deliveryBucketId()), deadlineKey(metadata.deliveryBucketId())},
                metadata.workerId(),
                encodeMetadata(metadata),
                Long.toString(leaseExpireAtEpochMillis));
        return updated != null && updated == 1L;
    }

    private boolean removeIfCurrent(TransportEndpointLeaseMetadata metadata) {
        Long removed = commands.eval(REMOVE_IF_MATCH_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{bucketWorkersKey(metadata.deliveryBucketId()), deadlineKey(metadata.deliveryBucketId())},
                metadata.workerId(),
                encodeMetadata(metadata));
        return removed != null && removed == 1L;
    }

    private boolean removeIfDeadlineDue(String deliveryBucketId, String workerId, long nowEpochMillis) {
        String normalizedBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        String normalizedWorkerId = requireText(workerId, "workerId");
        Long removed = commands.eval(REMOVE_IF_DEADLINE_DUE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{bucketWorkersKey(normalizedBucketId), deadlineKey(normalizedBucketId)},
                normalizedWorkerId,
                Long.toString(nowEpochMillis));
        return removed != null && removed == 1L;
    }

    private TransportEndpointLeaseMetadata readMetadata(String deliveryBucketId, String workerId) {
        String normalizedBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        String normalizedWorkerId = requireText(workerId, "workerId");
        return decodeMetadata(commands.hget(bucketWorkersKey(normalizedBucketId), normalizedWorkerId));
    }

    private TransportEndpointLeaseMetadata metadataFrom(TransportEndpointLeaseClaim claim) {
        return new TransportEndpointLeaseMetadata(
                claim.deliveryBucketId(),
                claim.workerId(),
                claim.endpointDriverId(),
                runtimeNodeId,
                claim.sessionHandle(),
                claim.endpointLeaseId(),
                claim.endpointAddress()
        );
    }

    private String bucketWorkersKey(String deliveryBucketId) {
        return namespacePrefix + ":bucket:" + encodeKeyToken(deliveryBucketId) + ":workers";
    }

    private String deadlineKey(String deliveryBucketId) {
        return namespacePrefix + ":bucket:" + encodeKeyToken(deliveryBucketId) + ":deadlines";
    }

    private static TransportEndpointLeaseConsumerEvidence consumerEvidence(TransportEndpointLeaseMetadata metadata,
                                                                          long leaseExpireAtEpochMillis) {
        return new TransportEndpointLeaseConsumerEvidence(
                metadata.deliveryBucketId(),
                metadata.workerId(),
                metadata.endpointDriverId(),
                metadata.endpointLeaseId(),
                leaseExpireAtEpochMillis
        );
    }

    private static boolean matches(TransportEndpointLeaseHeartbeat heartbeat,
                                   TransportEndpointLeaseMetadata metadata) {
        return heartbeat.workerId().equals(metadata.workerId())
                && heartbeat.deliveryBucketId().equals(metadata.deliveryBucketId())
                && heartbeat.endpointDriverId().equals(metadata.endpointDriverId())
                && heartbeat.endpointAddress().equals(metadata.endpointAddress())
                && heartbeat.sessionHandle().equals(metadata.sessionHandle())
                && heartbeat.endpointLeaseId().equals(metadata.endpointLeaseId());
    }

    private static boolean matches(TransportEndpointLeaseRelease release,
                                   TransportEndpointLeaseMetadata metadata) {
        return release.workerId().equals(metadata.workerId())
                && release.deliveryBucketId().equals(metadata.deliveryBucketId())
                && release.endpointDriverId().equals(metadata.endpointDriverId())
                && release.endpointAddress().equals(metadata.endpointAddress())
                && release.sessionHandle().equals(metadata.sessionHandle())
                && release.endpointLeaseId().equals(metadata.endpointLeaseId());
    }

    private static String encodeMetadata(TransportEndpointLeaseMetadata metadata) {
        return String.join("|",
                VERSION,
                encodeToken(metadata.deliveryBucketId()),
                encodeToken(metadata.workerId()),
                encodeToken(metadata.endpointDriverId()),
                encodeToken(metadata.runtimeNodeId()),
                encodeToken(metadata.sessionHandle()),
                encodeToken(metadata.endpointLeaseId()),
                encodeToken(metadata.endpointAddress())
        );
    }

    private static TransportEndpointLeaseMetadata decodeMetadata(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 8 || !VERSION.equals(parts[0])) {
            return null;
        }
        return new TransportEndpointLeaseMetadata(
                decodeToken(parts[1], "deliveryBucketId"),
                decodeToken(parts[2], "workerId"),
                decodeToken(parts[3], "endpointDriverId"),
                decodeToken(parts[4], "runtimeNodeId"),
                decodeToken(parts[5], "sessionHandle"),
                decodeToken(parts[6], "endpointLeaseId"),
                decodeToken(parts[7], "endpointAddress")
        );
    }

    private static String encodeKeyToken(String value) {
        return TOKEN_ENCODER.encodeToString(requireText(value, "key").getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(requireText(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value, String fieldName) {
        return requireText(new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8), fieldName);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
