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
import java.util.Set;

/**
 * Fixed transport composition snapshot derived from {@link TransportConfig}.
 */
public class TransportRuntimeComposition {

    private final EmbeddedTransportBackendDeclaration backendDeclaration;
    private final EmbeddedWebSocketAdapterDeclaration bundledWebSocketAdapterDeclaration;
    private final EmbeddedSocketAdapterDeclaration bundledSocketAdapterDeclaration;
    private final List<EmbeddedWebSocketAdapterDeclaration> supplementalWebSocketAdapterDeclarations;
    private final List<EmbeddedSocketAdapterDeclaration> supplementalSocketAdapterDeclarations;
    private final int transportRuntimeMaxPendingTasks;
    private final int eventRuntimeMaxPendingTasks;
    private final long eventHandlerTimeoutMillis;
    private final TransportRuntimeRole runtimeRole;

    public TransportRuntimeComposition(TransportConfig source) {
        this.backendDeclaration = source.getBackendDeclaration();
        this.bundledWebSocketAdapterDeclaration =
                new EmbeddedWebSocketAdapterDeclaration(source.getBundledWebSocketAdapterDeclaration());
        this.bundledSocketAdapterDeclaration =
                new EmbeddedSocketAdapterDeclaration(source.getBundledSocketAdapterDeclaration());
        this.supplementalWebSocketAdapterDeclarations = source.getSupplementalWebSocketAdapterDeclarations();
        this.supplementalSocketAdapterDeclarations = source.getSupplementalSocketAdapterDeclarations();
        this.transportRuntimeMaxPendingTasks = source.getTransportRuntimeMaxPendingTasks();
        this.eventRuntimeMaxPendingTasks = source.getEventRuntimeMaxPendingTasks();
        this.eventHandlerTimeoutMillis = source.getEventHandlerTimeoutMillis();
        this.runtimeRole = source.getRuntimeRole();
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterDeclaration.enabled()
                || bundledSocketAdapterDeclaration.enabled()
                || supplementalWebSocketAdapterDeclarations.stream().anyMatch(EmbeddedWebSocketAdapterDeclaration::enabled)
                || supplementalSocketAdapterDeclarations.stream().anyMatch(EmbeddedSocketAdapterDeclaration::enabled);
    }

    public EmbeddedWebSocketAdapterDeclaration getBundledWebSocketAdapterDeclaration() {
        return new EmbeddedWebSocketAdapterDeclaration(bundledWebSocketAdapterDeclaration);
    }

    public EmbeddedSocketAdapterDeclaration getBundledSocketAdapterDeclaration() {
        return new EmbeddedSocketAdapterDeclaration(bundledSocketAdapterDeclaration);
    }

    public List<EmbeddedWebSocketAdapterDeclaration> getSupplementalWebSocketAdapterDeclarations() {
        return supplementalWebSocketAdapterDeclarations.stream()
                .map(EmbeddedWebSocketAdapterDeclaration::new)
                .toList();
    }

    public List<EmbeddedSocketAdapterDeclaration> getSupplementalSocketAdapterDeclarations() {
        return supplementalSocketAdapterDeclarations.stream()
                .map(EmbeddedSocketAdapterDeclaration::new)
                .toList();
    }

    public EmbeddedTransportBackendDeclaration getBackendDeclaration() {
        return backendDeclaration;
    }

    public int getMaxPollingPendingDeliveryItems() {
        return backendDeclaration.maxPollingPendingDeliveryItems();
    }

    public int getMaxPollingPendingDeliveryItemsPerWorker() {
        return backendDeclaration.maxPollingPendingDeliveryItemsPerWorker();
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

    public long getEventHandlerTimeoutMillis() {
        return eventHandlerTimeoutMillis;
    }

    public int getTransportRuntimeMaxPendingTasks() {
        return transportRuntimeMaxPendingTasks;
    }

    public TransportRuntimeRole getRuntimeRole() {
        return runtimeRole;
    }

    public int getEventRuntimeMaxPendingTasks() {
        return eventRuntimeMaxPendingTasks;
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
