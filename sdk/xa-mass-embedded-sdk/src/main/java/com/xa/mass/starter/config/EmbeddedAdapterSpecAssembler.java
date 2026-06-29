package com.xa.mass.starter.config;

import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.starter.EmbeddedAdapterStarterDefaults;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SDK starter internal translation from typed adapter declarations to embedded
 * adapter runtime specs and object sidecars.
 */
final class EmbeddedAdapterSpecAssembler {

    private final TransportRuntimeComposition composition;

    private EmbeddedAdapterSpecAssembler(TransportRuntimeComposition composition) {
        this.composition = Objects.requireNonNull(composition, "composition");
    }

    static EmbeddedAdapterSpecAssembler from(TransportRuntimeComposition composition) {
        return new EmbeddedAdapterSpecAssembler(composition);
    }

    boolean isUserEnabled() {
        return composition.getBundledWebSocketAdapterConfig().isEnabled()
                || composition.getBundledSocketAdapterConfig().isEnabled()
                || hasAnyEnabledWebSocketAssembly(composition.getSupplementalWebSocketAdapterAssemblies())
                || hasAnyEnabledSocketConfig(composition.getSupplementalSocketAdapterConfigs());
    }

    List<EmbeddedAdapterRuntimeSpec> specs() {
        List<EmbeddedAdapterRuntimeSpec> specs = new ArrayList<>();
        specs.add(new EmbeddedAdapterRuntimeSpec(
                EmbeddedAdapterStarterDefaults.TYPE_POLLING,
                EmbeddedAdapterStarterDefaults.DEFAULT_POLLING_ADAPTER_ID,
                EmbeddedAdapterStarterDefaults.DEFAULT_POLLING_ADAPTER_ID,
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                Map.of()
        ));

        WebSocketAdapterConfig bundledWebSocketConfig = composition.getBundledWebSocketAdapterConfig();
        if (bundledWebSocketConfig.isEnabled()) {
            specs.add(webSocketSpec(bundledWebSocketConfig));
        }

        SocketAdapterConfig bundledSocketConfig = composition.getBundledSocketAdapterConfig();
        if (bundledSocketConfig.isEnabled()) {
            specs.add(socketSpec(bundledSocketConfig));
        }

        for (TransportConfig.WebSocketAdapterAssembly assembly : composition.getSupplementalWebSocketAdapterAssemblies()) {
            WebSocketAdapterConfig config = assembly.config();
            if (config.isEnabled()) {
                specs.add(webSocketSpec(config));
            }
        }
        for (SocketAdapterConfig config : composition.getSupplementalSocketAdapterConfigs()) {
            if (config.isEnabled()) {
                specs.add(socketSpec(config));
            }
        }
        validateUniqueAdapterIds(specs);
        return List.copyOf(specs);
    }

    Map<String, TransportServerFactory<WebSocketServerFactoryContext>> webSocketServerFactoriesByAdapterId() {
        List<EmbeddedAdapterRuntimeSpec> specs = specs();
        Set<String> configuredAdapterIds = adapterIds(specs);
        LinkedHashMap<String, TransportServerFactory<WebSocketServerFactoryContext>> factories = new LinkedHashMap<>();

        WebSocketAdapterConfig bundledConfig = composition.getBundledWebSocketAdapterConfig();
        TransportServerFactory<WebSocketServerFactoryContext> bundledFactory =
                composition.getBundledWebSocketTransportServerFactory();
        addWebSocketSidecar(
                factories,
                configuredAdapterIds,
                bundledConfig,
                bundledFactory
        );

        for (TransportConfig.WebSocketAdapterAssembly assembly : composition.getSupplementalWebSocketAdapterAssemblies()) {
            addWebSocketSidecar(
                    factories,
                    configuredAdapterIds,
                    assembly.config(),
                    assembly.transportServerFactory()
            );
        }
        return Map.copyOf(factories);
    }

    private static void addWebSocketSidecar(
            Map<String, TransportServerFactory<WebSocketServerFactoryContext>> factories,
            Set<String> configuredAdapterIds,
            WebSocketAdapterConfig config,
            TransportServerFactory<WebSocketServerFactoryContext> factory) {
        if (factory == null) {
            return;
        }
        String adapterId = normalizeAdapterId(config.getAdapterId());
        if (!config.isEnabled()) {
            throw new IllegalStateException("WebSocket server factory configured for disabled adapterId: " + adapterId);
        }
        if (!configuredAdapterIds.contains(adapterId)) {
            throw new IllegalStateException("WebSocket server factory configured for adapterId '" + adapterId
                    + "' but no matching embedded adapter spec exists");
        }
        TransportServerFactory<WebSocketServerFactoryContext> existing = factories.putIfAbsent(adapterId, factory);
        if (existing != null) {
            throw new IllegalStateException("Duplicate WebSocket server factory configured for adapterId: " + adapterId);
        }
    }

    private static EmbeddedAdapterRuntimeSpec webSocketSpec(WebSocketAdapterConfig config) {
        WebSocketAdapterConfig snapshot = new WebSocketAdapterConfig(config);
        return new EmbeddedAdapterRuntimeSpec(
                EmbeddedAdapterStarterDefaults.TYPE_WEBSOCKET,
                snapshot.getAdapterId(),
                snapshot.getAdapterId(),
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                EmbeddedAdapterStarterDefaults.webSocketOptions(snapshot)
        );
    }

    private static EmbeddedAdapterRuntimeSpec socketSpec(SocketAdapterConfig config) {
        SocketAdapterConfig snapshot = new SocketAdapterConfig(config);
        return new EmbeddedAdapterRuntimeSpec(
                EmbeddedAdapterStarterDefaults.TYPE_SOCKET,
                snapshot.getAdapterId(),
                snapshot.getAdapterId(),
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                EmbeddedAdapterStarterDefaults.socketOptions(snapshot)
        );
    }

    private static Set<String> adapterIds(List<EmbeddedAdapterRuntimeSpec> specs) {
        Set<String> adapterIds = new LinkedHashSet<>();
        for (EmbeddedAdapterRuntimeSpec spec : specs) {
            adapterIds.add(normalizeAdapterId(spec.adapterId()));
        }
        return adapterIds;
    }

    private static void validateUniqueAdapterIds(List<EmbeddedAdapterRuntimeSpec> specs) {
        Set<String> adapterIds = new LinkedHashSet<>();
        for (EmbeddedAdapterRuntimeSpec spec : specs) {
            String normalized = normalizeAdapterId(spec.adapterId());
            if (!adapterIds.add(normalized)) {
                throw new IllegalStateException("Duplicate transport adapterId configured: " + normalized);
            }
        }
    }

    private static boolean hasAnyEnabledWebSocketAssembly(List<TransportConfig.WebSocketAdapterAssembly> assemblies) {
        return assemblies.stream().anyMatch(assembly -> assembly.config().isEnabled());
    }

    private static boolean hasAnyEnabledSocketConfig(List<SocketAdapterConfig> configs) {
        return configs.stream().anyMatch(SocketAdapterConfig::isEnabled);
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
