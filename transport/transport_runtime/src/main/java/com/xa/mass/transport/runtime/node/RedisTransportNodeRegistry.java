package com.xa.mass.transport.runtime.node;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis-backed transport-node registry.
 */
public final class RedisTransportNodeRegistry implements TransportNodeRegistry, AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = "xa:mass:transport:nodes";
    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final long leaseMillis;
    private final boolean ownsClient;
    private final Gson gson = new Gson();

    public RedisTransportNodeRegistry(String redisUri, String namespacePrefix, long leaseMillis) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                leaseMillis,
                true
        );
    }

    RedisTransportNodeRegistry(RedisClient redisClient,
                               String namespacePrefix,
                               long leaseMillis,
                               boolean ownsClient) {
        this(redisClient, redisClient.connect(), namespacePrefix, leaseMillis, ownsClient);
    }

    RedisTransportNodeRegistry(StatefulRedisConnection<String, String> connection,
                               String namespacePrefix,
                               long leaseMillis) {
        this(null, connection, namespacePrefix, leaseMillis, false);
    }

    private RedisTransportNodeRegistry(RedisClient redisClient,
                                       StatefulRedisConnection<String, String> connection,
                                       String namespacePrefix,
                                       long leaseMillis,
                                       boolean ownsClient) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = requireText(namespacePrefix, "namespacePrefix");
        this.leaseMillis = leaseMillis;
        this.ownsClient = ownsClient;
    }

    @Override
    public TransportNodePresence register(String transportNodeId, List<String> adapterIds, long connectionCount) {
        return upsertOnline(transportNodeId, adapterIds, connectionCount);
    }

    @Override
    public TransportNodePresence heartbeat(String transportNodeId, List<String> adapterIds, long connectionCount) {
        return upsertOnline(transportNodeId, adapterIds, connectionCount);
    }

    @Override
    public TransportNodePresence markOffline(String transportNodeId) {
        String nodeId = requireText(transportNodeId, "transportNodeId");
        long now = System.currentTimeMillis();
        TransportNodePresence previous = readStoredNode(nodeId);
        TransportNodePresence next = new TransportNodePresence(
                nodeId,
                previous != null ? previous.adapterIds() : List.of(),
                TransportNodeState.OFFLINE,
                previous != null ? previous.lastHeartbeatEpochMillis() : 0L,
                now,
                now,
                previous != null ? previous.connectionCount() : 0L
        );
        persist(next);
        return next;
    }

    @Override
    public TransportNodePresence getNode(String transportNodeId) {
        String nodeId = normalizeNullable(transportNodeId);
        if (nodeId == null) {
            return null;
        }
        TransportNodePresence node = readStoredNode(nodeId);
        return node != null ? materialize(node) : null;
    }

    @Override
    public List<TransportNodePresence> listNodes() {
        List<TransportNodePresence> nodes = new ArrayList<>();
        for (String nodeId : commands.smembers(nodesKey())) {
            TransportNodePresence node = getNode(nodeId);
            if (node != null) {
                nodes.add(node);
            }
        }
        return List.copyOf(nodes);
    }

    public long getLeaseMillis() {
        return leaseMillis;
    }

    public String getNamespacePrefix() {
        return namespacePrefix;
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

    private TransportNodePresence upsertOnline(String transportNodeId, List<String> adapterIds, long connectionCount) {
        String nodeId = requireText(transportNodeId, "transportNodeId");
        long now = System.currentTimeMillis();
        TransportNodePresence next = new TransportNodePresence(
                nodeId,
                normalizeAdapterIds(adapterIds),
                TransportNodeState.ONLINE,
                now,
                now + leaseMillis,
                now,
                Math.max(0L, connectionCount)
        );
        persist(next);
        return next;
    }

    private TransportNodePresence materialize(TransportNodePresence stored) {
        TransportNodePresence effective = stored.effectiveAt(System.currentTimeMillis());
        if (effective != stored) {
            persist(effective);
        }
        return effective;
    }

    private void persist(TransportNodePresence node) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("transportNodeId", node.transportNodeId());
        fields.put("adapterIds", gson.toJson(node.adapterIds()));
        fields.put("state", node.state().name());
        fields.put("lastHeartbeatEpochMillis", Long.toString(node.lastHeartbeatEpochMillis()));
        fields.put("leaseExpireAtEpochMillis", Long.toString(node.leaseExpireAtEpochMillis()));
        fields.put("updatedAtEpochMillis", Long.toString(node.updatedAtEpochMillis()));
        fields.put("connectionCount", Long.toString(node.connectionCount()));
        commands.hset(nodeKey(node.transportNodeId()), fields);
        commands.sadd(nodesKey(), node.transportNodeId());
    }

    private TransportNodePresence readStoredNode(String transportNodeId) {
        Map<String, String> fields = commands.hgetall(nodeKey(transportNodeId));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return new TransportNodePresence(
                fields.getOrDefault("transportNodeId", transportNodeId),
                parseAdapterIds(fields.get("adapterIds")),
                TransportNodeState.valueOf(fields.getOrDefault("state", TransportNodeState.OFFLINE.name())),
                parseLong(fields.get("lastHeartbeatEpochMillis")),
                parseLong(fields.get("leaseExpireAtEpochMillis")),
                parseLong(fields.get("updatedAtEpochMillis")),
                parseLong(fields.get("connectionCount"))
        );
    }

    private List<String> parseAdapterIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> adapterIds = gson.fromJson(raw, STRING_LIST_TYPE);
        return normalizeAdapterIds(adapterIds);
    }

    private String nodeKey(String transportNodeId) {
        return namespacePrefix + ":node:" + transportNodeId;
    }

    private String nodesKey() {
        return namespacePrefix + ":nodes";
    }

    private static List<String> normalizeAdapterIds(List<String> adapterIds) {
        if (adapterIds == null || adapterIds.isEmpty()) {
            return List.of();
        }
        return adapterIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }

    private static String requireText(String value, String fieldName) {
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
