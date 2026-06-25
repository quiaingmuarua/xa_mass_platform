package com.xa.mass.starter.config;

import com.xa.mass.transport.polling.delivery.InMemoryPollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.runtime.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.transport.polling.runtime.PollingTransportAdapterBootstrap;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportRegistrationResolver;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.socket.runtime.SocketTransportAdapterBootstrap;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;
import com.xa.mass.transport.websocket.runtime.WebSocketTransportAdapterBootstrap;

import java.util.ArrayList;
import java.util.List;
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
    private final WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private final Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory;
    private final Supplier<TransportDispatchHandoff> dispatchHandoffFactory;
    private final Supplier<RedisTransportResultIngressChannel> taskResultInboxFactory;
    private final Supplier<RedisTransportDeliveryFailureChannel> deliveryFailureInboxFactory;
    private final TransportAdapterBootstrap primaryTransportAdapterBootstrap;
    private final List<TransportAdapterBootstrap> supplementalTransportAdapterBootstraps;
    private final int maxPollingPendingDeliveryItems;
    private final int maxPollingPendingDeliveryItemsPerWorker;
    private final int transportRuntimeMaxPendingTasks;
    private final int eventRuntimeMaxPendingTasks;
    private final long eventHandlerTimeoutMillis;
    private final long endpointLeaseMillis;
    private final long adapterMailboxConsumerAvailabilityMillis;
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
        this.workerTransportRuntimeFactory = source.getWorkerTransportRuntimeFactory();
        this.pollingPendingDeliveryBufferFactory = source.pollingPendingDeliveryBufferFactory();
        this.dispatchHandoffFactory = source.getDispatchHandoffFactory();
        this.taskResultInboxFactory = source.taskResultInboxFactory();
        this.deliveryFailureInboxFactory = source.deliveryFailureInboxFactory();
        this.primaryTransportAdapterBootstrap = source.getPrimaryTransportAdapterBootstrap();
        this.supplementalTransportAdapterBootstraps = List.copyOf(source.getSupplementalTransportAdapterBootstraps());
        this.maxPollingPendingDeliveryItems = source.getMaxPollingPendingDeliveryItems();
        this.maxPollingPendingDeliveryItemsPerWorker = source.getMaxPollingPendingDeliveryItemsPerWorker();
        this.transportRuntimeMaxPendingTasks = source.getTransportRuntimeMaxPendingTasks();
        this.eventRuntimeMaxPendingTasks = source.getEventRuntimeMaxPendingTasks();
        this.eventHandlerTimeoutMillis = source.getEventHandlerTimeoutMillis();
        this.endpointLeaseMillis = source.getEndpointLeaseMillis();
        this.adapterMailboxConsumerAvailabilityMillis = source.getAdapterMailboxConsumerAvailabilityMillis();
        this.runtimeRole = source.getRuntimeRole();
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterConfig.isEnabled()
                || bundledSocketAdapterConfig.isEnabled()
                || bundledSocketAdapterConfig.isServerEnabled()
                || hasAnyEnabledWebSocketAssembly(supplementalWebSocketAdapterAssemblies)
                || hasAnyEnabledSocketConfig(supplementalSocketAdapterConfigs)
                || primaryTransportAdapterBootstrap != null
                || !supplementalTransportAdapterBootstraps.isEmpty();
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

    public WorkerTransportRuntimeFactory resolveWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory != null
                ? workerTransportRuntimeFactory
                : new DefaultWorkerTransportRuntimeFactory();
    }

    public Supplier<PollingPendingDeliveryBuffer> resolvePollingPendingDeliveryBufferFactory() {
        return pollingPendingDeliveryBufferFactory != null
                ? pollingPendingDeliveryBufferFactory
                : () -> new InMemoryPollingPendingDeliveryBuffer(
                        maxPollingPendingDeliveryItems,
                        maxPollingPendingDeliveryItemsPerWorker
                );
    }

    public TransportDispatchHandoff resolveTransportDispatchHandoff(int defaultCapacity) {
        return dispatchHandoffFactory != null
                ? dispatchHandoffFactory.get()
                : new InMemoryTransportDispatchHandoff(defaultCapacity);
    }

    public RedisTransportResultIngressChannel resolveTaskResultInbox() {
        if (taskResultInboxFactory == null) {
            throw new IllegalStateException("Task result inbox is not configured for split transport runtime");
        }
        return taskResultInboxFactory.get();
    }

    public RedisTransportDeliveryFailureChannel resolveDeliveryFailureInbox() {
        if (deliveryFailureInboxFactory == null) {
            throw new IllegalStateException("Delivery failure inbox is not configured for split transport runtime");
        }
        return deliveryFailureInboxFactory.get();
    }

    public TransportEndpointLeaseStore resolveTransportEndpointLeaseStore() {
        if (runtimeOwnedEndpointLeaseStore == null) {
            runtimeOwnedEndpointLeaseStore = endpointLeaseStoreFactory != null
                    ? endpointLeaseStoreFactory.get()
                    : new InMemoryTransportEndpointLeaseStore(endpointLeaseMillis);
        }
        return runtimeOwnedEndpointLeaseStore;
    }

    public java.util.Map<String, String> resolveAdapterTransportHintsById() {
        java.util.LinkedHashMap<String, String> hintsByAdapterId = new java.util.LinkedHashMap<>();
        for (TransportAdapterDescriptor descriptor : resolveRegistrationDescriptors()) {
            if (descriptor != null && descriptor.getAdapterId() != null && descriptor.getTransportHint() != null) {
                hintsByAdapterId.put(descriptor.getAdapterId(), descriptor.getTransportHint());
            }
        }
        return java.util.Map.copyOf(hintsByAdapterId);
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        if (!hasRegistrationDescriptors()) {
            if (requestedAdapterId != null && !requestedAdapterId.isBlank()) {
                return requestedAdapterId.trim().toLowerCase(java.util.Locale.ROOT);
            }
            throw new IllegalStateException(
                    "transport registration metadata is unavailable; cannot infer adapter binding before runtime start");
        }
        return registrationResolver().resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public List<TransportAdapterBootstrap> resolveTransportAdapterBootstraps() {
        List<TransportAdapterBootstrap> bootstraps = bootstrapCandidates().stream()
                .filter(BootstrapCandidate::runtimeIncluded)
                .map(BootstrapCandidate::bootstrap)
                .toList();
        validateUniqueAdapterIds(bootstraps);
        return bootstraps;
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

    public long getAdapterMailboxConsumerAvailabilityMillis() {
        return adapterMailboxConsumerAvailabilityMillis;
    }

    public int getEventRuntimeMaxPendingTasks() {
        return eventRuntimeMaxPendingTasks;
    }

    TransportAdapterBootstrap resolvePrimaryTransportAdapterBootstrap() {
        return primaryTransportAdapterBootstrap != null
                ? primaryTransportAdapterBootstrap
                : new WebSocketTransportAdapterBootstrap(
                        bundledWebSocketAdapterConfig,
                        bundledWebSocketTransportServerFactory
                );
    }

    TransportAdapterBootstrap resolveBundledSocketTransportAdapterBootstrap() {
        return new SocketTransportAdapterBootstrap(bundledSocketAdapterConfig);
    }

    List<TransportAdapterBootstrap> resolveSupplementalBundledWebSocketTransportAdapterBootstraps() {
        return supplementalWebSocketAdapterAssemblies.stream()
                .map(assembly -> new WebSocketTransportAdapterBootstrap(
                        assembly.config(),
                        assembly.transportServerFactory()
                ))
                .map(bootstrap -> (TransportAdapterBootstrap) bootstrap)
                .toList();
    }

    List<TransportAdapterBootstrap> resolveSupplementalBundledSocketTransportAdapterBootstraps() {
        return supplementalSocketAdapterConfigs.stream()
                .map(SocketTransportAdapterBootstrap::new)
                .map(bootstrap -> (TransportAdapterBootstrap) bootstrap)
                .toList();
    }

    private TransportRegistrationResolver registrationResolver() {
        if (registrationResolver == null) {
            registrationResolver = new TransportRegistrationResolver(resolveRegistrationDescriptors());
        }
        return registrationResolver;
    }

    private List<TransportAdapterDescriptor> resolveRegistrationDescriptors() {
        List<TransportAdapterDescriptor> descriptors = new ArrayList<>();
        WorkerTransportRuntimeFactory runtimeFactory = workerTransportRuntimeFactory;
        if (runtimeFactory == null) {
            runtimeFactory = resolveWorkerTransportRuntimeFactory();
        }
        List<TransportAdapterDescriptor> runtimeDescriptors = runtimeFactory.registrationDescriptors();
        if (runtimeDescriptors != null && !runtimeDescriptors.isEmpty()) {
            descriptors.addAll(runtimeDescriptors);
        }
        for (BootstrapCandidate candidate : bootstrapCandidates()) {
            if (!candidate.registrationIncluded()) {
                continue;
            }
            TransportAdapterDescriptor descriptor = candidate.bootstrap().descriptor();
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        validateUniqueDescriptorIds(descriptors);
        return List.copyOf(descriptors);
    }

    private boolean hasRegistrationDescriptors() {
        return !resolveRegistrationDescriptors().isEmpty();
    }

    private List<BootstrapCandidate> bootstrapCandidates() {
        List<BootstrapCandidate> candidates = new ArrayList<>();
        if (workerTransportRuntimeFactory == null) {
            candidates.add(new BootstrapCandidate(
                    new PollingTransportAdapterBootstrap(
                            PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID,
                            resolvePollingPendingDeliveryBufferFactory()
                    ),
                    true,
                    true
            ));
        }
        candidates.add(new BootstrapCandidate(
                resolvePrimaryTransportAdapterBootstrap(),
                primaryTransportAdapterBootstrap != null || bundledWebSocketAdapterConfig.isEnabled(),
                primaryTransportAdapterBootstrap != null || bundledWebSocketAdapterConfig.isEnabled()
        ));
        candidates.add(new BootstrapCandidate(
                resolveBundledSocketTransportAdapterBootstrap(),
                bundledSocketAdapterConfig.isEnabled() || bundledSocketAdapterConfig.isServerEnabled(),
                bundledSocketAdapterConfig.isEnabled()
        ));
        for (TransportConfig.WebSocketAdapterAssembly assembly : supplementalWebSocketAdapterAssemblies) {
            WebSocketAdapterConfig config = assembly.config();
            candidates.add(new BootstrapCandidate(
                    new WebSocketTransportAdapterBootstrap(config, assembly.transportServerFactory()),
                    config.isEnabled(),
                    config.isEnabled()
            ));
        }
        for (SocketAdapterConfig config : supplementalSocketAdapterConfigs) {
            candidates.add(new BootstrapCandidate(
                    new SocketTransportAdapterBootstrap(config),
                    config.isEnabled() || config.isServerEnabled(),
                    config.isEnabled()
            ));
        }
        for (TransportAdapterBootstrap bootstrap : supplementalTransportAdapterBootstraps) {
            candidates.add(new BootstrapCandidate(bootstrap, true, true));
        }
        return List.copyOf(candidates);
    }

    private static boolean hasAnyEnabledWebSocketAssembly(List<TransportConfig.WebSocketAdapterAssembly> assemblies) {
        return assemblies.stream().anyMatch(assembly -> assembly.config().isEnabled());
    }

    private static boolean hasAnyEnabledSocketConfig(List<SocketAdapterConfig> configs) {
        return configs.stream().anyMatch(config -> config.isEnabled() || config.isServerEnabled());
    }

    private static void validateUniqueAdapterIds(List<TransportAdapterBootstrap> bootstraps) {
        List<TransportAdapterDescriptor> descriptors = new ArrayList<>();
        for (TransportAdapterBootstrap bootstrap : bootstraps) {
            TransportAdapterDescriptor descriptor = bootstrap.descriptor();
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        validateUniqueDescriptorIds(descriptors);
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

    private record BootstrapCandidate(TransportAdapterBootstrap bootstrap,
                                      boolean runtimeIncluded,
                                      boolean registrationIncluded) {
    }
}
