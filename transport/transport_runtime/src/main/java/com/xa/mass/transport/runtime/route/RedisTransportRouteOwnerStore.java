package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis-backed transport route-consumer heartbeat store.
 *
 * <p>The runtime owner is a routeKey-partitioned consumer hash. WorkerId is
 * optional consumer metadata and the selected-worker delivery lookup is the
 * derived adapter-worker pointer validated against the live consumer lease.</p>
 */
public final class RedisTransportRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.ROUTE_OWNER;
    private static final String VERSION = "v3";
    private static final String MEMBER_SEPARATOR = "\u001f";
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
        String normalizedWorkerId = normalizeNullable(workerId);
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
        persistOwner(next, true);
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
        if (normalizedRouteKey == null || normalizedConnectionId == null) {
            return null;
        }
        TransportRouteOwnerRecord previous = readOwnerRecord(normalizedRouteKey, normalizedConnectionId);
        if (previous == null
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || (normalizedWorkerId != null && !normalizedWorkerId.equals(previous.getWorkerId()))) {
            return previous;
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
        persistOwner(next, false);
        return next;
    }

    @Override
    public TransportRouteOwnerRecord releaseRouteOwner(String workerId,
                                                       String adapterId,
                                                       String routeKey,
                                                       String connectionId,
                                                       String reason) {
        String normalizedWorkerId = normalizeNullable(workerId);
        String normalizedAdapterId = normalizeRequired(adapterId, "adapterId");
        String normalizedRouteKey = normalizeRequired(routeKey, "routeKey");
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedConnectionId == null) {
            return null;
        }
        TransportRouteOwnerRecord previous = readOwnerRecord(normalizedRouteKey, normalizedConnectionId);
        if (previous == null
                || !normalizedAdapterId.equals(previous.getAdapterId())
                || (normalizedWorkerId != null && !normalizedWorkerId.equals(previous.getWorkerId()))) {
            return previous;
        }
        removeOwner(previous);
        return previous;
    }

    @Override
    public boolean hasActiveRouteOwner(String adapterId, String routeKey) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        if (normalizedAdapterId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        return currentOwners(routeKey).stream()
                .filter(owner -> normalizedAdapterId.equals(owner.adapterId()))
                .anyMatch(owner -> owner.isActive(now));
    }

    @Override
    public List<WorkerDispatchRouteOwner> currentOwners(String routeKey) {
        return readOwnerRecords(normalizeNullable(routeKey)).stream()
                .map(WorkerDispatchRouteOwner::fromRecord)
                .toList();
    }

    @Override
    public Optional<WorkerDispatchRouteOwner> activeOwnerForSelectedWorker(String adapterId, String selectedWorkerId) {
        String normalizedAdapterId = normalizeNullable(adapterId);
        String normalizedWorkerId = normalizeNullable(selectedWorkerId);
        if (normalizedAdapterId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        String indexKey = adapterWorkerKey(normalizedAdapterId, normalizedWorkerId);
        RouteConsumerMember member = decodeRouteConsumerMember(commands.get(indexKey));
        if (member == null) {
            return Optional.empty();
        }
        TransportRouteOwnerRecord owner = readOwnerRecord(member.routeKey(), member.connectionId());
        long now = System.currentTimeMillis();
        if (owner == null
                || !owner.isLeaseActive(now)
                || !normalizedAdapterId.equals(owner.getAdapterId())
                || !normalizedWorkerId.equals(owner.getWorkerId())) {
            commands.del(indexKey);
            return Optional.empty();
        }
        return Optional.of(WorkerDispatchRouteOwner.fromRecord(owner));
    }

    @Override
    public int pruneExpired() {
        int stale = 0;
        long now = System.currentTimeMillis();
        List<String> due = commands.zrangebyscore(
                deadlineKey(),
                Range.create(Double.NEGATIVE_INFINITY, (double) now)
        );
        for (String member : due) {
            RouteConsumerMember routeConsumer = decodeRouteConsumerMember(member);
            if (routeConsumer == null) {
                commands.zrem(deadlineKey(), member);
                continue;
            }
            TransportRouteOwnerRecord owner = readOwnerRecord(routeConsumer.routeKey(), routeConsumer.connectionId());
            if (owner != null && !owner.isLeaseActive(now)) {
                removeOwner(owner);
                stale++;
            } else if (owner == null) {
                commands.zrem(deadlineKey(), member);
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

    private TransportRouteOwnerRecord readOwnerRecord(String routeKey, String connectionId) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        String normalizedConnectionId = normalizeNullable(connectionId);
        if (normalizedRouteKey == null || normalizedConnectionId == null) {
            return null;
        }
        return decodeOwnerRecord(commands.hget(routeConsumersKey(normalizedRouteKey), normalizedConnectionId));
    }

    private List<TransportRouteOwnerRecord> readOwnerRecords(String routeKey) {
        String normalizedRouteKey = normalizeNullable(routeKey);
        if (normalizedRouteKey == null) {
            return List.of();
        }
        return commands.hvals(routeConsumersKey(normalizedRouteKey)).stream()
                .map(RedisTransportRouteOwnerStore::decodeOwnerRecord)
                .filter(Objects::nonNull)
                .toList();
    }

    private void persistOwner(TransportRouteOwnerRecord owner, boolean replaceAdapterWorkerPointer) {
        commands.hset(routeConsumersKey(owner.getRouteKey()), owner.getConnectionId(), encodeOwnerRecord(owner));
        if (owner.getWorkerId() != null) {
            updateAdapterWorkerPointer(owner, replaceAdapterWorkerPointer);
        }
        commands.zadd(deadlineKey(), owner.getLeaseExpireAtEpochMillis(), routeConsumerMember(owner));
    }

    private void updateAdapterWorkerPointer(TransportRouteOwnerRecord owner, boolean replaceAdapterWorkerPointer) {
        String key = adapterWorkerKey(owner.getAdapterId(), owner.getWorkerId());
        String ownerMember = routeConsumerMember(owner);
        if (replaceAdapterWorkerPointer) {
            commands.set(key, ownerMember);
            return;
        }
        RouteConsumerMember current = decodeRouteConsumerMember(commands.get(key));
        if (current == null
                || (owner.getRouteKey().equals(current.routeKey())
                && owner.getConnectionId().equals(current.connectionId()))) {
            commands.set(key, ownerMember);
        }
    }

    private void removeOwner(TransportRouteOwnerRecord owner) {
        String routeKey = owner.getRouteKey();
        String connectionId = owner.getConnectionId();
        commands.hdel(routeConsumersKey(routeKey), connectionId);
        commands.zrem(deadlineKey(), routeConsumerMember(routeKey, connectionId));
        if (commands.hlen(routeConsumersKey(routeKey)) == 0L) {
            commands.del(routeConsumersKey(routeKey));
        }
        clearAdapterWorkerProjection(owner);
    }

    private void clearAdapterWorkerProjection(TransportRouteOwnerRecord owner) {
        if (owner == null || owner.getWorkerId() == null) {
            return;
        }
        String key = adapterWorkerKey(owner.getAdapterId(), owner.getWorkerId());
        RouteConsumerMember current = decodeRouteConsumerMember(commands.get(key));
        if (current != null
                && owner.getRouteKey().equals(current.routeKey())
                && owner.getConnectionId().equals(current.connectionId())) {
            commands.del(key);
        }
    }

    private String routeConsumersKey(String routeKey) {
        return namespacePrefix + ":route:" + encodeKeyToken(routeKey) + ":consumers";
    }

    private String deadlineKey() {
        return namespacePrefix + ":deadline";
    }

    private String adapterWorkerKey(String adapterId, String workerId) {
        return namespacePrefix
                + ":adapter:" + encodeKeyToken(adapterId)
                + ":worker:" + encodeKeyToken(workerId)
                + ":owner";
    }

    private static String routeConsumerMember(TransportRouteOwnerRecord owner) {
        return routeConsumerMember(owner.getRouteKey(), owner.getConnectionId());
    }

    private static String routeConsumerMember(String routeKey, String connectionId) {
        return encodeKeyToken(routeKey) + MEMBER_SEPARATOR + encodeKeyToken(connectionId);
    }

    private static RouteConsumerMember decodeRouteConsumerMember(String member) {
        if (member == null || member.isBlank()) {
            return null;
        }
        String[] parts = member.split(MEMBER_SEPARATOR, -1);
        if (parts.length != 2) {
            return null;
        }
        return new RouteConsumerMember(decodeKeyToken(parts[0]), decodeKeyToken(parts[1]));
    }

    private static String encodeOwnerRecord(TransportRouteOwnerRecord owner) {
        return String.join("|",
                VERSION,
                encodeNullableToken(owner.getWorkerId()),
                encodeToken(owner.getAdapterId()),
                encodeToken(owner.getRouteKey()),
                Long.toString(owner.getLastHeartbeatEpochMillis()),
                Long.toString(owner.getLeaseExpireAtEpochMillis()),
                encodeToken(owner.getTransportNodeId()),
                encodeToken(owner.getConnectionId()),
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
                decodeNullableToken(parts[1]),
                decodeToken(parts[2], "adapterId"),
                decodeToken(parts[3], "routeKey"),
                parseLong(parts[4]),
                parseLong(parts[5]),
                decodeToken(parts[6], "transportInstanceId"),
                decodeToken(parts[7], "connectionId"),
                parseLong(parts[8])
        );
    }

    private static String encodeKeyToken(String value) {
        return TOKEN_ENCODER.encodeToString(normalizeRequired(value, "key").getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKeyToken(String value) {
        return normalizeRequired(new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8), "key");
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

    private record RouteConsumerMember(String routeKey, String connectionId) {
    }
}
