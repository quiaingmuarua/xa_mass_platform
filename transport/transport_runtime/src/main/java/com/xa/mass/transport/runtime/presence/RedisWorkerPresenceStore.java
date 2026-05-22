package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
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
    private static final String MARK_ONLINE_SCRIPT = """
            local workerKey = KEYS[1]
            local workersKey = KEYS[2]
            local newRouteKey = KEYS[3]
            local routePrefix = ARGV[11]
            local prevAdapter = redis.call('HGET', workerKey, 'adapterId')
            local prevRoute = redis.call('HGET', workerKey, 'routeKey')
            local prevWorkerId = redis.call('HGET', workerKey, 'workerId')
            if prevAdapter and prevRoute and prevWorkerId == ARGV[1] then
              redis.call('DEL', routePrefix .. prevAdapter .. string.char(0) .. prevRoute)
            end
            redis.call('HSET', workerKey,
              'workerId', ARGV[1],
              'adapterId', ARGV[2],
              'routeKey', ARGV[3],
              'presenceState', ARGV[4],
              'lastHeartbeatEpochMillis', ARGV[5],
              'leaseExpireAtEpochMillis', ARGV[6],
              'transportInstanceId', ARGV[7],
              'connectionId', ARGV[8],
              'updatedAtEpochMillis', ARGV[9],
              'disconnectReason', ARGV[10]
            )
            redis.call('SET', newRouteKey, ARGV[1])
            redis.call('SADD', workersKey, ARGV[1])
            return 1
            """;
    private static final String REFRESH_HEARTBEAT_SCRIPT = """
            local workerKey = KEYS[1]
            local workersKey = KEYS[2]
            local currentConnectionId = redis.call('HGET', workerKey, 'connectionId')
            if not currentConnectionId or currentConnectionId ~= ARGV[1] then
              return 0
            end
            local adapterId = redis.call('HGET', workerKey, 'adapterId')
            local routeValue = redis.call('HGET', workerKey, 'routeKey')
            if not adapterId or not routeValue then
              return 0
            end
            redis.call('HSET', workerKey,
              'presenceState', ARGV[2],
              'lastHeartbeatEpochMillis', ARGV[3],
              'leaseExpireAtEpochMillis', ARGV[4],
              'transportInstanceId', ARGV[5],
              'updatedAtEpochMillis', ARGV[6],
              'disconnectReason', ARGV[7]
            )
            redis.call('SET', KEYS[3], ARGV[8])
            redis.call('SADD', workersKey, ARGV[8])
            return 1
            """;
    private static final String MARK_OFFLINE_SCRIPT = """
            local workerKey = KEYS[1]
            local currentConnectionId = redis.call('HGET', workerKey, 'connectionId')
            if not currentConnectionId or currentConnectionId ~= ARGV[1] then
              return 0
            end
            local adapterId = redis.call('HGET', workerKey, 'adapterId')
            local routeValue = redis.call('HGET', workerKey, 'routeKey')
            local workerId = redis.call('HGET', workerKey, 'workerId')
            local lastHeartbeat = redis.call('HGET', workerKey, 'lastHeartbeatEpochMillis')
            if adapterId and routeValue and workerId then
              local routeKey = ARGV[7] .. adapterId .. string.char(0) .. routeValue
              local mappedWorkerId = redis.call('GET', routeKey)
              if mappedWorkerId == workerId then
                redis.call('DEL', routeKey)
              end
            end
            redis.call('HSET', workerKey,
              'presenceState', ARGV[2],
              'leaseExpireAtEpochMillis', ARGV[3],
              'transportInstanceId', ARGV[4],
              'updatedAtEpochMillis', ARGV[5],
              'disconnectReason', ARGV[6],
              'lastHeartbeatEpochMillis', lastHeartbeat or '0'
            )
            return 1
            """;

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
        String nextConnectionId = normalizedConnectionId != null ? normalizedConnectionId : java.util.UUID.randomUUID().toString();
        WorkerPresence next = new WorkerPresence(
                normalizedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey,
                WorkerPresenceState.ONLINE,
                now,
                now + leaseMillis,
                transportInstanceId,
                nextConnectionId,
                now,
                null
        );
        persistPresence(workerKey(normalizedWorkerId), next);
        persistPresence(routePresenceKey(normalizedAdapterId, normalizedRouteKey), next);
        commands.set(routeKey(normalizedAdapterId, normalizedRouteKey), normalizedWorkerId);
        commands.sadd(workersKey(), normalizedWorkerId);
        commands.sadd(workerRoutesKey(normalizedWorkerId), routeIdentity(normalizedAdapterId, normalizedRouteKey));
        commands.sadd(routesKey(), routeIdentity(normalizedAdapterId, normalizedRouteKey));
        return next;
    }

    @Override
    public WorkerPresence refreshHeartbeat(String workerId,
                                           String adapterId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedWorkerId == null || normalizedConnectionId == null) {
            return getPresence(workerId);
        }
        WorkerPresence previous = readRoutePresence(normalizedWorkerId, adapterId, routeKey);
        if (previous == null || !normalizedConnectionId.equals(previous.getConnectionId())) {
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
        persistPresence(routePresenceKey(previous.getAdapterId(), previous.getRouteKey()), next);
        persistPresence(workerKey(normalizedWorkerId), next);
        commands.set(routeKey(previous.getAdapterId(), previous.getRouteKey()), normalizedWorkerId);
        commands.sadd(workersKey(), normalizedWorkerId);
        commands.sadd(workerRoutesKey(normalizedWorkerId), routeIdentity(previous.getAdapterId(), previous.getRouteKey()));
        commands.sadd(routesKey(), routeIdentity(previous.getAdapterId(), previous.getRouteKey()));
        return next;
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
        WorkerPresence previous = readRoutePresence(normalizedWorkerId, normalizedAdapterId, normalizedRouteKey);
        if (previous == null || normalizedConnectionId == null || !normalizedConnectionId.equals(previous.getConnectionId())) {
            return getPresence(normalizedWorkerId);
        }
        long lastHeartbeat = previous != null ? previous.getLastHeartbeatEpochMillis() : 0L;
        WorkerPresence next = new WorkerPresence(
                normalizedWorkerId,
                previous.getAdapterId() != null ? previous.getAdapterId() : normalizedAdapterId,
                previous.getRouteKey() != null ? previous.getRouteKey() : normalizedRouteKey,
                WorkerPresenceState.OFFLINE,
                lastHeartbeat,
                now,
                transportInstanceId,
                previous.getConnectionId(),
                now,
                normalizeNullable(reason)
        );
        persistPresence(routePresenceKey(normalizedAdapterId, normalizedRouteKey), next);
        persistPresence(workerKey(normalizedWorkerId), next);
        String routeStorageKey = routeKey(normalizedAdapterId, normalizedRouteKey);
        String mappedWorkerId = commands.get(routeStorageKey);
        if (normalizedWorkerId.equals(mappedWorkerId)) {
            commands.del(routeStorageKey);
        }
        return next;
    }

    @Override
    public WorkerPresence getPresence(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        WorkerPresence latestProjection = readStoredPresence(workerKey(normalizedWorkerId), normalizedWorkerId);
        latestProjection = latestProjection != null ? materialize(latestProjection) : null;
        if (latestProjection != null
                && normalizedWorkerId.equals(latestProjection.getWorkerId())
                && latestProjection.getPresenceState() == WorkerPresenceState.ONLINE
                && latestProjection.getLeaseExpireAtEpochMillis() > now) {
            WorkerPresence routeProjection = readRoutePresence(
                    normalizedWorkerId,
                    latestProjection.getAdapterId(),
                    latestProjection.getRouteKey()
            );
            if (routeProjection != null
                    && routeProjection.getPresenceState() == WorkerPresenceState.ONLINE
                    && Objects.equals(routeProjection.getConnectionId(), latestProjection.getConnectionId())) {
                return latestProjection;
            }
        }
        List<WorkerPresence> candidates = new ArrayList<>();
        for (String routeId : commands.smembers(workerRoutesKey(normalizedWorkerId))) {
            WorkerPresence presence = readStoredRoutePresence(routeId);
            presence = presence != null ? materialize(presence) : null;
            if (presence != null && normalizedWorkerId.equals(presence.getWorkerId())) {
                candidates.add(presence);
            }
        }
        WorkerPresence online = candidates.stream()
                .filter(presence -> presence.getPresenceState() == WorkerPresenceState.ONLINE
                        && presence.getLeaseExpireAtEpochMillis() > now)
                .max(java.util.Comparator.comparingLong(WorkerPresence::getUpdatedAtEpochMillis))
                .orElse(null);
        if (online != null) {
            persistPresence(workerKey(normalizedWorkerId), online);
            return online;
        }
        WorkerPresence latestRoute = candidates.stream()
                .max(java.util.Comparator.comparingLong(WorkerPresence::getUpdatedAtEpochMillis))
                .orElse(null);
        if (latestRoute != null) {
            persistPresence(workerKey(normalizedWorkerId), latestRoute);
            return latestRoute;
        }
        return latestProjection;
    }

    @Override
    public boolean isRouteOnline(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return false;
        }
        WorkerPresence presence = readRoutePresence(null, normalizedAdapterId, normalizedRouteKey);
        return presence != null
                && presence.getPresenceState() == WorkerPresenceState.ONLINE
                && normalizedAdapterId.equals(presence.getAdapterId())
                && normalizedRouteKey.equals(presence.getRouteKey());
    }

    @Override
    public List<WorkerPresence> listActivePresences() {
        List<WorkerPresence> presences = new ArrayList<>();
        for (String routeId : commands.smembers(routesKey())) {
            WorkerPresence presence = readStoredRoutePresence(routeId);
            presence = presence != null ? materialize(presence) : null;
            if (presence != null && presence.getPresenceState() == WorkerPresenceState.ONLINE) {
                presences.add(presence);
            }
        }
        return List.copyOf(presences);
    }

    @Override
    public int pruneExpired() {
        int stale = 0;
        for (String routeId : commands.smembers(routesKey())) {
            WorkerPresence presence = readStoredRoutePresence(routeId);
            presence = presence != null ? materialize(presence) : null;
            if (presence != null && presence.getPresenceState() == WorkerPresenceState.STALE) {
                clearPreviousRoute(presence, presence.getWorkerId());
                commands.del(routePresenceKey(presence.getAdapterId(), presence.getRouteKey()));
                commands.srem(workerRoutesKey(presence.getWorkerId()), routeId);
                commands.srem(routesKey(), routeId);
                if (commands.scard(workerRoutesKey(presence.getWorkerId())) == 0L) {
                    commands.del(workerRoutesKey(presence.getWorkerId()));
                    commands.del(workerKey(presence.getWorkerId()));
                }
                stale++;
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

    private WorkerPresence materialize(WorkerPresence stored) {
        long now = System.currentTimeMillis();
        WorkerPresence effective = stored.effectiveAt(now);
        if (effective != stored) {
            persistMaterialized(effective, stored);
        }
        return effective;
    }

    private void persistMaterialized(WorkerPresence effective, WorkerPresence previous) {
        persistPresence(routePresenceKey(effective.getAdapterId(), effective.getRouteKey()), effective);
        persistPresence(workerKey(effective.getWorkerId()), effective);
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

    private WorkerPresence readStoredPresence(String key, String defaultWorkerId) {
        Map<String, String> fields = commands.hgetall(key);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return new WorkerPresence(
                normalizeNullable(fields.get("workerId")) != null ? normalizeNullable(fields.get("workerId")) : defaultWorkerId,
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

    private WorkerPresence readRoutePresence(String workerId, String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null) {
            return null;
        }
        WorkerPresence presence = readStoredPresence(routePresenceKey(normalizedAdapterId, normalizedRouteKey), workerId);
        return presence != null ? materialize(presence) : null;
    }

    private WorkerPresence readStoredRoutePresence(String routeId) {
        int separator = routeId == null ? -1 : routeId.indexOf('\u0000');
        if (separator <= 0 || separator >= routeId.length() - 1) {
            return null;
        }
        String adapterId = routeId.substring(0, separator);
        String routeKey = routeId.substring(separator + 1);
        return readStoredPresence(routePresenceKey(adapterId, routeKey), null);
    }

    private void persistPresence(String key, WorkerPresence presence) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workerId", presence.getWorkerId());
        fields.put("adapterId", presence.getAdapterId());
        fields.put("routeKey", presence.getRouteKey());
        fields.put("presenceState", presence.getPresenceState().name());
        fields.put("lastHeartbeatEpochMillis", Long.toString(presence.getLastHeartbeatEpochMillis()));
        fields.put("leaseExpireAtEpochMillis", Long.toString(presence.getLeaseExpireAtEpochMillis()));
        fields.put("transportInstanceId", presence.getTransportInstanceId());
        fields.put("connectionId", nullableField(presence.getConnectionId()));
        fields.put("updatedAtEpochMillis", Long.toString(presence.getUpdatedAtEpochMillis()));
        fields.put("disconnectReason", nullableField(normalizeNullable(presence.getDisconnectReason())));
        commands.hset(key, fields);
    }

    private String workerKey(String workerId) {
        return namespacePrefix + ":worker:" + workerId;
    }

    private String workersKey() {
        return namespacePrefix + ":workers";
    }

    private String routesKey() {
        return namespacePrefix + ":routes";
    }

    private String workerRoutesKey(String workerId) {
        return namespacePrefix + ":worker-routes:" + workerId;
    }

    private String routePresenceKey(String adapterId, String routeKey) {
        return namespacePrefix + ":route-presence:" + adapterId + '\u0000' + routeKey;
    }

    private static String routeIdentity(String adapterId, String routeKey) {
        return adapterId + '\u0000' + routeKey;
    }

    private String routeKey(String adapterId, String routeKey) {
        return namespacePrefix + ":route:" + adapterId + '\u0000' + routeKey;
    }

    private String routeKeyPrefix() {
        return namespacePrefix + ":route:";
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
