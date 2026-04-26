package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.starter.transport.CompositeWorkerEndpointRegistry;
import com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.RuntimeEventBusWorkerSystemEventChannel;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.socket.runtime.SocketTransportAdapterBootstrap;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.runtime.WebSocketEmbeddedRuntimeSupport;
import com.xa.mass.transport.websocket.runtime.WebSocketTransportAdapterBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Transport runtime configuration for embedded XA Mass application assembly.
 */
public class TransportConfig {

    private static final String API_MODE_UNSUPPORTED_MESSAGE =
            "API-based transport is not implemented yet. Use queue/polling transport or provide a real transport adapter.";

    private MessageTransporterFactory.TransporterType transporterType =
            MessageTransporterFactory.TransporterType.QUEUE_BASED;
    private MessageQueue<String> inputQueue;
    private MessageQueue<WorkerTransportMessage> outputQueue;

    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    private WorkerSystemEventChannel customSystemEventChannel;
    private WorkerEndpointRegistry workerEndpointRegistry;
    private Supplier<WorkerEndpointRegistry> endpointRegistryFactory;
    private Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver;
    private WebSocketAdapterConfig defaultWebSocketAdapterConfig = new WebSocketAdapterConfig();
    private SocketAdapterConfig defaultSocketAdapterConfig = new SocketAdapterConfig();
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap;
    private List<TransportAdapterBootstrap<WorkerTransportMessage>> additionalTransportAdapterBootstraps = List.of();
    private transient WorkerEndpointRegistry compatibilityWorkerEndpointRegistry;

    public TransportConfig() {
        this.endpointRegistryFactory = CompositeWorkerEndpointRegistry::new;
        this.systemEventChannelResolver = ignored -> new RuntimeEventBusWorkerSystemEventChannel();
    }

