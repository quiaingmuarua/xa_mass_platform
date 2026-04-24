package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.gateway.runtime.WebSocketGatewayRuntimeSupport;
import com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Gateway runtime configuration.
 */
public class GatewayConfig {
    private static final String API_MODE_UNSUPPORTED_MESSAGE =
            "API-based transport is not implemented yet. Use queue/polling transport or provide a real transport adapter.";

    private boolean enabled = true;
    private boolean transportServerEnabled = true;
    private int maxConnections = 1000;
    private String transportEndpointPath = "/ws";

    private MessageTransporterFactory.TransporterType transporterType =
            MessageTransporterFactory.TransporterType.QUEUE_BASED;
    private MessageQueue<String> inputQueue;
    private MessageQueue<OutboundDelivery> outputQueue;

    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    private WebSocketTransportFrameCodec frameCodec;

    private WorkerSystemEventChannel customSystemEventChannel;
    private WorkerEndpointRegistry workerEndpointRegistry;
    private transient WorkerEndpointRegistry runtimeOwnedEndpointRegistry;
    private Supplier<WorkerEndpointRegistry> endpointRegistryFactory;
    private Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver;
    private TransportServerFactory<TransportServerFactoryContext> transportServerFactory;
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;

    public GatewayConfig() {
        this.endpointRegistryFactory = WebSocketGatewayRuntimeSupport::createEndpointRegistry;
        this.systemEventChannelResolver = WebSocketGatewayRuntimeSupport::resolveSystemEventChannel;
    }

    public GatewayConfig(GatewayConfig source) {
        this.enabled = source.enabled;
        this.transportServerEnabled = source.transportServerEnabled;
        this.maxConnections = source.maxConnections;
        this.transportEndpointPath = source.transportEndpointPath;
        this.transporterType = source.transporterType;
        this.inputQueue = source.inputQueue;
        this.outputQueue = source.outputQueue;
        this.inputApiUrl = source.inputApiUrl;
        this.outputApiUrl = source.outputApiUrl;
        this.apiKey = source.apiKey;
        this.frameCodec = source.frameCodec;
        this.customSystemEventChannel = source.customSystemEventChannel;
        this.workerEndpointRegistry = source.workerEndpointRegistry;
        this.runtimeOwnedEndpointRegistry = null;
        this.endpointRegistryFactory = source.endpointRegistryFactory;
        this.systemEventChannelResolver = source.systemEventChannelResolver;
        this.transportServerFactory = source.transportServerFactory;
        this.workerTransportRuntimeFactory = source.workerTransportRuntimeFactory;
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

    public MessageTransporter<String, OutboundDelivery> createMessageTransporter() {
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

    public WebSocketTransportFrameCodec resolveFrameCodec() {
        return frameCodec != null ? frameCodec : new WebSocketTransportFrameCodec();
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

    public MessageQueue<OutboundDelivery> getOutputQueue() {
        return outputQueue;
    }

    public void setOutputQueue(MessageQueue<OutboundDelivery> outputQueue) {
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

    public WebSocketTransportFrameCodec getFrameCodec() {
        return frameCodec;
    }

    public void setFrameCodec(WebSocketTransportFrameCodec frameCodec) {
        this.frameCodec = frameCodec;
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

    public WorkerEndpointRegistry resolveWorkerEndpointRegistry() {
        if (workerEndpointRegistry != null) {
            return workerEndpointRegistry;
        }
        if (runtimeOwnedEndpointRegistry == null) {
            if (endpointRegistryFactory == null) {
                throw new IllegalStateException("Gateway endpoint registry factory is not configured");
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
            throw new IllegalStateException("Gateway system-event resolver is not configured");
        }
        return systemEventChannelResolver.apply(endpointRegistry);
    }

    public WorkerTransportRuntimeFactory resolveWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory != null
                ? workerTransportRuntimeFactory
                : new DefaultWorkerTransportRuntimeFactory();
    }

    public TransportServer createTransportServer(WebSocketTransportFrameCodec frameCodec,
                                                 Consumer<String> inboundMessageSink,
                                                 WorkerEndpointRegistry endpointRegistry,
                                                 int port) {
        if (!transportServerEnabled) {
            return null;
        }
        if (transportServerFactory == null) {
            return WebSocketGatewayRuntimeSupport.createTransportServer(
                    transportEndpointPath,
                    frameCodec,
                    inboundMessageSink,
                    endpointRegistry
            );
        }
        return transportServerFactory.create(new TransportServerFactoryContext(
                endpointRegistry,
                frameCodec,
                inboundMessageSink,
                port,
                transportEndpointPath
        ));
    }
}
