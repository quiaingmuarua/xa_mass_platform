package com.xa.mass.starter.config;

import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.polling.delivery.InMemoryPollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportRegistrationResolver;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.starter.EmbeddedAdapterStarterDefaults;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fixed runtime composition snapshot derived from {@link TransportConfig}.
 *
 * <p>This keeps runtime-owned resolver state out of
 * {@link com.xa.mass.starter.MassApplication} so the application manages only
 * assembled transport components rather than a live config object.
 */
public class TransportRuntimeComposition {

    private final Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory;
    private final WebSocketAdapterConfig bundledWebSocketAdapterConfig;
    private final TransportServerFactory<WebSocketServerFactoryContext> bundledWebSocketTransportServerFactory;
    private final SocketAdapterConfig bundledSocketAdapterConfig;
    private final List<TransportConfig.WebSocketAdapterAssembly> supplementalWebSocketAdapterAssemblies;
    private final List<SocketAdapterConfig> supplementalSocketAdapterConfigs;
    private final Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory;
    private final Supplier<TransportDispatchQueue> dispatchQueueFactory;
    private final Supplier<RedisTransportResultIngressChannel> taskResultIngressQueueFactory;
    private final int maxPollingPendingDeliveryItems;
    private final int maxPollingPendingDeliveryItemsPerWorker;
    private final int transportRuntimeMaxPendingTasks;
    private final int eventRuntimeMaxPendingTasks;
    private final long eventHandlerTimeoutMillis;
    private final long endpointLeaseMillis;
    private final TransportRuntimeRole runtimeRole;

    private transient TransportRegistrationResolver registrationResolver;
    private transient TransportEndpointLeaseStore runtimeOwnedEndpointLeaseStore;

    public TransportRuntimeComposition(TransportConfig source) {
        this.endpointLeaseStoreFactory = source.endpointLeaseStoreFactory();
        this.bundledWebSocketAdapterConfig = new WebSocketAdapterConfig(source.getBundledWebSocketAdapterConfig());
        this.bundledWebSocketTransportServerFactory = source.getBundledWebSocketTransportServerFactory();
        this.bundledSocketAdapterConfig = new SocketAdapterConfig(source.getBundledSocketAdapterConfig());
        this.supplementalWebSocketAdapterAssemblies = source.getSupplementalWebSocketAdapterAssemblies().stream()
                .map(assembly -> new TransportConfig.WebSocketAdapterAssembly(
                        assembly.config(),
                        assembly.transportServerFactory()
                ))
                .toList();
        this.supplementalSocketAdapterConfigs = source.getSupplementalSocketAdapterConfigs().stream()
                .map(SocketAdapterConfig::new)
                .toList();
        this.pollingPendingDeliveryBufferFactory = source.pollingPendingDeliveryBufferFactory();
        this.dispatchQueueFactory = source.getDispatchQueueFactory();
        this.taskResultIngressQueueFactory = source.taskResultIngressQueueFactory();
        this.maxPollingPendingDeliveryItems = source.getMaxPollingPendingDeliveryItems();
        this.maxPollingPendingDeliveryItemsPerWorker = source.getMaxPollingPendingDeliveryItemsPerWorker();
        this.transportRuntimeMaxPendingTasks = source.getTransportRuntimeMaxPendingTasks();
        this.eventRuntimeMaxPendingTasks = source.getEventRuntimeMaxPendingTasks();
        this.eventHandlerTimeoutMillis = source.getEventHandlerTimeoutMillis();
        this.endpointLeaseMillis = source.getEndpointLeaseMillis();
        this.runtimeRole = source.getRuntimeRole();
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterConfig.isEnabled()
                || bundledSocketAdapterConfig.isEnabled()
                || hasAnyEnabledWebSocketAssembly(supplementalWebSocketAdapterAssemblies)
                || hasAnyEnabledSocketConfig(supplementalSocketAdapterConfigs);
    }

    public WebSocketAdapterConfig getBundledWebSocketAdapterConfig() {
        return new WebSocketAdapterConfig(bundledWebSocketAdapterConfig);
    }

    public SocketAdapterConfig getBundledSocketAdapterConfig() {
        return new SocketAdapterConfig(bundledSocketAdapterConfig);
    }

    public List<WebSocketAdapterConfig> getSupplementalWebSocketAdapterConfigs() {
        return supplementalWebSocketAdapterAssemblies.stream()
                .map(TransportConfig.WebSocketAdapterAssembly::config)
                .toList();
    }

    public List<SocketAdapterConfig> getSupplementalSocketAdapterConfigs() {
        return supplementalSocketAdapterConfigs.stream()
                .map(SocketAdapterConfig::new)
                .toList();
    }

    public Supplier<PollingPendingDeliveryBuffer> resolvePollingPendingDeliveryBufferFactory() {
        return pollingPendingDeliveryBufferFactory != null
                ? pollingPendingDeliveryBufferFactory
                : () -> new InMemoryPollingPendingDeliveryBuffer(
                        maxPollingPendingDeliveryItems,
                        maxPollingPendingDeliveryItemsPerWorker
                );
    }

    public TransportDispatchQueue resolveTransportDispatchQueue(int defaultCapacity) {
        return dispatchQueueFactory != null
                ? dispatchQueueFactory.get()
                : new InMemoryTransportDispatchHandoff(defaultCapacity);
    }

    public RedisTransportResultIngressChannel resolveTaskResultIngressQueue() {
        if (taskResultIngressQueueFactory == null) {
            throw new IllegalStateException("Task result ingress queue is not configured for split transport runtime");
        }
        return taskResultIngressQueueFactory.get();
    }

