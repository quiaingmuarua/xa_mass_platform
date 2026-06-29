package com.xa.mass.starter.config;

import com.xa.mass.transport.starter.EmbeddedAdapterDeclaration;
import com.xa.mass.transport.starter.EmbeddedAdapterStarterDefaults;
import com.xa.mass.transport.starter.EmbeddedSocketAdapterDeclaration;
import com.xa.mass.transport.starter.EmbeddedTransportBackendDeclaration;
import com.xa.mass.transport.starter.EmbeddedWebSocketAdapterDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Transport configuration for embedded XA Mass application assembly.
 */
public class TransportConfig {

    public static final int DEFAULT_MAX_POLLING_PENDING_DELIVERY_ITEMS = 100_000;
    public static final int DEFAULT_MAX_POLLING_PENDING_DELIVERY_ITEMS_PER_WORKER = 10_000;
    public static final int DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS = 10_000;

    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    private EmbeddedTransportBackendDeclaration backendDeclaration = EmbeddedTransportBackendDeclaration
            .memory(
                    DEFAULT_MAX_POLLING_PENDING_DELIVERY_ITEMS,
                    DEFAULT_MAX_POLLING_PENDING_DELIVERY_ITEMS_PER_WORKER,
                    30_000L
            );
    private EmbeddedWebSocketAdapterDeclaration bundledWebSocketAdapterDeclaration =
            new EmbeddedWebSocketAdapterDeclaration();
    private EmbeddedSocketAdapterDeclaration bundledSocketAdapterDeclaration =
            new EmbeddedSocketAdapterDeclaration();
    private List<EmbeddedWebSocketAdapterDeclaration> supplementalWebSocketAdapterDeclarations = List.of();
    private List<EmbeddedSocketAdapterDeclaration> supplementalSocketAdapterDeclarations = List.of();
    private int transportRuntimeMaxPendingTasks = DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS;
    private int eventRuntimeMaxPendingTasks = DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS;
    private long eventHandlerTimeoutMillis;
    private TransportRuntimeRole runtimeRole = TransportRuntimeRole.EMBEDDED;

    public TransportConfig() {
    }

    public TransportConfig(TransportConfig source) {
        this.inputApiUrl = source.inputApiUrl;
        this.outputApiUrl = source.outputApiUrl;
        this.apiKey = source.apiKey;
        this.backendDeclaration = source.backendDeclaration;
        this.bundledWebSocketAdapterDeclaration =
                new EmbeddedWebSocketAdapterDeclaration(source.bundledWebSocketAdapterDeclaration);
        this.bundledSocketAdapterDeclaration =
                new EmbeddedSocketAdapterDeclaration(source.bundledSocketAdapterDeclaration);
        this.supplementalWebSocketAdapterDeclarations = source.supplementalWebSocketAdapterDeclarations.stream()
                .map(EmbeddedWebSocketAdapterDeclaration::new)
                .toList();
        this.supplementalSocketAdapterDeclarations = source.supplementalSocketAdapterDeclarations.stream()
                .map(EmbeddedSocketAdapterDeclaration::new)
                .toList();
        this.transportRuntimeMaxPendingTasks = source.transportRuntimeMaxPendingTasks;
        this.eventRuntimeMaxPendingTasks = source.eventRuntimeMaxPendingTasks;
        this.eventHandlerTimeoutMillis = source.eventHandlerTimeoutMillis;
        this.runtimeRole = source.runtimeRole;
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterDeclaration.enabled()
                || bundledSocketAdapterDeclaration.enabled()
                || hasAnyEnabledWebSocketDeclaration(supplementalWebSocketAdapterDeclarations)
                || hasAnyEnabledSocketDeclaration(supplementalSocketAdapterDeclarations);
    }

    public String getInputApiUrl() {
        return inputApiUrl;
    }

    public void setInputApiUrl(String inputApiUrl) {
        this.inputApiUrl = inputApiUrl;
    }

    public String getOutputApiUrl() {
        return outputApiUrl;
    }

