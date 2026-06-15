package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.RouteConsumerEndpoint;
import com.xa.mass.transport.route.SelectedWorkerDeliveryTarget;
import com.xa.mass.transport.route.TransportRouteOwnerClaim;
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
 * <p>The runtime owner is a routeKey-partitioned consumer hash plus a derived
 * bucket-worker current-consumer pointer validated against the live consumer
 * lease.</p>
 */
public final class RedisTransportRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.ROUTE_OWNER;
    private static final String VERSION = "v4";
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
    public TransportRouteOwnerRecord claimRouteOwner(TransportRouteOwnerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        long now = System.currentTimeMillis();
        String normalizedConnectionId = normalizeNullable(claim.connectionId());
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                claim.workerId(),
                claim.deliveryBucketId(),
                claim.adapterId(),
                claim.routeKey(),
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
    public TransportRouteOwnerRecord refreshHeartbeat(TransportRouteOwnerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String normalizedRouteKey = normalizeNullable(claim.routeKey());
        String normalizedConnectionId = normalizeNullable(claim.connectionId());
        if (normalizedRouteKey == null || normalizedConnectionId == null) {
            return null;
        }
        TransportRouteOwnerRecord previous = readOwnerRecord(normalizedRouteKey, normalizedConnectionId);
        if (previous == null
                || !sameClaimConsumer(previous, claim)
                || !isCurrentBucketWorkerConsumer(previous)) {
            return previous;
        }
        long now = System.currentTimeMillis();
        TransportRouteOwnerRecord next = new TransportRouteOwnerRecord(
                previous.getWorkerId(),
                previous.getDeliveryBucketId(),
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
    public TransportRouteOwnerRecord releaseRouteOwner(TransportRouteOwnerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String normalizedRouteKey = normalizeRequired(claim.routeKey(), "routeKey");
        String normalizedConnectionId = normalizeNullable(claim.connectionId());
        if (normalizedConnectionId == null) {
            return null;
        }
        TransportRouteOwnerRecord previous = readOwnerRecord(normalizedRouteKey, normalizedConnectionId);
        if (previous == null
                || !sameClaimConsumer(previous, claim)) {
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
    public Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(String deliveryBucketId,
                                                                          String selectedWorkerId) {
        return endpointForSelectedWorker(deliveryBucketId, selectedWorkerId)
                .map(RouteConsumerEndpoint::toTarget);
    }

    @Override
    public Optional<RouteConsumerEndpoint> endpointForSelectedWorker(String deliveryBucketId, String selectedWorkerId) {
        String normalizedBucketId = normalizeNullable(deliveryBucketId);
        String normalizedWorkerId = normalizeNullable(selectedWorkerId);
        if (normalizedBucketId == null || normalizedWorkerId == null) {
            return Optional.empty();
        }
        String indexKey = bucketWorkerKey(normalizedBucketId, normalizedWorkerId);
        RouteConsumerMember member = decodeRouteConsumerMember(commands.get(indexKey));
        if (member == null) {
            return Optional.empty();
        }
        TransportRouteOwnerRecord owner = readOwnerRecord(member.routeKey(), member.connectionId());
        long now = System.currentTimeMillis();
        if (owner == null
                || !owner.isLeaseActive(now)
                || !normalizedBucketId.equals(owner.getDeliveryBucketId())
                || !normalizedWorkerId.equals(owner.getWorkerId())) {
            commands.del(indexKey);
            return Optional.empty();
        }
        return Optional.of(endpointFromRecord(owner));
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

    private void persistOwner(TransportRouteOwnerRecord owner, boolean replaceCurrentConsumer) {
        commands.hset(routeConsumersKey(owner.getRouteKey()), owner.getConnectionId(), encodeOwnerRecord(owner));
        updateBucketWorkerPointer(owner, replaceCurrentConsumer);
        commands.zadd(deadlineKey(), owner.getLeaseExpireAtEpochMillis(), routeConsumerMember(owner));
    }

    private void updateBucketWorkerPointer(TransportRouteOwnerRecord owner, boolean replaceCurrentConsumer) {
        String key = bucketWorkerKey(owner.getDeliveryBucketId(), owner.getWorkerId());
        String ownerMember = routeConsumerMember(owner);
        if (replaceCurrentConsumer) {
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
        clearBucketWorkerProjection(owner);
    }

    private void clearBucketWorkerProjection(TransportRouteOwnerRecord owner) {
        if (owner == null || owner.getWorkerId() == null || owner.getDeliveryBucketId() == null) {
            return;
        }
        String key = bucketWorkerKey(owner.getDeliveryBucketId(), owner.getWorkerId());
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

    private String bucketWorkerKey(String deliveryBucketId, String workerId) {
        return namespacePrefix
                + ":bucket:" + encodeKeyToken(deliveryBucketId)
                + ":worker:" + encodeKeyToken(workerId)
                + ":owner";
    }

    private boolean isCurrentBucketWorkerConsumer(TransportRouteOwnerRecord owner) {
        if (owner == null || owner.getDeliveryBucketId() == null || owner.getWorkerId() == null) {
            return false;
        }
        RouteConsumerMember current = decodeRouteConsumerMember(
                commands.get(bucketWorkerKey(owner.getDeliveryBucketId(), owner.getWorkerId()))
        );
        return current != null
                && owner.getRouteKey().equals(current.routeKey())
                && owner.getConnectionId().equals(current.connectionId());
    }

    private static boolean sameClaimConsumer(TransportRouteOwnerRecord owner, TransportRouteOwnerClaim claim) {
        return owner != null
                && claim != null
                && owner.getWorkerId().equals(claim.workerId())
                && owner.getDeliveryBucketId().equals(claim.deliveryBucketId())
                && owner.getAdapterId().equals(claim.adapterId())
                && owner.getRouteKey().equals(claim.routeKey())
                && owner.getConnectionId().equals(claim.connectionId());
    }

    private static RouteConsumerEndpoint endpointFromRecord(TransportRouteOwnerRecord owner) {
        return new RouteConsumerEndpoint(
                owner.getDeliveryBucketId(),
                owner.getWorkerId(),
                owner.getAdapterId(),
                owner.getRouteKey(),
                owner.getConnectionId(),
                owner.getTransportNodeId(),
                owner.getLeaseExpireAtEpochMillis()
        );
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
                encodeToken(owner.getDeliveryBucketId()),
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
        if (parts.length != 10 || !VERSION.equals(parts[0])) {
            return null;
        }
        return new TransportRouteOwnerRecord(
                decodeNullableToken(parts[1]),
                decodeToken(parts[2], "deliveryBucketId"),
                decodeToken(parts[3], "adapterId"),
                decodeToken(parts[4], "routeKey"),
                parseLong(parts[5]),
                parseLong(parts[6]),
                decodeToken(parts[7], "transportInstanceId"),
                decodeToken(parts[8], "connectionId"),
                parseLong(parts[9])
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
