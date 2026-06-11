package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runtime-owned endpoint registry that can aggregate multiple adapter-owned
 * endpoint registries under one transport-neutral surface.
 */
public final class CompositeWorkerEndpointRegistry implements WorkerEndpointRegistry, WorkerEndpointInspector {

    private final Map<String, WorkerEndpointRegistry> registriesByAdapterId = new LinkedHashMap<>();
    private final Map<String, WorkerEndpointInspector> inspectorsByAdapterId = new LinkedHashMap<>();

    public synchronized <T extends WorkerEndpointRegistry> T getOrRegister(String adapterId, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        String normalizedAdapterId = normalizeAdapterId(adapterId);
        WorkerEndpointRegistry existing = registriesByAdapterId.get(normalizedAdapterId);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            T typed = (T) existing;
            return typed;
        }
        T created = Objects.requireNonNull(supplier.get(), "supplier returned null registry");
        register(normalizedAdapterId, created);
        return created;
    }

    public synchronized void register(String adapterId, WorkerEndpointRegistry registry) {
        String normalizedAdapterId = normalizeAdapterId(adapterId);
        Objects.requireNonNull(registry, "registry");
        WorkerEndpointRegistry existing = registriesByAdapterId.get(normalizedAdapterId);
        if (existing != null && existing != registry) {
            throw new IllegalArgumentException("Endpoint registry already registered for adapterId '"
                    + normalizedAdapterId + "'");
        }
        registriesByAdapterId.put(normalizedAdapterId, registry);
        if (registry instanceof WorkerEndpointInspector inspector) {
            inspectorsByAdapterId.put(normalizedAdapterId, inspector);
        }
    }

    @Override
    public synchronized boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        WorkerEndpointRegistry registry = registryForAdapter(adapterId);
        return registry != null && registry.sendToAdapterRoute(adapterId, routeKey, message);
    }

    @Override
    public synchronized boolean sendToAdapterRoute(String adapterId, String routeKey, String workerId, String message) {
        WorkerEndpointRegistry registry = registryForAdapter(adapterId);
        return registry != null && registry.sendToAdapterRoute(adapterId, routeKey, workerId, message);
    }

    @Override
    public synchronized boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        WorkerEndpointRegistry registry = registryForAdapter(adapterId);
        return registry != null && registry.isAdapterRouteOnline(adapterId, routeKey);
    }

    @Override
    public synchronized int getActiveConnectionCount() {
        int count = 0;
        for (WorkerEndpointRegistry registry : uniqueRegistries()) {
            count += registry.getActiveConnectionCount();
        }
        return count;
    }

    @Override
    public synchronized void shutdown() {
        for (WorkerEndpointRegistry registry : uniqueRegistries()) {
            registry.shutdown();
        }
        registriesByAdapterId.clear();
        inspectorsByAdapterId.clear();
    }

    @Override
    public synchronized List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (WorkerEndpointInspector inspector : uniqueInspectors()) {
            snapshots.addAll(inspector.listWorkerEndpoints());
        }
        return List.copyOf(snapshots);
    }

    private List<WorkerEndpointRegistry> uniqueRegistries() {
        Set<WorkerEndpointRegistry> unique = new LinkedHashSet<>(registriesByAdapterId.values());
        return List.copyOf(unique);
    }

    private List<WorkerEndpointInspector> uniqueInspectors() {
        Set<WorkerEndpointInspector> unique = new LinkedHashSet<>(inspectorsByAdapterId.values());
        return List.copyOf(unique);
    }

    private WorkerEndpointRegistry registryForAdapter(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return registriesByAdapterId.get(normalizeAdapterId(adapterId));
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
