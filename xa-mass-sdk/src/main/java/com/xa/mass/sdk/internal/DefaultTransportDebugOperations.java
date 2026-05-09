package com.xa.mass.sdk.internal;

import com.google.gson.Gson;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.*;

public final class DefaultTransportDebugOperations implements TransportDebugOperations {

    private static final Gson GSON = new Gson();

    private final MassApplication delegate;

    public DefaultTransportDebugOperations(MassApplication delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> data = new ArrayList<>();
        WorkerEndpointInspector endpointInspector = resolveEndpointInspector();
        if (endpointInspector == null) {
            return data;
        }

        Map<String, List<WorkerEndpointSnapshot>> grouped = new HashMap<>();
        for (WorkerEndpointSnapshot snapshot : endpointInspector.listWorkerEndpoints()) {
            grouped.computeIfAbsent(snapshot.getWorkerId(), ignored -> new ArrayList<>()).add(snapshot);
        }
        grouped.forEach((workerId, endpoints) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("workerId", workerId);
            List<Map<String, Object>> connections = new ArrayList<>();
            endpoints.forEach(snapshot -> {
                Map<String, Object> connectionInfo = new LinkedHashMap<>();
                connectionInfo.put("active", snapshot.isActive());
                connectionInfo.put("endpointId", snapshot.getEndpointId());
                connectionInfo.put("routeKey", snapshot.getRouteKey());
                connectionInfo.put("adapterId", snapshot.getAdapterId());
                connections.add(connectionInfo);
            });
            entry.put("connections", connections);
            data.add(entry);
        });
        return data;
    }

    @Override
    public Map<String, Object> getSessionStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        WorkerEndpointRegistry endpointRegistry = resolveEndpointRegistry();
        WorkerEndpointInspector endpointInspector = resolveEndpointInspector();
        if (endpointRegistry != null) {
            data.put("activeConnections", endpointRegistry.getActiveConnectionCount());
            if (endpointInspector != null) {
                List<WorkerEndpointSnapshot> snapshots = endpointInspector.listWorkerEndpoints();
                data.put("workerCount", snapshots.stream().map(WorkerEndpointSnapshot::getWorkerId).distinct().count());
                Map<String, Long> activeConnectionsByAdapter = new LinkedHashMap<>();
                snapshots.stream()
                        .filter(WorkerEndpointSnapshot::isActive)
                        .forEach(snapshot -> activeConnectionsByAdapter.merge(
                                snapshot.getAdapterId(),
                                1L,
                                Long::sum
                        ));
                data.put("activeConnectionsByAdapter", activeConnectionsByAdapter);
            } else {
                data.put("workerCount", 0L);
                data.put("activeConnectionsByAdapter", Map.of());
            }
        } else {
            data.put("activeConnections", 0);
            data.put("workerCount", 0L);
            data.put("activeConnectionsByAdapter", Map.of());
        }
        return data;
    }

    @Override
    public Map<String, Object> enqueueRawMessage(Map<String, Object> request) {
        Object workerId = request.get("workerId");
        if (!(workerId instanceof String workerIdText) || workerIdText.isBlank()) {
            return Map.of("success", false, "msg", "workerId is required");
        }
        Object rawJson = request.get("rawJson");
        String payload = rawJson instanceof String rawText ? rawText : GSON.toJson(request);
        boolean accepted = delegate.sendRawTransportMessage(
                workerIdText.trim(),
                payload,
                UUID.randomUUID().toString()
        );
        if (!accepted) {
            return Map.of("success", false, "msg", "no transport side-channel accepted a unique active worker route");
        }
        return Map.of("success", true, "msg", "message enqueued");
    }

    @Override
    public Map<String, Object> getQueueDetail() {
        return delegate.getTransportQueueDetail();
    }

    @Override
    public Map<String, Object> getQueueMetrics() {
        return Map.of(
                "inputQueueRate", 0,
                "outputQueueRate", 0
        );
    }

    private WorkerEndpointRegistry resolveEndpointRegistry() {
        return delegate.getEndpointRegistry();
    }

    private WorkerEndpointInspector resolveEndpointInspector() {
        WorkerEndpointRegistry endpointRegistry = resolveEndpointRegistry();
        return endpointRegistry instanceof WorkerEndpointInspector inspector ? inspector : null;
    }
}