    public void setOutputApiUrl(String outputApiUrl) {
        this.outputApiUrl = outputApiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public EmbeddedWebSocketAdapterDeclaration getBundledWebSocketAdapterDeclaration() {
        return bundledWebSocketAdapterDeclaration;
    }

    public void setBundledWebSocketAdapterDeclaration(
            EmbeddedWebSocketAdapterDeclaration bundledWebSocketAdapterDeclaration) {
        this.bundledWebSocketAdapterDeclaration = new EmbeddedWebSocketAdapterDeclaration(
                Objects.requireNonNull(bundledWebSocketAdapterDeclaration, "bundledWebSocketAdapterDeclaration")
        );
    }

    public EmbeddedSocketAdapterDeclaration getBundledSocketAdapterDeclaration() {
        return bundledSocketAdapterDeclaration;
    }

    public void setBundledSocketAdapterDeclaration(EmbeddedSocketAdapterDeclaration bundledSocketAdapterDeclaration) {
        this.bundledSocketAdapterDeclaration = new EmbeddedSocketAdapterDeclaration(
                Objects.requireNonNull(bundledSocketAdapterDeclaration, "bundledSocketAdapterDeclaration")
        );
    }

    public List<EmbeddedWebSocketAdapterDeclaration> getSupplementalWebSocketAdapterDeclarations() {
        return supplementalWebSocketAdapterDeclarations.stream()
                .map(EmbeddedWebSocketAdapterDeclaration::new)
                .toList();
    }

    public void addSupplementalWebSocketAdapterDeclaration(EmbeddedWebSocketAdapterDeclaration declaration) {
        if (declaration == null) {
            return;
        }
        List<EmbeddedWebSocketAdapterDeclaration> updated = new ArrayList<>(supplementalWebSocketAdapterDeclarations);
        updated.add(new EmbeddedWebSocketAdapterDeclaration(declaration));
        supplementalWebSocketAdapterDeclarations = List.copyOf(updated);
    }

    public List<EmbeddedSocketAdapterDeclaration> getSupplementalSocketAdapterDeclarations() {
        return supplementalSocketAdapterDeclarations.stream()
                .map(EmbeddedSocketAdapterDeclaration::new)
                .toList();
    }

    public void addSupplementalSocketAdapterDeclaration(EmbeddedSocketAdapterDeclaration declaration) {
        if (declaration == null) {
            return;
        }
        List<EmbeddedSocketAdapterDeclaration> updated = new ArrayList<>(supplementalSocketAdapterDeclarations);
        updated.add(new EmbeddedSocketAdapterDeclaration(declaration));
        supplementalSocketAdapterDeclarations = List.copyOf(updated);
    }

    public EmbeddedTransportBackendDeclaration getBackendDeclaration() {
        return backendDeclaration;
    }

    public void setBackendDeclaration(EmbeddedTransportBackendDeclaration backendDeclaration) {
        this.backendDeclaration = Objects.requireNonNull(backendDeclaration, "backendDeclaration");
    }

    public int getMaxPollingPendingDeliveryItems() {
        return backendDeclaration.maxPollingPendingDeliveryItems();
    }

    public void setMaxPollingPendingDeliveryItems(int maxPollingPendingDeliveryItems) {
        setBackendDeclaration(backendDeclaration.toBuilder()
                .maxPollingPendingDeliveryItems(maxPollingPendingDeliveryItems)
                .build());
    }

    public int getMaxPollingPendingDeliveryItemsPerWorker() {
        return backendDeclaration.maxPollingPendingDeliveryItemsPerWorker();
    }

    public void setMaxPollingPendingDeliveryItemsPerWorker(int maxPollingPendingDeliveryItemsPerWorker) {
        setBackendDeclaration(backendDeclaration.toBuilder()
                .maxPollingPendingDeliveryItemsPerWorker(maxPollingPendingDeliveryItemsPerWorker)
                .build());
    }

    public long getEndpointLeaseMillis() {
        return backendDeclaration.endpointLeaseMillis();
    }

    public void setEndpointLeaseMillis(long endpointLeaseMillis) {
        setBackendDeclaration(backendDeclaration.toBuilder()
                .endpointLeaseMillis(endpointLeaseMillis)
                .build());
    }

    public long getEventHandlerTimeoutMillis() {
        return eventHandlerTimeoutMillis;
    }

    public TransportRuntimeRole getRuntimeRole() {
        return runtimeRole;
    }

    public void setRuntimeRole(TransportRuntimeRole runtimeRole) {
        this.runtimeRole = runtimeRole == null ? TransportRuntimeRole.EMBEDDED : runtimeRole;
    }

    public int getTransportRuntimeMaxPendingTasks() {
        return transportRuntimeMaxPendingTasks;
    }

    public void setTransportRuntimeMaxPendingTasks(int transportRuntimeMaxPendingTasks) {
        if (transportRuntimeMaxPendingTasks <= 0) {
            throw new IllegalArgumentException("transportRuntimeMaxPendingTasks must be positive");
        }
        this.transportRuntimeMaxPendingTasks = transportRuntimeMaxPendingTasks;
    }

    public int getEventRuntimeMaxPendingTasks() {
        return eventRuntimeMaxPendingTasks;
    }

    public void setEventRuntimeMaxPendingTasks(int eventRuntimeMaxPendingTasks) {
        if (eventRuntimeMaxPendingTasks <= 0) {
            throw new IllegalArgumentException("eventRuntimeMaxPendingTasks must be positive");
        }
        this.eventRuntimeMaxPendingTasks = eventRuntimeMaxPendingTasks;
    }

    public void setEventHandlerTimeoutMillis(long eventHandlerTimeoutMillis) {
        if (eventHandlerTimeoutMillis < 0) {
            throw new IllegalArgumentException("eventHandlerTimeoutMillis must be greater than or equal to 0");
        }
        this.eventHandlerTimeoutMillis = eventHandlerTimeoutMillis;
    }

    public List<EmbeddedAdapterDeclaration> resolveEmbeddedAdapterDeclarations() {
        List<EmbeddedAdapterDeclaration> declarations = new ArrayList<>();
        declarations.add(EmbeddedAdapterDeclaration.pollingDefault());

        if (bundledWebSocketAdapterDeclaration.enabled()) {
            declarations.add(webSocketDeclaration(bundledWebSocketAdapterDeclaration));
        }
        if (bundledSocketAdapterDeclaration.enabled()) {
            declarations.add(socketDeclaration(bundledSocketAdapterDeclaration));
        }
        for (EmbeddedWebSocketAdapterDeclaration declaration : supplementalWebSocketAdapterDeclarations) {
            if (declaration.enabled()) {
                declarations.add(webSocketDeclaration(declaration));
            }
        }
        for (EmbeddedSocketAdapterDeclaration declaration : supplementalSocketAdapterDeclarations) {
            if (declaration.enabled()) {
                declarations.add(socketDeclaration(declaration));
            }
        }
        validateUniqueAdapterIds(declarations);
        return List.copyOf(declarations);
    }

    private static boolean hasAnyEnabledSocketDeclaration(List<EmbeddedSocketAdapterDeclaration> declarations) {
        return declarations.stream().anyMatch(EmbeddedSocketAdapterDeclaration::enabled);
    }

    private static boolean hasAnyEnabledWebSocketDeclaration(List<EmbeddedWebSocketAdapterDeclaration> declarations) {
        return declarations.stream().anyMatch(EmbeddedWebSocketAdapterDeclaration::enabled);
    }

    private static EmbeddedAdapterDeclaration webSocketDeclaration(EmbeddedWebSocketAdapterDeclaration declaration) {
        EmbeddedWebSocketAdapterDeclaration snapshot = new EmbeddedWebSocketAdapterDeclaration(declaration);
        return new EmbeddedAdapterDeclaration(
                EmbeddedAdapterStarterDefaults.TYPE_WEBSOCKET,
                snapshot.adapterId(),
                snapshot.adapterId(),
                EmbeddedAdapterDeclaration.DEFAULT_RESULT_QUEUE_KEY,
                EmbeddedAdapterStarterDefaults.webSocketOptions(snapshot)
        );
    }

    private static EmbeddedAdapterDeclaration socketDeclaration(EmbeddedSocketAdapterDeclaration declaration) {
        EmbeddedSocketAdapterDeclaration snapshot = new EmbeddedSocketAdapterDeclaration(declaration);
        return new EmbeddedAdapterDeclaration(
                EmbeddedAdapterStarterDefaults.TYPE_SOCKET,
                snapshot.adapterId(),
                snapshot.adapterId(),
                EmbeddedAdapterDeclaration.DEFAULT_RESULT_QUEUE_KEY,
                EmbeddedAdapterStarterDefaults.socketOptions(snapshot)
        );
    }

    private static void validateUniqueAdapterIds(List<EmbeddedAdapterDeclaration> declarations) {
        Set<String> adapterIds = new LinkedHashSet<>();
        for (EmbeddedAdapterDeclaration declaration : declarations) {
            String normalized = normalizeAdapterId(declaration.adapterId());
            if (!adapterIds.add(normalized)) {
                throw new IllegalStateException("Duplicate transport adapterId configured: " + normalized);
            }
        }
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