    public TransportConfig(TransportConfig source) {
        this.transporterType = source.transporterType;
        this.inputQueue = source.inputQueue;
        this.outputQueue = source.outputQueue;
        this.inputApiUrl = source.inputApiUrl;
        this.outputApiUrl = source.outputApiUrl;
        this.apiKey = source.apiKey;
        this.customSystemEventChannel = source.customSystemEventChannel;
        this.workerEndpointRegistry = source.workerEndpointRegistry;
        this.endpointRegistryFactory = source.endpointRegistryFactory;
        this.systemEventChannelResolver = source.systemEventChannelResolver;
        this.defaultWebSocketAdapterConfig = new WebSocketAdapterConfig(source.defaultWebSocketAdapterConfig);
        this.defaultSocketAdapterConfig = new SocketAdapterConfig(source.defaultSocketAdapterConfig);
        this.workerTransportRuntimeFactory = source.workerTransportRuntimeFactory;
        this.transportAdapterBootstrap = source.transportAdapterBootstrap;
        this.additionalTransportAdapterBootstraps = List.copyOf(source.additionalTransportAdapterBootstraps);
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
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()} or
     * {@link #getDefaultSocketAdapterConfig()}. This transport-global helper
     * mutates only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public void setEnabled(boolean enabled) {
        defaultWebSocketAdapterConfig.setEnabled(enabled);
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * reflects only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public boolean isTransportServerEnabled() {
        return defaultWebSocketAdapterConfig.isServerEnabled();
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * mutates only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public void setTransportServerEnabled(boolean transportServerEnabled) {
        defaultWebSocketAdapterConfig.setServerEnabled(transportServerEnabled);
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * reflects only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public int getTransportServerPort() {
        return defaultWebSocketAdapterConfig.getServerPort();
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * mutates only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public void setTransportServerPort(int transportServerPort) {
        defaultWebSocketAdapterConfig.setServerPort(transportServerPort);
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()} or
     * {@link #getDefaultSocketAdapterConfig()}. This transport-global helper
     * reflects only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public int getMaxConnections() {
        return defaultWebSocketAdapterConfig.getMaxConnections();
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()} or
     * {@link #getDefaultSocketAdapterConfig()}. This transport-global helper
     * mutates only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public void setMaxConnections(int maxConnections) {
        defaultWebSocketAdapterConfig.setMaxConnections(maxConnections);
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * reflects only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public String getTransportEndpointPath() {
        return defaultWebSocketAdapterConfig.getEndpointPath();
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * mutates only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public void setTransportEndpointPath(String transportEndpointPath) {
        defaultWebSocketAdapterConfig.setEndpointPath(transportEndpointPath);
    }

    /**
     * @deprecated Embedded-runtime mainline should snapshot this config into
     * {@link TransportRuntimeComposition} and create the transporter from that
     * fixed runtime composition. Keep this only for advanced external embedding
     * that still routes runtime assembly through {@code TransportConfig}.
     */
    @Deprecated
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

    /**
     * @deprecated Embedded-runtime mainline should call
     * {@link WebSocketEmbeddedRuntimeSupport#createDispatcherContext(WorkerEndpointRegistry, TaskResultIngestChannel, WorkerSystemEventChannel)}
     * directly for the bundled WebSocket-backed adapter path. Keep this only
     * for advanced external embedding that still routes through
     * {@code TransportConfig}.
     */
    @Deprecated
    public WebSocketDispatchRuntimeContext createDispatcherContext(
            MessageTransporter<String, WorkerTransportMessage> messageTransporter,
            WorkerEndpointRegistry endpointRegistry,
            TaskResultIngestChannel taskResultIngestChannel,
            WorkerSystemEventChannel systemEventChannel) {
        return WebSocketEmbeddedRuntimeSupport.createDispatcherContext(
                endpointRegistry,
                taskResultIngestChannel,
                systemEventChannel
        );
    }

    public MessageTransporterFactory.TransporterType getTransporterType() {
        return transporterType;
    }

    public void setTransporterType(MessageTransporterFactory.TransporterType transporterType) {
        this.transporterType = transporterType;
    }

    public MessageQueue<String> getInputQueue() {
        return inputQueue;
    }

    public void setInputQueue(MessageQueue<String> inputQueue) {
        this.inputQueue = inputQueue;
    }

    public MessageQueue<WorkerTransportMessage> getOutputQueue() {
        return outputQueue;
    }

    public void setOutputQueue(MessageQueue<WorkerTransportMessage> outputQueue) {
        this.outputQueue = outputQueue;
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

    public WorkerSystemEventChannel getCustomSystemEventChannel() {
        return customSystemEventChannel;
    }

    public void setCustomSystemEventChannel(WorkerSystemEventChannel customSystemEventChannel) {
        this.customSystemEventChannel = customSystemEventChannel;
    }

    public WorkerEndpointRegistry getWorkerEndpointRegistry() {
        return workerEndpointRegistry;
    }

    public void setWorkerEndpointRegistry(WorkerEndpointRegistry workerEndpointRegistry) {
        this.workerEndpointRegistry = workerEndpointRegistry;
        this.compatibilityWorkerEndpointRegistry = null;
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * reflects only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public com.xa.mass.transport.TransportServerFactory<com.xa.mass.starter.transport.TransportServerFactoryContext> getTransportServerFactory() {
        return defaultWebSocketAdapterConfig.getTransportServerFactory();
    }

    /**
     * @deprecated Prefer explicit adapter configuration via
     * {@link #getDefaultWebSocketAdapterConfig()}. This transport-global helper
     * mutates only the bundled default WebSocket adapter and is
     * compatibility-only.
     */
    @Deprecated(forRemoval = false)
    public void setTransportServerFactory(
            com.xa.mass.transport.TransportServerFactory<com.xa.mass.starter.transport.TransportServerFactoryContext> transportServerFactory) {
        defaultWebSocketAdapterConfig.setTransportServerFactory(transportServerFactory);
    }

    public WebSocketAdapterConfig getDefaultWebSocketAdapterConfig() {
        return defaultWebSocketAdapterConfig;
    }

    public void setDefaultWebSocketAdapterConfig(WebSocketAdapterConfig defaultWebSocketAdapterConfig) {
        this.defaultWebSocketAdapterConfig = new WebSocketAdapterConfig(
                java.util.Objects.requireNonNull(defaultWebSocketAdapterConfig, "defaultWebSocketAdapterConfig")
        );
    }

    public SocketAdapterConfig getDefaultSocketAdapterConfig() {
        return defaultSocketAdapterConfig;
    }

    public void setDefaultSocketAdapterConfig(SocketAdapterConfig defaultSocketAdapterConfig) {
        this.defaultSocketAdapterConfig = new SocketAdapterConfig(
                java.util.Objects.requireNonNull(defaultSocketAdapterConfig, "defaultSocketAdapterConfig")
        );
    }

    public WorkerTransportRuntimeFactory getWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory;
    }

    public void setWorkerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
        this.workerTransportRuntimeFactory = workerTransportRuntimeFactory;
    }

    public TransportAdapterBootstrap<WorkerTransportMessage> getTransportAdapterBootstrap() {
        return transportAdapterBootstrap;
    }

    public void setTransportAdapterBootstrap(TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
        this.transportAdapterBootstrap = transportAdapterBootstrap;
    }

    public List<TransportAdapterBootstrap<WorkerTransportMessage>> getAdditionalTransportAdapterBootstraps() {
        return additionalTransportAdapterBootstraps;
    }

    public void setAdditionalTransportAdapterBootstraps(
            List<TransportAdapterBootstrap<WorkerTransportMessage>> additionalTransportAdapterBootstraps) {
        this.additionalTransportAdapterBootstraps = additionalTransportAdapterBootstraps == null
                ? List.of()
                : List.copyOf(additionalTransportAdapterBootstraps);
    }

    public void addTransportAdapterBootstrap(TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
        if (transportAdapterBootstrap == null) {
            return;
        }
        List<TransportAdapterBootstrap<WorkerTransportMessage>> bootstraps =
                new ArrayList<>(additionalTransportAdapterBootstraps);
        bootstraps.add(transportAdapterBootstrap);
        additionalTransportAdapterBootstraps = List.copyOf(bootstraps);
    }

    Supplier<WorkerEndpointRegistry> endpointRegistryFactory() {
        return endpointRegistryFactory;
    }

    Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver() {
        return systemEventChannelResolver;
    }

    public TransportRuntimeComposition snapshotRuntimeComposition() {
        return new TransportRuntimeComposition(this);
    }

    /**
     * @deprecated Embedded-runtime mainline should resolve endpoint-registry
     * ownership from {@link TransportRuntimeComposition}. Keep this only for
     * advanced external embedding that still routes runtime assembly through
     * {@code TransportConfig}.
     */
    @Deprecated
    public WorkerEndpointRegistry resolveWorkerEndpointRegistry() {
        if (workerEndpointRegistry != null) {
            return workerEndpointRegistry;
        }
        if (compatibilityWorkerEndpointRegistry == null) {
            if (endpointRegistryFactory == null) {
                throw new IllegalStateException("Transport endpoint registry factory is not configured");
            }
            compatibilityWorkerEndpointRegistry = endpointRegistryFactory.get();
        }
        return compatibilityWorkerEndpointRegistry;
    }

    /**
     * @deprecated Embedded-runtime mainline should resolve adapter system-event
     * wiring from {@link TransportRuntimeComposition}. Keep this only for
     * advanced external embedding that still routes runtime assembly through
     * {@code TransportConfig}.
     */
    @Deprecated
    public WorkerSystemEventChannel resolveSystemEventChannel() {
        if (customSystemEventChannel != null) {
            return customSystemEventChannel;
        }
        if (systemEventChannelResolver == null) {
            throw new IllegalStateException("Transport system-event resolver is not configured");
        }
        return systemEventChannelResolver.apply(resolveWorkerEndpointRegistry());
    }

    /**
     * @deprecated Embedded-runtime mainline should resolve worker transport
     * runtime composition from {@link TransportRuntimeComposition}. Keep this
     * only for advanced external embedding that still routes runtime assembly
     * through {@code TransportConfig}.
     */
    @Deprecated
    public WorkerTransportRuntimeFactory resolveWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory != null
                ? workerTransportRuntimeFactory
                : new DefaultWorkerTransportRuntimeFactory();
    }

    /**
     * @deprecated Embedded-runtime mainline should resolve adapter bootstrap
     * from {@link TransportRuntimeComposition}. Keep this only for advanced
     * external embedding that still routes runtime assembly through
     * {@code TransportConfig}.
     */
    @Deprecated
    public TransportAdapterBootstrap<WorkerTransportMessage> resolveTransportAdapterBootstrap() {
        return transportAdapterBootstrap != null
                ? transportAdapterBootstrap
                : new WebSocketTransportAdapterBootstrap(defaultWebSocketAdapterConfig);
    }

    /**
     * @deprecated Embedded-runtime mainline should resolve adapter bootstraps
     * from {@link TransportRuntimeComposition}. Keep this only for advanced
     * external embedding that still routes runtime assembly through
     * {@code TransportConfig}.
     */
    @Deprecated
    public TransportAdapterBootstrap<WorkerTransportMessage> resolveSocketTransportAdapterBootstrap() {
        return new SocketTransportAdapterBootstrap(defaultSocketAdapterConfig);
    }

    /**
     * @deprecated Embedded-runtime mainline should use
     * {@link WebSocketEmbeddedRuntimeSupport#createTransportServer(int, String, WebSocketDispatchRuntimeContext, WorkerEndpointRegistry)}
     * for the default WebSocket-backed adapter path, or
     * {@link #getTransportServerFactory()} for an explicit override. Keep this
     * only for advanced external embedding that still routes through
     * {@code TransportConfig}.
     */
    @Deprecated
    public TransportServer createTransportServer(WebSocketDispatchRuntimeContext dispatcherContext,
                                                 WorkerEndpointRegistry endpointRegistry,
                                                 int port) {
        return WebSocketEmbeddedRuntimeSupport.createTransportServer(
                new WebSocketAdapterConfig(defaultWebSocketAdapterConfig),
                dispatcherContext,
                endpointRegistry,
                port
        );
    }
}
