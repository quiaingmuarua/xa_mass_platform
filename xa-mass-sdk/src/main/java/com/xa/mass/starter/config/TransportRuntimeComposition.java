package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.TransportAdapterDescriptor;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportRegistrationResolver;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.starter.worker.PollingWorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.socket.runtime.SocketTransportAdapterBootstrap;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketTransportAdapterBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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

    private final MessageTransporterFactory.TransporterType transporterType;
    private final MessageQueue<String> inputQueue;
    private final MessageQueue<WorkerTransportMessage> outputQueue;
    private final WorkerSystemEventChannel customSystemEventChannel;
    private final WorkerEndpointRegistry workerEndpointRegistry;
    private final Supplier<WorkerEndpointRegistry> endpointRegistryFactory;
    private final Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver;
    private final WebSocketAdapterConfig defaultWebSocketAdapterConfig;
    private final SocketAdapterConfig defaultSocketAdapterConfig;
    private final WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private final TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap;
    private final List<TransportAdapterBootstrap<WorkerTransportMessage>> additionalTransportAdapterBootstraps;

    private transient WorkerEndpointRegistry runtimeOwnedEndpointRegistry;
    private transient TransportRegistrationResolver compatibilityRegistrationResolver;

    public TransportRuntimeComposition(TransportConfig source) {
        this.transporterType = source.getTransporterType();
        this.inputQueue = source.getInputQueue();
        this.outputQueue = source.getOutputQueue();
        this.customSystemEventChannel = source.getCustomSystemEventChannel();
        this.workerEndpointRegistry = source.getWorkerEndpointRegistry();
        this.endpointRegistryFactory = source.endpointRegistryFactory();
        this.systemEventChannelResolver = source.systemEventChannelResolver();
        this.defaultWebSocketAdapterConfig = new WebSocketAdapterConfig(source.getDefaultWebSocketAdapterConfig());
        this.defaultSocketAdapterConfig = new SocketAdapterConfig(source.getDefaultSocketAdapterConfig());
        this.workerTransportRuntimeFactory = source.getWorkerTransportRuntimeFactory();
        this.transportAdapterBootstrap = source.getTransportAdapterBootstrap();
        this.additionalTransportAdapterBootstraps = List.copyOf(source.getAdditionalTransportAdapterBootstraps());
    }

    public boolean isEnabled() {
        return defaultWebSocketAdapterConfig.isEnabled()
                || defaultWebSocketAdapterConfig.isServerEnabled()
                || defaultSocketAdapterConfig.isEnabled()
                || defaultSocketAdapterConfig.isServerEnabled()
                || transportAdapterBootstrap != null
                || !additionalTransportAdapterBootstraps.isEmpty();
    }

    /**
     * @deprecated Prefer explicit adapter-owned runtime inspection through
     * adapter configs. This transport-global helper reflects only the bundled
     * default WebSocket adapter and is compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public boolean isTransportServerEnabled() {
        return defaultWebSocketAdapterConfig.isServerEnabled();
    }

    /**
     * @deprecated Prefer explicit adapter-owned runtime inspection through
     * adapter configs. This transport-global helper reflects only the bundled
     * default WebSocket adapter and is compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public int getTransportServerPort() {
        return defaultWebSocketAdapterConfig.getServerPort();
    }

    /**
     * @deprecated Prefer explicit adapter-owned runtime inspection through
     * adapter configs. This transport-global helper reflects only the bundled
     * default WebSocket adapter and is compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public int getMaxConnections() {
        return defaultWebSocketAdapterConfig.getMaxConnections();
    }

    /**
     * @deprecated Prefer explicit adapter-owned runtime inspection through
     * adapter configs. This transport-global helper reflects only the bundled
     * default WebSocket adapter and is compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public String getTransportEndpointPath() {
        return defaultWebSocketAdapterConfig.getEndpointPath();
    }

    public MessageTransporter<String, WorkerTransportMessage> createMessageTransporter() {
        return switch (transporterType) {
            case QUEUE_BASED -> {
                if (inputQueue == null || outputQueue == null) {
                    throw new IllegalStateException("QUEUE_BASED transporter requires both inputQueue and outputQueue");
                }
                yield MessageTransporterFactory.createQueueBased(inputQueue, outputQueue);
            }
            case MULTI_LEVEL -> MessageTransporterFactory.createMultiLevel();
            case API_BASED -> throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
        };
    }

    public MessageTransporter<String, WorkerTransportMessage> createMessageTransporterIfConfigured() {
        return switch (transporterType) {
            case QUEUE_BASED -> (inputQueue != null && outputQueue != null)
                    ? MessageTransporterFactory.createQueueBased(inputQueue, outputQueue)
                    : null;
            case MULTI_LEVEL -> MessageTransporterFactory.createMultiLevel();
            case API_BASED -> null;
        };
    }

    public WebSocketAdapterConfig getDefaultWebSocketAdapterConfig() {
        return new WebSocketAdapterConfig(defaultWebSocketAdapterConfig);
    }

    public SocketAdapterConfig getDefaultSocketAdapterConfig() {
        return new SocketAdapterConfig(defaultSocketAdapterConfig);
    }

    public WorkerEndpointRegistry resolveWorkerEndpointRegistry() {
        if (workerEndpointRegistry != null) {
            return workerEndpointRegistry;
        }
        if (runtimeOwnedEndpointRegistry == null) {
            if (endpointRegistryFactory == null) {
                throw new IllegalStateException("Transport endpoint registry factory is not configured");
            }
            runtimeOwnedEndpointRegistry = endpointRegistryFactory.get();
        }
        return runtimeOwnedEndpointRegistry;
    }

    public WorkerSystemEventChannel resolveSystemEventChannel() {
        if (customSystemEventChannel != null) {
            return customSystemEventChannel;
        }
        WorkerEndpointRegistry endpointRegistry = workerEndpointRegistry;
        if (endpointRegistry == null) {
            endpointRegistry = resolveWorkerEndpointRegistry();
        }
        if (systemEventChannelResolver == null) {
            throw new IllegalStateException("Transport system-event resolver is not configured");
        }
        return systemEventChannelResolver.apply(endpointRegistry);
    }

    public WorkerTransportRuntimeFactory resolveWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory != null
                ? workerTransportRuntimeFactory
                : new DefaultWorkerTransportRuntimeFactory();
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        if (!usesDefaultWorkerTransportRuntimeFactory()) {
            if (requestedAdapterId != null && !requestedAdapterId.isBlank()) {
                return requestedAdapterId.trim().toLowerCase(java.util.Locale.ROOT);
            }
            throw new IllegalStateException(
                    "worker adapterId must be set before runtime start when a custom worker transport runtime factory is configured");
        }
        return compatibilityRegistrationResolver().resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public List<TransportAdapterBootstrap<WorkerTransportMessage>> resolveTransportAdapterBootstraps() {
        List<TransportAdapterBootstrap<WorkerTransportMessage>> bootstraps = new ArrayList<>();
        bootstraps.add(resolvePrimaryTransportAdapterBootstrap());
        bootstraps.add(resolveDefaultSocketTransportAdapterBootstrap());
        bootstraps.addAll(additionalTransportAdapterBootstraps);
        return List.copyOf(bootstraps);
    }

    TransportAdapterBootstrap<WorkerTransportMessage> resolvePrimaryTransportAdapterBootstrap() {
        return transportAdapterBootstrap != null
                ? transportAdapterBootstrap
                : new WebSocketTransportAdapterBootstrap(defaultWebSocketAdapterConfig);
    }

    TransportAdapterBootstrap<WorkerTransportMessage> resolveDefaultSocketTransportAdapterBootstrap() {
        return new SocketTransportAdapterBootstrap(defaultSocketAdapterConfig);
    }

    private TransportRegistrationResolver compatibilityRegistrationResolver() {
        if (compatibilityRegistrationResolver == null) {
            compatibilityRegistrationResolver = new TransportRegistrationResolver(resolveRegistrationDescriptors());
        }
        return compatibilityRegistrationResolver;
    }

    private List<TransportAdapterDescriptor> resolveRegistrationDescriptors() {
        List<TransportAdapterDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new TransportAdapterDescriptor(
                PollingWorkerAdapter.PROTOCOL,
                WorkerTransportHints.POLLING,
                java.util.Set.of("pull", "queue")
        ));
        TransportAdapterBootstrap<WorkerTransportMessage> primaryBootstrap = resolvePrimaryTransportAdapterBootstrap();
        if (transportAdapterBootstrap != null) {
            TransportAdapterDescriptor primaryDescriptor = primaryBootstrap.descriptor();
            if (primaryDescriptor != null) {
                descriptors.add(primaryDescriptor);
            }
        } else if (defaultWebSocketAdapterConfig.isEnabled()) {
            TransportAdapterDescriptor webSocketDescriptor = primaryBootstrap.descriptor();
            if (webSocketDescriptor != null) {
                descriptors.add(webSocketDescriptor);
            }
        }
        if (defaultSocketAdapterConfig.isEnabled()) {
            TransportAdapterDescriptor socketDescriptor = resolveDefaultSocketTransportAdapterBootstrap().descriptor();
            if (socketDescriptor != null) {
                descriptors.add(socketDescriptor);
            }
        }
        for (TransportAdapterBootstrap<WorkerTransportMessage> bootstrap : additionalTransportAdapterBootstraps) {
            TransportAdapterDescriptor descriptor = bootstrap.descriptor();
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        return List.copyOf(descriptors);
    }

    private boolean usesDefaultWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory == null
                || workerTransportRuntimeFactory instanceof DefaultWorkerTransportRuntimeFactory;
    }
}
