package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
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
        return defaultWebSocketAdapterConfig.isEnabled() || defaultSocketAdapterConfig.isEnabled();
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

    public List<TransportAdapterBootstrap<WorkerTransportMessage>> resolveTransportAdapterBootstraps() {
        List<TransportAdapterBootstrap<WorkerTransportMessage>> bootstraps = new ArrayList<>();
        bootstraps.add(transportAdapterBootstrap != null
                ? transportAdapterBootstrap
                : new WebSocketTransportAdapterBootstrap(defaultWebSocketAdapterConfig));
        bootstraps.add(new SocketTransportAdapterBootstrap(defaultSocketAdapterConfig));
        bootstraps.addAll(additionalTransportAdapterBootstraps);
        return List.copyOf(bootstraps);
    }
}
