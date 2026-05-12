package com.xa.mass.sdk;

import com.xa.mass.starter.MassApplication;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default SDK-owned runtime diagnostics backed by the embedded runtime.
 */
public class DefaultRuntimeDiagnosticsOperations implements RuntimeDiagnosticsOperations {

    private final MassApplication delegate;

    public DefaultRuntimeDiagnosticsOperations(MassApplication delegate) {
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

    protected final MassApplication runtimeApplication() {
        return delegate;
    }

    private WorkerEndpointRegistry resolveEndpointRegistry() {
        return delegate.getEndpointRegistry();
    }

    private WorkerEndpointInspector resolveEndpointInspector() {
        WorkerEndpointRegistry endpointRegistry = resolveEndpointRegistry();
        return endpointRegistry instanceof WorkerEndpointInspector inspector ? inspector : null;
    }
}
