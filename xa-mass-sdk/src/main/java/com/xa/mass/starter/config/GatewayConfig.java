package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.EnvelopeMessageTransporter;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.MessageCodecFactory;
import com.xa.mass.gateway.session.EventBusWorkerSystemEventChannel;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Gateway runtime configuration.
 */
public class GatewayConfig {
    private boolean enabled = true;
    private boolean transportServerEnabled = true;
    private int maxConnections = 1000;
    private String transportEndpointPath = "/ws";

    // Transport configuration.
    private MessageTransporterFactory.TransporterType transporterType =
            MessageTransporterFactory.TransporterType.QUEUE_BASED;
    private MessageQueue<Envelope> inputQueue;
    private MessageQueue<Envelope> outputQueue;

    // External API configuration used by API_BASED transporters.
    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    // Codec configuration.
    private MessageCodecFactory.CodecType codecType = MessageCodecFactory.CodecType.GSON;
    private MessageCodec messageCodec;

    // Optional custom WorkerSystemEventChannel; null means use the default from ServerSessionManager.
    private WorkerSystemEventChannel customSystemEventChannel;
    private WorkerEndpointRegistry workerEndpointRegistry;
    private TransportServerFactory<TransportServerFactoryContext> transportServerFactory;
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;

    public GatewayConfig() {
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
        this.codecType = source.codecType;
        this.messageCodec = source.messageCodec;
        this.customSystemEventChannel = source.customSystemEventChannel;
        this.workerEndpointRegistry = source.workerEndpointRegistry;
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

    /**
     * Create the configured message transporter.
     */
    public MessageTransporter<Envelope> createMessageTransporter() {
        switch (transporterType) {
            case QUEUE_BASED:
                if (inputQueue == null || outputQueue == null) {
                    throw new IllegalStateException(
                            "QUEUE_BASED transporter requires both inputQueue and outputQueue");
                }
                return MessageTransporterFactory.createQueueBased(inputQueue, outputQueue);
            case MULTI_LEVEL:
                return MessageTransporterFactory.createMultiLevel();
            case API_BASED:
                if (inputApiUrl == null || outputApiUrl == null || apiKey == null) {
                    throw new IllegalStateException(
                            "API_BASED transporter requires inputApiUrl, outputApiUrl, and apiKey");
                }
                return MessageTransporterFactory.createApiBased(inputApiUrl, outputApiUrl, apiKey);
            default:
                throw new IllegalArgumentException("Unsupported transporter type: " + transporterType);
        }
    }

    /**
     * Create the envelope-aware transporter used by the gateway.
     */
    public EnvelopeMessageTransporter createEnvelopeMessageTransporter() {
        switch (transporterType) {
            case QUEUE_BASED:
                if (inputQueue == null || outputQueue == null) {
                    throw new IllegalStateException(
                            "QUEUE_BASED transporter requires both inputQueue and outputQueue");
                }
                return EnvelopeMessageTransporter.createQueueBased(inputQueue, outputQueue);
            case MULTI_LEVEL:
                return EnvelopeMessageTransporter.createMultiLevel();
            case API_BASED:
                if (inputApiUrl == null || outputApiUrl == null || apiKey == null) {
                    throw new IllegalStateException(
                            "API_BASED transporter requires inputApiUrl, outputApiUrl, and apiKey");
                }
                return EnvelopeMessageTransporter.createApiBased(inputApiUrl, outputApiUrl, apiKey);
            default:
                throw new IllegalArgumentException("Unsupported transporter type: " + transporterType);
        }
    }

    /**
     * Create the configured message codec.
     */
    public MessageCodec createMessageCodec() {
        if (messageCodec != null) {
            return messageCodec;
        }
        return MessageCodecFactory.create(codecType);
    }

    public MessageTransporterFactory.TransporterType getTransporterType() {
        return transporterType;
    }

    public void setTransporterType(MessageTransporterFactory.TransporterType transporterType) {
        this.transporterType = transporterType;
    }

    public MessageQueue<Envelope> getInputQueue() {
        return inputQueue;
    }

    public void setInputQueue(MessageQueue<Envelope> inputQueue) {
        this.inputQueue = inputQueue;
    }

    public MessageQueue<Envelope> getOutputQueue() {
        return outputQueue;
    }

    public void setOutputQueue(MessageQueue<Envelope> outputQueue) {
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

    public MessageCodecFactory.CodecType getCodecType() {
        return codecType;
    }

    public void setCodecType(MessageCodecFactory.CodecType codecType) {
        this.codecType = codecType;
    }

    public MessageCodec getMessageCodec() {
        return messageCodec;
    }

    public void setMessageCodec(MessageCodec messageCodec) {
        this.messageCodec = messageCodec;
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
        return workerEndpointRegistry != null ? workerEndpointRegistry : ServerSessionManager.INSTANCE;
    }

    public WorkerSystemEventChannel resolveSystemEventChannel(WorkerEndpointRegistry endpointRegistry) {
        if (customSystemEventChannel != null) {
            return customSystemEventChannel;
        }
        if (endpointRegistry instanceof ServerSessionManager sessionManager) {
            return sessionManager.getSystemEventChannel();
        }
        return new EventBusWorkerSystemEventChannel();
    }

    public WorkerTransportRuntimeFactory resolveWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory != null
                ? workerTransportRuntimeFactory
                : new DefaultWorkerTransportRuntimeFactory();
    }

    public TransportServer createTransportServer(DispatchRuntimeContext dispatcherContext, int port) {
        if (!transportServerEnabled) {
            return null;
        }
        if (transportServerFactory == null) {
            return null;
        }
        return transportServerFactory.create(new TransportServerFactoryContext(
                dispatcherContext,
                resolveWorkerEndpointRegistry(),
                port,
                transportEndpointPath
        ));
    }
}
