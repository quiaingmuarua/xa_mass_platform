package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.transport.polling.runtime.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.transport.polling.runtime.PollingTransportAdapterBootstrap;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportRegistrationResolver;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.socket.runtime.SocketTransportAdapterBootstrap;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
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

    private static final String API_MODE_UNSUPPORTED_MESSAGE =
            "API-based transport is not implemented yet. Use queue/polling transport or provide a real transport adapter.";
    private static final String DEFAULT_INPUT_QUEUE_NAME = "transport-input";
    private static final String DEFAULT_OUTPUT_QUEUE_NAME = "transport-output";

    private final MessageTransporterFactory.TransporterType transporterType;
    private final MessageQueue<String> inputQueue;
    private final MessageQueue<TransportOutboundMessage> outputQueue;
    private final WorkerPresenceIngress customWorkerPresenceIngress;
    private final Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory;
    private final WebSocketAdapterConfig bundledWebSocketAdapterConfig;
    private final SocketAdapterConfig bundledSocketAdapterConfig;
    private final List<WebSocketAdapterConfig> supplementalWebSocketAdapterConfigs;
    private final List<SocketAdapterConfig> supplementalSocketAdapterConfigs;
    private final WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private final Supplier<TransportDeliveryStore> deliveryStoreFactory;
    private final Supplier<TransportDeliveryCommandHandoff> deliveryCommandHandoffFactory;
    private final Supplier<RedisTransportResultIngressChannel> taskResultInboxFactory;
    private final Supplier<RedisTransportDeliveryFailureChannel> deliveryFailureInboxFactory;
    private final TransportAdapterBootstrap primaryTransportAdapterBootstrap;
    private final List<TransportAdapterBootstrap> supplementalTransportAdapterBootstraps;
    private final int maxDeliveryQueuedItems;
    private final int maxDeliveryItemsPerRoute;
    private final int transportRuntimeMaxPendingTasks;
    private final int eventRuntimeMaxPendingTasks;
    private final long eventHandlerTimeoutMillis;
    private final long endpointLeaseMillis;
    private final long adapterMailboxConsumerAvailabilityMillis;
    private final TransportRuntimeRole runtimeRole;

    private transient TransportRegistrationResolver registrationResolver;
    private transient TransportEndpointLeaseStore runtimeOwnedEndpointLeaseStore;

    public TransportRuntimeComposition(TransportConfig source) {
        this.transporterType = source.getTransporterType();
        this.inputQueue = source.getInputQueue();
        this.outputQueue = source.getOutputQueue();
        this.customWorkerPresenceIngress = source.getCustomWorkerPresenceIngress();
        this.endpointLeaseStoreFactory = source.endpointLeaseStoreFactory();
        this.bundledWebSocketAdapterConfig = new WebSocketAdapterConfig(source.getBundledWebSocketAdapterConfig());
        this.bundledSocketAdapterConfig = new SocketAdapterConfig(source.getBundledSocketAdapterConfig());
        this.supplementalWebSocketAdapterConfigs = source.getSupplementalWebSocketAdapterConfigs().stream()
                .map(WebSocketAdapterConfig::new)
                .toList();
        this.supplementalSocketAdapterConfigs = source.getSupplementalSocketAdapterConfigs().stream()
                .map(SocketAdapterConfig::new)
                .toList();
        this.workerTransportRuntimeFactory = source.getWorkerTransportRuntimeFactory();
        this.deliveryStoreFactory = source.deliveryStoreFactory();
        this.deliveryCommandHandoffFactory = source.deliveryCommandHandoffFactory();
        this.taskResultInboxFactory = source.taskResultInboxFactory();
        this.deliveryFailureInboxFactory = source.deliveryFailureInboxFactory();
        this.primaryTransportAdapterBootstrap = source.getPrimaryTransportAdapterBootstrap();
        this.supplementalTransportAdapterBootstraps = List.copyOf(source.getSupplementalTransportAdapterBootstraps());
        this.maxDeliveryQueuedItems = source.getMaxDeliveryQueuedItems();
        this.maxDeliveryItemsPerRoute = source.getMaxDeliveryItemsPerRoute();
        this.transportRuntimeMaxPendingTasks = source.getTransportRuntimeMaxPendingTasks();
        this.eventRuntimeMaxPendingTasks = source.getEventRuntimeMaxPendingTasks();
        this.eventHandlerTimeoutMillis = source.getEventHandlerTimeoutMillis();
        this.endpointLeaseMillis = source.getEndpointLeaseMillis();
        this.adapterMailboxConsumerAvailabilityMillis = source.getAdapterMailboxConsumerAvailabilityMillis();
        this.runtimeRole = source.getRuntimeRole();
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterConfig.isEnabled()
                || bundledWebSocketAdapterConfig.isServerEnabled()
                || bundledSocketAdapterConfig.isEnabled()
                || bundledSocketAdapterConfig.isServerEnabled()
                || hasAnyEnabledWebSocketConfig(supplementalWebSocketAdapterConfigs)
                || hasAnyEnabledSocketConfig(supplementalSocketAdapterConfigs)
                || primaryTransportAdapterBootstrap != null
                || !supplementalTransportAdapterBootstraps.isEmpty();
    }

    public MessageTransporter<String, TransportOutboundMessage> createMessageTransporter() {
        return switch (transporterType) {
            case QUEUE_BASED -> MessageTransporterFactory.createQueueBased(
                    resolveInputQueue(),
                    resolveOutputQueue()
            );
            case MULTI_LEVEL -> MessageTransporterFactory.createMultiLevel();
            case API_BASED -> throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
        };
    }

    public MessageTransporter<String, TransportOutboundMessage> createMessageTransporterIfConfigured() {
        return switch (transporterType) {
            case QUEUE_BASED -> (inputQueue != null || outputQueue != null || isEnabled())
                    ? MessageTransporterFactory.createQueueBased(resolveInputQueue(), resolveOutputQueue())
                    : null;
            case MULTI_LEVEL -> MessageTransporterFactory.createMultiLevel();
            case API_BASED -> null;
        };
    }

    public WebSocketAdapterConfig getBundledWebSocketAdapterConfig() {
        return new WebSocketAdapterConfig(bundledWebSocketAdapterConfig);
    }

    public SocketAdapterConfig getBundledSocketAdapterConfig() {
        return new SocketAdapterConfig(bundledSocketAdapterConfig);
    }

    public List<WebSocketAdapterConfig> getSupplementalWebSocketAdapterConfigs() {
        return supplementalWebSocketAdapterConfigs.stream()
                .map(WebSocketAdapterConfig::new)
                .toList();
    }

    public List<SocketAdapterConfig> getSupplementalSocketAdapterConfigs() {
        return supplementalSocketAdapterConfigs.stream()
                .map(SocketAdapterConfig::new)
                .toList();
    }

    public WorkerPresenceIngress resolveWorkerPresenceIngress() {
        if (customWorkerPresenceIngress != null) {
            return customWorkerPresenceIngress;
        }
        return null;
    }

    public WorkerTransportRuntimeFactory resolveWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory != null
                ? workerTransportRuntimeFactory
                : new DefaultWorkerTransportRuntimeFactory();
    }

    public TransportDeliveryStore resolveTransportDeliveryStore() {
        return deliveryStoreFactory != null
                ? deliveryStoreFactory.get()
                : new InMemoryTransportDeliveryStore(maxDeliveryQueuedItems, maxDeliveryItemsPerRoute);
    }

    public TransportDeliveryCommandHandoff resolveTransportDeliveryCommandHandoff(int defaultCapacity) {
        return deliveryCommandHandoffFactory != null
                ? deliveryCommandHandoffFactory.get()
                : new InMemoryTransportDeliveryCommandHandoff(defaultCapacity);
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

    public int getMaxDeliveryQueuedItems() {
        return maxDeliveryQueuedItems;
    }

    public long getEventHandlerTimeoutMillis() {
        return eventHandlerTimeoutMillis;
    }

    public int getMaxDeliveryItemsPerRoute() {
        return maxDeliveryItemsPerRoute;
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
                : new WebSocketTransportAdapterBootstrap(bundledWebSocketAdapterConfig);
    }

    TransportAdapterBootstrap resolveBundledSocketTransportAdapterBootstrap() {
        return new SocketTransportAdapterBootstrap(bundledSocketAdapterConfig);
    }

    List<TransportAdapterBootstrap> resolveSupplementalBundledWebSocketTransportAdapterBootstraps() {
        return supplementalWebSocketAdapterConfigs.stream()
                .map(WebSocketTransportAdapterBootstrap::new)
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
                    new PollingTransportAdapterBootstrap(),
                    true,
                    true
            ));
        }
        candidates.add(new BootstrapCandidate(
                resolvePrimaryTransportAdapterBootstrap(),
                primaryTransportAdapterBootstrap != null
                        || bundledWebSocketAdapterConfig.isEnabled()
                        || bundledWebSocketAdapterConfig.isServerEnabled(),
                primaryTransportAdapterBootstrap != null || bundledWebSocketAdapterConfig.isEnabled()
        ));
        candidates.add(new BootstrapCandidate(
                resolveBundledSocketTransportAdapterBootstrap(),
                bundledSocketAdapterConfig.isEnabled() || bundledSocketAdapterConfig.isServerEnabled(),
                bundledSocketAdapterConfig.isEnabled()
        ));
        for (WebSocketAdapterConfig config : supplementalWebSocketAdapterConfigs) {
            candidates.add(new BootstrapCandidate(
                    new WebSocketTransportAdapterBootstrap(config),
                    config.isEnabled() || config.isServerEnabled(),
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

    private static boolean hasAnyEnabledWebSocketConfig(List<WebSocketAdapterConfig> configs) {
        return configs.stream().anyMatch(config -> config.isEnabled() || config.isServerEnabled());
    }

    private static boolean hasAnyEnabledSocketConfig(List<SocketAdapterConfig> configs) {
        return configs.stream().anyMatch(config -> config.isEnabled() || config.isServerEnabled());
    }

    private MessageQueue<String> resolveInputQueue() {
        return inputQueue != null
                ? inputQueue
                : new InMemoryMessageQueue<>(DEFAULT_INPUT_QUEUE_NAME, String.class);
    }

    private MessageQueue<TransportOutboundMessage> resolveOutputQueue() {
        return outputQueue != null
                ? outputQueue
                : new InMemoryMessageQueue<>(DEFAULT_OUTPUT_QUEUE_NAME, TransportOutboundMessage.class);
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