    public TransportEndpointLeaseStore resolveTransportEndpointLeaseStore() {
        if (runtimeOwnedEndpointLeaseStore == null) {
            runtimeOwnedEndpointLeaseStore = endpointLeaseStoreFactory != null
                    ? endpointLeaseStoreFactory.get()
                    : new InMemoryTransportEndpointLeaseStore(endpointLeaseMillis);
        }
        return runtimeOwnedEndpointLeaseStore;
    }

    public List<EmbeddedAdapterRuntimeSpec> resolveEmbeddedAdapterRuntimeSpecs() {
        List<EmbeddedAdapterRuntimeSpec> specs = new ArrayList<>();
        specs.add(new EmbeddedAdapterRuntimeSpec(
                EmbeddedAdapterStarterDefaults.TYPE_POLLING,
                EmbeddedAdapterStarterDefaults.DEFAULT_POLLING_ADAPTER_ID,
                EmbeddedAdapterStarterDefaults.DEFAULT_POLLING_ADAPTER_ID,
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                Map.of()
        ));
        if (bundledWebSocketAdapterConfig.isEnabled()) {
            specs.add(webSocketSpec(bundledWebSocketAdapterConfig));
        }
        if (bundledSocketAdapterConfig.isEnabled()) {
            specs.add(socketSpec(bundledSocketAdapterConfig));
        }
        for (TransportConfig.WebSocketAdapterAssembly assembly : supplementalWebSocketAdapterAssemblies) {
            WebSocketAdapterConfig config = assembly.config();
            if (config.isEnabled()) {
                specs.add(webSocketSpec(config));
            }
        }
        for (SocketAdapterConfig config : supplementalSocketAdapterConfigs) {
            if (config.isEnabled()) {
                specs.add(socketSpec(config));
            }
        }
        validateUniqueAdapterIds(specs);
        return List.copyOf(specs);
    }

    public Map<String, String> resolveAdapterTransportHintsById() {
        java.util.LinkedHashMap<String, String> hintsByAdapterId = new java.util.LinkedHashMap<>();
        for (TransportAdapterDescriptor descriptor : resolveRegistrationDescriptors()) {
            if (descriptor != null && descriptor.getAdapterId() != null && descriptor.getTransportHint() != null) {
                hintsByAdapterId.put(descriptor.getAdapterId(), descriptor.getTransportHint());
            }
        }
        return java.util.Map.copyOf(hintsByAdapterId);
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        return registrationResolver().resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public int getMaxPollingPendingDeliveryItems() {
        return maxPollingPendingDeliveryItems;
    }

    public long getEventHandlerTimeoutMillis() {
        return eventHandlerTimeoutMillis;
    }

    public int getMaxPollingPendingDeliveryItemsPerWorker() {
        return maxPollingPendingDeliveryItemsPerWorker;
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

    private TransportRegistrationResolver registrationResolver() {
        if (registrationResolver == null) {
            registrationResolver = new TransportRegistrationResolver(resolveRegistrationDescriptors());
        }
        return registrationResolver;
    }

    private List<TransportAdapterDescriptor> resolveRegistrationDescriptors() {
        List<TransportAdapterDescriptor> descriptors = new ArrayList<>();
        for (EmbeddedAdapterRuntimeSpec spec : resolveEmbeddedAdapterRuntimeSpecs()) {
            descriptors.add(new TransportAdapterDescriptor(
                    spec.adapterId(),
                    EmbeddedAdapterStarterDefaults.transportHintForType(spec.type())
            ));
        }
        validateUniqueDescriptorIds(descriptors);
        return List.copyOf(descriptors);
    }

    public Map<String, TransportServerFactory<WebSocketServerFactoryContext>> resolveWebSocketServerFactoriesByAdapterId() {
        Map<String, TransportServerFactory<WebSocketServerFactoryContext>> factories = new LinkedHashMap<>();
        if (bundledWebSocketTransportServerFactory != null) {
            factories.put(bundledWebSocketAdapterConfig.getAdapterId(), bundledWebSocketTransportServerFactory);
        }
        for (TransportConfig.WebSocketAdapterAssembly assembly : supplementalWebSocketAdapterAssemblies) {
            TransportServerFactory<WebSocketServerFactoryContext> factory = assembly.transportServerFactory();
            if (factory != null) {
                factories.put(assembly.config().getAdapterId(), factory);
            }
        }
        return Map.copyOf(factories);
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

    private static boolean hasAnyEnabledWebSocketAssembly(List<TransportConfig.WebSocketAdapterAssembly> assemblies) {
        return assemblies.stream().anyMatch(assembly -> assembly.config().isEnabled());
    }

    private static boolean hasAnyEnabledSocketConfig(List<SocketAdapterConfig> configs) {
        return configs.stream().anyMatch(SocketAdapterConfig::isEnabled);
    }

    private static void validateUniqueAdapterIds(List<EmbeddedAdapterRuntimeSpec> specs) {
        java.util.Set<String> adapterIds = new java.util.LinkedHashSet<>();
        for (EmbeddedAdapterRuntimeSpec spec : specs) {
            String normalized = spec.adapterId().trim().toLowerCase(java.util.Locale.ROOT);
            if (!adapterIds.add(normalized)) {
                throw new IllegalStateException("Duplicate transport adapterId configured: " + normalized);
            }
        }
    }

    private static void validateUniqueDescriptorIds(List<TransportAdapterDescriptor> descriptors) {
        java.util.Set<String> adapterIds = new java.util.LinkedHashSet<>();
        for (TransportAdapterDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.getAdapterId() == null || descriptor.getAdapterId().isBlank()) {
                continue;
            }
            String normalized = descriptor.getAdapterId().trim().toLowerCase(java.util.Locale.ROOT);
            if (!adapterIds.add(normalized)) {
                throw new IllegalStateException("Duplicate transport adapterId configured: " + normalized);
            }
        }
    }
}
