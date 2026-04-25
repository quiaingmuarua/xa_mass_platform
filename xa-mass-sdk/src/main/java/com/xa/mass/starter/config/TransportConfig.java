package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.WorkerTransportMessage;
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

    private boolean enabled = true;
    private boolean transportServerEnabled = true;
    private int transportServerPort = 8080;
    private int maxConnections = 1000;
    private String transportEndpointPath = "/ws";

    private MessageTransporterFactory.TransporterType transporterType =
            MessageTransporterFactory.TransporterType.QUEUE_BASED;
    private MessageQueue<String> inputQueue;
    private MessageQueue<WorkerTransportMessage> outputQueue;

    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    private WorkerSystemEventChannel customSystemEventChannel;
    private WorkerEndpointRegistry workerEndpointRegistry;
    private transient WorkerEndpointRegistry runtimeOwnedEndpointRegistry;
    private Supplier<WorkerEndpointRegistry> endpointRegistryFactory;
    private Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver;
    private TransportServerFactory<TransportServerFactoryContext> transportServerFactory;
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap;
    private List<TransportAdapterBootstrap<WorkerTransportMessage>> additionalTransportAdapterBootstraps = List.of();

    public TransportConfig() {
        this.endpointRegistryFactory = WebSocketEmbeddedRuntimeSupport::createEndpointRegistry;
        this.systemEventChannelResolver = WebSocketEmbeddedRuntimeSupport::resolveSystemEventChannel;
    }

    public TransportConfig(TransportConfig source) {
        this.enabled = source.enabled;
        this.transportServerEnabled = source.transportServerEnabled;
        this.transportServerPort = source.transportServerPort;
        this.maxConnections = source.maxConnections;
        this.transportEndpointPath = source.transportEndpointPath;
        this.transporterType = source.transporterType;
        this.inputQueue = source.inputQueue;
        this.outputQueue = source.outputQueue;
        this.inputApiUrl = source.inputApiUrl;
        this.outputApiUrl = source.outputApiUrl;
        this.apiKey = source.apiKey;
        this.customSystemEventChannel = source.customSystemEventChannel;
        this.workerEndpointRegistry = source.workerEndpointRegistry;
        this.runtimeOwnedEndpointRegistry = null;
        this.endpointRegistryFactory = source.endpointRegistryFactory;
        this.systemEventChannelResolver = source.systemEventChannelResolver;
        this.transportServerFactory = source.transportServerFactory;
        this.workerTransportRuntimeFactory = source.workerTransportRuntimeFactory;
        this.transportAdapterBootstrap = source.transportAdapterBootstrap;
        this.additionalTransportAdapterBootstraps = List.copyOf(source.additionalTransportAdapterBootstraps);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTransportServerEnabled() {
        return transportServerEnabled;
    }

    public void setTransportServerEnabled(boolean transportServerEnabled) {
        this.transportServerEnabled = transportServerEnabled;
    }

    public int getTransportServerPort() {
        return transportServerPort;
    }

    public void setTransportServerPort(int transportServerPort) {
        this.transportServerPort = transportServerPort;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public String getTransportEndpointPath() {
        return transportEndpointPath;
    }

    public void setTransportEndpointPath(String transportEndpointPath) {
        this.transportEndpointPath = transportEndpointPath;
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
     * {@link WebSocketEmbeddedRuntimeSupport#createDispatcherContext(MessageTransporter, WorkerEndpointRegistry, TaskResultIngestChannel, WorkerSystemEventChannel)}
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
                messageTransporter,
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
    }

    public TransportServerFactory<TransportServerFactoryContext> getTransportServerFactory() {
        return transportServerFactory;
    }

    public void setTransportServerFactory(TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
        this.transportServerFactory = transportServerFactory;
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
        if (runtimeOwnedEndpointRegistry == null) {
            if (endpointRegistryFactory == null) {
                throw new IllegalStateException("Transport endpoint registry factory is not configured");
            }
            runtimeOwnedEndpointRegistry = endpointRegistryFactory.get();
        }
        return runtimeOwnedEndpointRegistry;
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
        WorkerEndpointRegistry endpointRegistry = workerEndpointRegistry;
        if (endpointRegistry == null) {
            endpointRegistry = resolveWorkerEndpointRegistry();
        }
        if (systemEventChannelResolver == null) {
            throw new IllegalStateException("Transport system-event resolver is not configured");
        }
        return systemEventChannelResolver.apply(endpointRegistry);
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
                : new WebSocketTransportAdapterBootstrap(
                enabled,
                transportServerEnabled,
                transportServerPort,
                maxConnections,
                transportEndpointPath,
                transportServerFactory
        );
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
        if (!transportServerEnabled) {
            return null;
        }
        if (transportServerFactory == null) {
            return WebSocketEmbeddedRuntimeSupport.createTransportServer(
                    port,
                    transportEndpointPath,
                    dispatcherContext,
                    endpointRegistry
            );
        }
        return transportServerFactory.create(new TransportServerFactoryContext(
                endpointRegistry,
                dispatcherContext.getMessageTransporter()::sendInput,
                port,
                transportEndpointPath
        ));
    }
}
