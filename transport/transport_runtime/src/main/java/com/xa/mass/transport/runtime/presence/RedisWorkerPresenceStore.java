package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis-backed shared worker presence projection.
 */
public final class RedisWorkerPresenceStore implements WorkerPresenceStore, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = "xa:mass:transport:presence";

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
        WorkerPresence previous = readStoredPresence(normalizedWorkerId);
        clearPreviousRoute(previous, normalizedWorkerId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workerId", normalizedWorkerId);
        fields.put("adapterId", normalizedAdapterId);
        fields.put("routeKey", normalizedRouteKey);
        fields.put("presenceState", WorkerPresenceState.ONLINE.name());
        fields.put("lastHeartbeatEpochMillis", Long.toString(now));
        fields.put("leaseExpireAtEpochMillis", Long.toString(now + leaseMillis));
        fields.put("transportInstanceId", transportInstanceId);
        fields.put("connectionId", nullableField(normalizedConnectionId));
        fields.put("updatedAtEpochMillis", Long.toString(now));
        fields.put("disconnectReason", "");
        commands.hset(workerKey(normalizedWorkerId), fields);
        commands.set(routeKey(normalizedAdapterId, normalizedRouteKey), normalizedWorkerId);
        commands.sadd(workersKey(), normalizedWorkerId);
        return new WorkerPresence(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                WorkerPresenceState.ONLINE,
                now,
                now + leaseMillis,
                transportInstanceId,
                normalizedConnectionId,
                now,
                null
        );
    }

    @Override
    public WorkerPresence refreshHeartbeat(String workerId,
                                           String adapterId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        return markOnline(workerId, adapterId, routeKey, connectionId, reason);
    }

    @Override
    public WorkerPresence markOffline(String workerId,
                                      String adapterId,
                                      String routeKey,
                                      String connectionId,
                                      String reason) {
        long now = System.currentTimeMillis();
        String normalizedWorkerId = normalizeRequired(workerId, "workerId");
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        WorkerPresence previous = readStoredPresence(normalizedWorkerId);
        clearPreviousRoute(previous, normalizedWorkerId);
        long lastHeartbeat = previous != null ? previous.getLastHeartbeatEpochMillis() : 0L;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workerId", normalizedWorkerId);
        fields.put("adapterId", normalizedAdapterId);
        fields.put("routeKey", normalizedRouteKey);
        fields.put("presenceState", WorkerPresenceState.OFFLINE.name());
        fields.put("lastHeartbeatEpochMillis", Long.toString(lastHeartbeat));
        fields.put("leaseExpireAtEpochMillis", Long.toString(now));
        fields.put("transportInstanceId", transportInstanceId);
        fields.put("connectionId", nullableField(normalizedConnectionId));
        fields.put("updatedAtEpochMillis", Long.toString(now));
        fields.put("disconnectReason", nullableField(normalizeNullable(reason)));
        commands.hset(workerKey(normalizedWorkerId), fields);
        commands.sadd(workersKey(), normalizedWorkerId);
        return new WorkerPresence(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                WorkerPresenceState.OFFLINE,
                lastHeartbeat,
                now,
                transportInstanceId,
                normalizedConnectionId,
                now,
                normalizeNullable(reason)
        );
    }

    @Override
    public WorkerPresence getPresence(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        WorkerPresence stored = readStoredPresence(normalizedWorkerId);
        if (stored == null) {
            return null;
        }
        return materialize(stored);
    }

    @Override
    public boolean isRouteOnline(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return false;
        }
        String workerId = commands.get(routeKey(normalizedAdapterId, normalizedRouteKey));
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        WorkerPresence presence = getPresence(workerId);
        return presence != null
                && presence.getPresenceState() == WorkerPresenceState.ONLINE
                && normalizedAdapterId.equals(presence.getAdapterId())
                && normalizedRouteKey.equals(presence.getRouteKey());
    }

    @Override
    public List<WorkerPresence> listActivePresences() {
        List<WorkerPresence> presences = new ArrayList<>();
        for (String workerId : commands.smembers(workersKey())) {
            WorkerPresence presence = getPresence(workerId);
            if (presence != null && presence.getPresenceState() == WorkerPresenceState.ONLINE) {
                presences.add(presence);
            }
        }
        return List.copyOf(presences);
    }

    @Override
    public int pruneExpired() {
        int stale = 0;
        for (String workerId : commands.smembers(workersKey())) {
            WorkerPresence presence = getPresence(workerId);
            if (presence != null && presence.getPresenceState() == WorkerPresenceState.STALE) {
                stale++;
            }
        }
        return stale;
    }

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

    private WorkerPresence materialize(WorkerPresence stored) {
        long now = System.currentTimeMillis();
        WorkerPresence effective = stored.effectiveAt(now);
        if (effective != stored) {
            persistMaterialized(effective, stored);
        }
        return effective;
    }

    private void persistMaterialized(WorkerPresence effective, WorkerPresence previous) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workerId", effective.getWorkerId());
        fields.put("adapterId", effective.getAdapterId());
        fields.put("routeKey", effective.getRouteKey());
        fields.put("presenceState", effective.getPresenceState().name());
        fields.put("lastHeartbeatEpochMillis", Long.toString(effective.getLastHeartbeatEpochMillis()));
        fields.put("leaseExpireAtEpochMillis", Long.toString(effective.getLeaseExpireAtEpochMillis()));
        fields.put("transportInstanceId", effective.getTransportInstanceId());
        fields.put("connectionId", nullableField(effective.getConnectionId()));
        fields.put("updatedAtEpochMillis", Long.toString(effective.getUpdatedAtEpochMillis()));
        fields.put("disconnectReason", nullableField(normalizeNullable(effective.getDisconnectReason())));
        commands.hset(workerKey(effective.getWorkerId()), fields);
        if (previous != null && previous.getAdapterId() != null && previous.getRouteKey() != null) {
            commands.del(routeKey(previous.getAdapterId(), previous.getRouteKey()));
        }
    }

    private void clearPreviousRoute(WorkerPresence previous, String workerId) {
        if (previous == null || previous.getAdapterId() == null || previous.getRouteKey() == null) {
            return;
        }
        String routeKey = routeKey(previous.getAdapterId(), previous.getRouteKey());
        String mappedWorker = commands.get(routeKey);
        if (workerId.equals(mappedWorker)) {
            commands.del(routeKey);
        }
    }

    private WorkerPresence readStoredPresence(String workerId) {
        Map<String, String> fields = commands.hgetall(workerKey(workerId));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return new WorkerPresence(
                workerId,
                normalizeNullable(fields.get("adapterId")),
                normalizeNullable(fields.get("routeKey")),
                WorkerPresenceState.valueOf(fields.getOrDefault("presenceState", WorkerPresenceState.OFFLINE.name())),
                parseLong(fields.get("lastHeartbeatEpochMillis")),
                parseLong(fields.get("leaseExpireAtEpochMillis")),
                normalizeNullable(fields.get("transportInstanceId")),
                normalizeNullable(fields.get("connectionId")),
                parseLong(fields.get("updatedAtEpochMillis")),
                normalizeNullable(fields.get("disconnectReason"))
        );
    }

    private String workerKey(String workerId) {
        return namespacePrefix + ":worker:" + workerId;
    }

    private String workersKey() {
        return namespacePrefix + ":workers";
    }

    private String routeKey(String adapterId, String routeKey) {
        return namespacePrefix + ":route:" + adapterId + '\u0000' + routeKey;
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

    private static String nullableField(String value) {
        return value == null ? "" : value;
    }
}
