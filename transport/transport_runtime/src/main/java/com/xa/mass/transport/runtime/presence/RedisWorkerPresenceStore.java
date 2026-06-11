package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerDispatchRouteOwner;
import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;
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
 * Redis-backed transport presence owner store.
 *
 * <p>The runtime owner is the routeKey-sharded owner hash. WorkerId keys are
 * derived compatibility projections for inspection APIs only.</p>
 */
public final class RedisWorkerPresenceStore implements WorkerPresenceStore, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = "xa:mass:transport:presence";
    private static final int SHARD_COUNT = 64;
    private static final String VERSION = "v1";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final long leaseMillis;
    private final String transportInstanceId;
    private final boolean ownsClient;

    public RedisWorkerPresenceStore(String redisUri, long leaseMillis) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, leaseMillis);
    }

    public RedisWorkerPresenceStore(String redisUri, String namespacePrefix, long leaseMillis) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                leaseMillis,
                java.util.UUID.randomUUID().toString(),
                true
        );
    }

    public RedisWorkerPresenceStore(String redisUri,
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

    RedisWorkerPresenceStore(RedisClient redisClient,
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

    RedisWorkerPresenceStore(StatefulRedisConnection<String, String> connection,
                             String namespacePrefix,
                             long leaseMillis,
                             String transportInstanceId) {
        this(null, connection, namespacePrefix, leaseMillis, transportInstanceId, false);
    }

    private RedisWorkerPresenceStore(RedisClient redisClient,
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
    public WorkerPresence markOnline(String workerId,
                                     String adapterId,
                                     String routeKey,
                                     String connectionId,
                                     String reason) {
        long now = System.currentTimeMillis();
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        WorkerPresence next = new WorkerPresence(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                WorkerPresenceState.ONLINE,
                now,
                now + leaseMillis,
                transportInstanceId,
                normalizedConnectionId != null ? normalizedConnectionId : java.util.UUID.randomUUID().toString(),
                now,
                null
        );
        persistOwner(next);
        return next;
    }

    @Override
    public WorkerPresence refreshHeartbeat(String workerId,
                                           String adapterId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeNullable(routeKey);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedWorkerId == null || normalizedRouteKey == null || normalizedConnectionId == null) {
            return getPresence(workerId);
        }
        WorkerPresence previous = readOwnerPresence(normalizedRouteKey);
        if (previous == null
                || !normalizedWorkerId.equals(previous.getWorkerId())
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getPresence(normalizedWorkerId);
        }
        long now = System.currentTimeMillis();
        WorkerPresence next = new WorkerPresence(
                previous.getWorkerId(),
                previous.getAdapterId(),
                previous.getRouteKey(),
                WorkerPresenceState.ONLINE,
                now,
                now + leaseMillis,
                transportInstanceId,
                previous.getConnectionId(),
                now,
                null
        );
        persistOwner(next);
        return next;
    }

    @Override
    public WorkerPresence markOffline(String workerId,
                                      String adapterId,
                                      String routeKey,
                                      String connectionId,
                                      String reason) {
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        WorkerPresence previous = readOwnerPresence(normalizedRouteKey);
        if (previous == null
                || normalizedConnectionId == null
                || !normalizedWorkerId.equals(previous.getWorkerId())
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getPresence(normalizedWorkerId);
        }
        long now = System.currentTimeMillis();
        WorkerPresence next = new WorkerPresence(
                previous.getWorkerId(),
                previous.getAdapterId(),
                previous.getRouteKey(),
                WorkerPresenceState.OFFLINE,
                previous.getLastHeartbeatEpochMillis(),
                now,
                transportInstanceId,
                previous.getConnectionId(),
                now,
                normalizeNullable(reason)
        );
        persistOwner(next);
        return next;
    }

    @Override
    public WorkerPresence getPresence(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        String routeKey = commands.get(workerRouteKey(normalizedWorkerId));
        if (routeKey == null || routeKey.isBlank()) {
            return null;
        }
        WorkerPresence presence = readOwnerPresence(routeKey);
        if (presence == null) {
            commands.del(workerRouteKey(normalizedWorkerId));
            return null;
        }
        if (!normalizedWorkerId.equals(presence.getWorkerId())) {
            clearWorkerRouteProjection(normalizedWorkerId, routeKey);
            return null;
        }
        return presence;
    }

    @Override
    public boolean isRouteOnline(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        if (normalizedAdapterId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        return currentOwner(routeKey)
                .filter(owner -> normalizedAdapterId.equals(owner.adapterId()))
                .filter(owner -> owner.isOnline(now))
                .isPresent();
    }

    @Override
    public Optional<WorkerDispatchRouteOwner> currentOwner(String routeKey) {
        WorkerPresence presence = readOwnerPresence(normalizeNullable(routeKey));
        if (presence == null) {
            return Optional.empty();
        }
        return Optional.of(WorkerDispatchRouteOwner.fromPresence(presence));
    }

    @Override
    public List<WorkerPresence> listActivePresences() {
        List<WorkerPresence> presences = new ArrayList<>();
        for (String routeKey : ownerRouteKeys()) {
            WorkerPresence presence = readOwnerPresence(routeKey);
            if (presence != null && presence.getPresenceState() == WorkerPresenceState.ONLINE) {
                presences.add(presence);
            }
        }
        return List.copyOf(presences);
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
                WorkerPresence presence = readOwnerPresence(routeKey);
                if (presence != null && presence.getPresenceState() == WorkerPresenceState.STALE) {
                    removeOwner(presence);
                    stale++;
                } else if (presence == null || presence.getPresenceState() != WorkerPresenceState.ONLINE) {
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

    private WorkerPresence readOwnerPresence(String routeKey) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedRouteKey == null) {
            return null;
        }
        String encoded = commands.hget(ownerKey(normalizedRouteKey), normalizedRouteKey);
        WorkerPresence stored = decodePresence(encoded);
        return stored != null ? stored.effectiveAt(System.currentTimeMillis()) : null;
    }

    private void persistOwner(WorkerPresence presence) {
        commands.hset(ownerKey(presence.getRouteKey()), presence.getRouteKey(), encodePresence(presence));
        commands.set(workerRouteKey(presence.getWorkerId()), presence.getRouteKey());
        if (presence.getPresenceState() == WorkerPresenceState.ONLINE
                || presence.getPresenceState() == WorkerPresenceState.STALE) {
            commands.zadd(deadlineKey(presence.getRouteKey()), presence.getLeaseExpireAtEpochMillis(), presence.getRouteKey());
        } else {
            commands.zrem(deadlineKey(presence.getRouteKey()), presence.getRouteKey());
        }
    }

    private void removeOwner(WorkerPresence presence) {
        commands.hdel(ownerKey(presence.getRouteKey()), presence.getRouteKey());
        commands.zrem(deadlineKey(presence.getRouteKey()), presence.getRouteKey());
        clearWorkerRouteProjection(presence.getWorkerId(), presence.getRouteKey());
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

    private static String encodePresence(WorkerPresence presence) {
        return String.join("|",
                VERSION,
                encodeToken(presence.getWorkerId()),
                encodeToken(presence.getAdapterId()),
                encodeToken(presence.getRouteKey()),
                presence.getPresenceState().name(),
                Long.toString(presence.getLastHeartbeatEpochMillis()),
                Long.toString(presence.getLeaseExpireAtEpochMillis()),
                encodeToken(presence.getTransportNodeId()),
                encodeNullableToken(presence.getConnectionId()),
                Long.toString(presence.getUpdatedAtEpochMillis()),
                encodeNullableToken(presence.getDisconnectReason())
        );
    }

    private static WorkerPresence decodePresence(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 11 || !VERSION.equals(parts[0])) {
            return null;
        }
        return new WorkerPresence(
                decodeToken(parts[1], "workerId"),
                decodeToken(parts[2], "adapterId"),
                decodeToken(parts[3], "routeKey"),
                WorkerPresenceState.valueOf(parts[4]),
                parseLong(parts[5]),
                parseLong(parts[6]),
                decodeToken(parts[7], "transportInstanceId"),
                decodeNullableToken(parts[8]),
                parseLong(parts[9]),
                decodeNullableToken(parts[10])
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
