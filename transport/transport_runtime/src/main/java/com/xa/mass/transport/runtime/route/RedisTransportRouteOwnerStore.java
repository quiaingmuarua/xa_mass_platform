package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerInspectionView;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed transport route-owner heartbeat store.
 *
 * <p>The runtime owner is the routeKey-sharded owner hash. WorkerId keys are
 * derived projections for SDK/operator inspection APIs only.</p>
 */
public final class RedisTransportRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView,
        TransportRouteOwnerInspectionView,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.ROUTE_OWNER;
    private static final int SHARD_COUNT = 64;
    private static final String VERSION = "v2";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final long leaseMillis;
    private final String transportInstanceId;
    private final boolean ownsClient;

    public RedisTransportRouteOwnerStore(String redisUri, long leaseMillis) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, leaseMillis);
    }

    public RedisTransportRouteOwnerStore(String redisUri, String namespacePrefix, long leaseMillis) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                leaseMillis,
                java.util.UUID.randomUUID().toString(),
                true
        );
    }

    public RedisTransportRouteOwnerStore(String redisUri,
                                    String namespacePrefix,
                                    long leaseMillis,
                                    String transportInstanceId) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                leaseMillis,
                transportInstanceId,
                true
        );
    }

    RedisTransportRouteOwnerStore(RedisClient redisClient,
                             String namespacePrefix,
                             long leaseMillis,
                             String transportInstanceId,
                             boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                leaseMillis,
                transportInstanceId,
                ownsClient
        );
    }

    RedisTransportRouteOwnerStore(StatefulRedisConnection<String, String> connection,
                             String namespacePrefix,
                             long leaseMillis,
                             String transportInstanceId) {
        this(null, connection, namespacePrefix, leaseMillis, transportInstanceId, false);
    }

    private RedisTransportRouteOwnerStore(RedisClient redisClient,
                                     StatefulRedisConnection<String, String> connection,
                                     String namespacePrefix,
                                     long leaseMillis,
                                     String transportInstanceId,
                                     boolean ownsClient) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.leaseMillis = leaseMillis;
        this.transportInstanceId = normalizeRequired(transportInstanceId, "transportInstanceId");
        this.ownsClient = ownsClient;
    }

    @Override
    public TransportRouteOwnerRecord claimRouteOwner(String workerId,
                                          String adapterId,
                                          String routeKey,
                                          String connectionId,
                                          String reason) {
        long now = System.currentTimeMillis();
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                now,
                now + leaseMillis,
                transportInstanceId,
                normalizedConnectionId != null ? normalizedConnectionId : java.util.UUID.randomUUID().toString(),
                now
        );
        persistOwner(next);
        return next;
    }

    @Override
    public TransportRouteOwnerRecord refreshHeartbeat(String workerId,
                                           String adapterId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeNullable(routeKey);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedWorkerId == null || normalizedRouteKey == null || normalizedConnectionId == null) {
            return getLatestOwnerByWorker(workerId);
        }
        TransportRouteOwnerRecord previous = readOwnerRecord(normalizedRouteKey);
        if (previous == null
                || !normalizedWorkerId.equals(previous.getWorkerId())
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getLatestOwnerByWorker(normalizedWorkerId);
        }
        long now = System.currentTimeMillis();
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                    previous.getWorkerId(),
                    previous.getAdapterId(),
                    previous.getRouteKey(),
                    now,
                    now + leaseMillis,
                    transportInstanceId,
                    previous.getConnectionId(),
                    now
        );
        persistOwner(next);
        return next;
    }

    @Override
    public TransportRouteOwnerRecord releaseRouteOwner(String workerId,
                                            String adapterId,
                                            String routeKey,
                                            String connectionId,
                                            String reason) {
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        TransportRouteOwnerRecord previous = readOwnerRecord(normalizedRouteKey);
        if (previous == null
                || normalizedConnectionId == null
                || !normalizedWorkerId.equals(previous.getWorkerId())
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getLatestOwnerByWorker(normalizedWorkerId);
        }
        removeOwner(previous);
        return previous;
    }

    @Override
    public TransportRouteOwnerRecord getLatestOwnerByWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        String routeKey = commands.get(workerRouteKey(normalizedWorkerId));
        if (routeKey == null || routeKey.isBlank()) {
            return null;
        }
        TransportRouteOwnerRecord owner = readOwnerRecord(routeKey);
        if (owner == null) {
            commands.del(workerRouteKey(normalizedWorkerId));
            return null;
        }
        if (!normalizedWorkerId.equals(owner.getWorkerId())) {
            clearWorkerRouteProjection(normalizedWorkerId, routeKey);
            return null;
        }
        return owner;
    }

    @Override
    public boolean hasActiveRouteOwner(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        if (normalizedAdapterId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        return currentOwner(routeKey)
                .filter(owner -> normalizedAdapterId.equals(owner.adapterId()))
                .filter(owner -> owner.isActive(now))
                .isPresent();
    }

    @Override
    public Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey) {
        TransportRouteOwnerRecord owner = readOwnerRecord(normalizeNullable(routeKey));
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of(WorkerDispatchRouteOwner.fromRecord(owner));
    }

    @Override
    public List<TransportRouteOwnerRecord> listActiveRouteOwners() {
        List<TransportRouteOwnerRecord> owners = new ArrayList<>();
        for (String routeKey : ownerRouteKeys()) {
            TransportRouteOwnerRecord owner = readOwnerRecord(routeKey);
            if (owner != null && owner.isLeaseActive(System.currentTimeMillis())) {
                owners.add(owner);
            }
        }
        return List.copyOf(owners);
    }

    @Override
    public int pruneExpired() {
        int stale = 0;
        long now = System.currentTimeMillis();
        for (int shard = 0; shard < SHARD_COUNT; shard++) {
            String deadlineKey = deadlineKey(shard);
            List<String> due = commands.zrangebyscore(
                    deadlineKey,
                    Range.create(Double.NEGATIVE_INFINITY, (double) now)
            );
            for (String routeKey : due) {
                TransportRouteOwnerRecord owner = readOwnerRecord(routeKey);
                if (owner != null && !owner.isLeaseActive(now)) {
                    removeOwner(owner);
                    stale++;
                } else if (owner == null) {
                    commands.zrem(deadlineKey, routeKey);
                }
            }
        }
        return stale;
    }

    @Override
    public long getLeaseMillis() {
        return leaseMillis;
    }

    public String getTransportInstanceId() {
        return transportInstanceId;
    }

    public String getNamespacePrefix() {
        return namespacePrefix;
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

    private TransportRouteOwnerRecord readOwnerRecord(String routeKey) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedRouteKey == null) {
            return null;
        }
        String encoded = commands.hget(ownerKey(normalizedRouteKey), normalizedRouteKey);
        return decodeOwnerRecord(encoded);
    }

    private void persistOwner(TransportRouteOwnerRecord owner) {
        commands.hset(ownerKey(owner.getRouteKey()), owner.getRouteKey(), encodeOwnerRecord(owner));
        commands.set(workerRouteKey(owner.getWorkerId()), owner.getRouteKey());
        commands.zadd(deadlineKey(owner.getRouteKey()), owner.getLeaseExpireAtEpochMillis(), owner.getRouteKey());
    }

    private void removeOwner(TransportRouteOwnerRecord owner) {
        commands.hdel(ownerKey(owner.getRouteKey()), owner.getRouteKey());
        commands.zrem(deadlineKey(owner.getRouteKey()), owner.getRouteKey());
        clearWorkerRouteProjection(owner.getWorkerId(), owner.getRouteKey());
    }

    private void clearWorkerRouteProjection(String workerId, String routeKey) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedWorkerId == null || normalizedRouteKey == null) {
            return;
        }
        String key = workerRouteKey(normalizedWorkerId);
        if (normalizedRouteKey.equals(commands.get(key))) {
            commands.del(key);
        }
    }

    private Set<String> ownerRouteKeys() {
        Set<String> routeKeys = new LinkedHashSet<>();
        for (int shard = 0; shard < SHARD_COUNT; shard++) {
            routeKeys.addAll(commands.hkeys(ownerKey(shard)));
        }
        return routeKeys;
    }

    private String ownerKey(String routeKey) {
        return ownerKey(shard(routeKey));
    }

    private String ownerKey(int shard) {
        return namespacePrefix + ":owner:" + shard;
    }

    private String deadlineKey(String routeKey) {
        return deadlineKey(shard(routeKey));
    }

    private String deadlineKey(int shard) {
        return namespacePrefix + ":deadline:" + shard;
    }

    private String workerRouteKey(String workerId) {
        return namespacePrefix + ":worker-route:" + workerId;
    }

    private static int shard(String routeKey) {
        return Math.floorMod(normalizeRequired(routeKey, "routeKey").hashCode(), SHARD_COUNT);
    }

    private static String encodeOwnerRecord(TransportRouteOwnerRecord owner) {
        return String.join("|",
                VERSION,
                encodeToken(owner.getWorkerId()),
                encodeToken(owner.getAdapterId()),
                encodeToken(owner.getRouteKey()),
                Long.toString(owner.getLastHeartbeatEpochMillis()),
                Long.toString(owner.getLeaseExpireAtEpochMillis()),
                encodeToken(owner.getTransportNodeId()),
                encodeNullableToken(owner.getConnectionId()),
                Long.toString(owner.getUpdatedAtEpochMillis())
        );
    }

    private static TransportRouteOwnerRecord decodeOwnerRecord(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 9 || !VERSION.equals(parts[0])) {
            return null;
        }
        return new TransportRouteOwnerRecord(
                decodeToken(parts[1], "workerId"),
                decodeToken(parts[2], "adapterId"),
                decodeToken(parts[3], "routeKey"),
                parseLong(parts[4]),
                parseLong(parts[5]),
                decodeToken(parts[6], "transportInstanceId"),
                decodeNullableToken(parts[7]),
                parseLong(parts[8])
        );
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(normalizeRequired(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeNullableToken(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? "" : TOKEN_ENCODER.encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value, String fieldName) {
        return normalizeRequired(new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8), fieldName);
    }

    private static String decodeNullableToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeNullable(new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8));
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
