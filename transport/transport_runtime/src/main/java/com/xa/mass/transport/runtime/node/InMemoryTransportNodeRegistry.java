package com.xa.mass.transport.runtime.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory transport-node registry for embedded tests.
 */
public final class InMemoryTransportNodeRegistry implements TransportNodeRegistry {

    public static final long DEFAULT_LEASE_MILLIS = 30_000L;

    private final long leaseMillis;
    private final ConcurrentMap<String, TransportNodePresence> nodeById = new ConcurrentHashMap<>();

    public InMemoryTransportNodeRegistry() {
        this(DEFAULT_LEASE_MILLIS);
    }

    public InMemoryTransportNodeRegistry(long leaseMillis) {
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be greater than 0");
        }
        this.leaseMillis = leaseMillis;
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
        return nodeById.compute(nodeId, (ignored, previous) -> {
            TransportNodePresence current = materialize(previous, now);
            List<String> adapterIds = current != null ? current.adapterIds() : List.of();
            long lastHeartbeat = current != null ? current.lastHeartbeatEpochMillis() : 0L;
            long connectionCount = current != null ? current.connectionCount() : 0L;
            return new TransportNodePresence(
                    nodeId,
                    adapterIds,
                    TransportNodeState.OFFLINE,
                    lastHeartbeat,
                    now,
                    now,
                    connectionCount
            );
        });
    }

    @Override
    public TransportNodePresence getNode(String transportNodeId) {
        String nodeId = normalizeNullable(transportNodeId);
        if (nodeId == null) {
            return null;
        }
        return materialize(nodeById.get(nodeId), System.currentTimeMillis());
    }

    @Override
    public List<TransportNodePresence> listNodes() {
        long now = System.currentTimeMillis();
        List<TransportNodePresence> nodes = new ArrayList<>();
        for (TransportNodePresence stored : nodeById.values()) {
            TransportNodePresence node = materialize(stored, now);
            if (node != null) {
                nodes.add(node);
            }
        }
        return List.copyOf(nodes);
    }

    public long getLeaseMillis() {
        return leaseMillis;
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
        nodeById.put(nodeId, next);
        return next;
    }

    private TransportNodePresence materialize(TransportNodePresence stored, long now) {
        if (stored == null) {
            return null;
        }
        TransportNodePresence effective = stored.effectiveAt(now);
        if (effective != stored) {
            nodeById.put(stored.transportNodeId(), effective);
        }
        return effective;
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
