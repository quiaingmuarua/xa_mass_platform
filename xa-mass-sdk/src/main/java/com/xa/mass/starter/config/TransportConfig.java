package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.RuntimeEventBusWorkerSystemEventChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Transport runtime configuration for embedded XA Mass application assembly.
 */
public class TransportConfig {

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
    private WebSocketAdapterConfig bundledWebSocketAdapterConfig = new WebSocketAdapterConfig();
    private SocketAdapterConfig bundledSocketAdapterConfig = new SocketAdapterConfig();
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private TransportAdapterBootstrap<WorkerTransportMessage> primaryTransportAdapterBootstrap;
    private List<TransportAdapterBootstrap<WorkerTransportMessage>> supplementalTransportAdapterBootstraps = List.of();

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
        this.bundledWebSocketAdapterConfig = new WebSocketAdapterConfig(source.bundledWebSocketAdapterConfig);
        this.bundledSocketAdapterConfig = new SocketAdapterConfig(source.bundledSocketAdapterConfig);
        this.workerTransportRuntimeFactory = source.workerTransportRuntimeFactory;
        this.primaryTransportAdapterBootstrap = source.primaryTransportAdapterBootstrap;
        this.supplementalTransportAdapterBootstraps = List.copyOf(source.supplementalTransportAdapterBootstraps);
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterConfig.isEnabled()
                || bundledWebSocketAdapterConfig.isServerEnabled()
                || bundledSocketAdapterConfig.isEnabled()
                || bundledSocketAdapterConfig.isServerEnabled()
                || primaryTransportAdapterBootstrap != null
                || !supplementalTransportAdapterBootstraps.isEmpty();
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

    public WebSocketAdapterConfig getBundledWebSocketAdapterConfig() {
        return bundledWebSocketAdapterConfig;
    }

    public void setBundledWebSocketAdapterConfig(WebSocketAdapterConfig bundledWebSocketAdapterConfig) {
        this.bundledWebSocketAdapterConfig = new WebSocketAdapterConfig(
                java.util.Objects.requireNonNull(bundledWebSocketAdapterConfig, "bundledWebSocketAdapterConfig")
        );
    }

    public SocketAdapterConfig getBundledSocketAdapterConfig() {
        return bundledSocketAdapterConfig;
    }

    public void setBundledSocketAdapterConfig(SocketAdapterConfig bundledSocketAdapterConfig) {
        this.bundledSocketAdapterConfig = new SocketAdapterConfig(
                java.util.Objects.requireNonNull(bundledSocketAdapterConfig, "bundledSocketAdapterConfig")
        );
    }

    public WorkerTransportRuntimeFactory getWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory;
    }

    public void setWorkerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
        this.workerTransportRuntimeFactory = workerTransportRuntimeFactory;
    }

    public TransportAdapterBootstrap<WorkerTransportMessage> getPrimaryTransportAdapterBootstrap() {
        return primaryTransportAdapterBootstrap;
    }

    public void setPrimaryTransportAdapterBootstrap(
            TransportAdapterBootstrap<WorkerTransportMessage> primaryTransportAdapterBootstrap) {
        this.primaryTransportAdapterBootstrap = primaryTransportAdapterBootstrap;
    }

    public List<TransportAdapterBootstrap<WorkerTransportMessage>> getSupplementalTransportAdapterBootstraps() {
        return supplementalTransportAdapterBootstraps;
    }

    public void setSupplementalTransportAdapterBootstraps(
            List<TransportAdapterBootstrap<WorkerTransportMessage>> supplementalTransportAdapterBootstraps) {
        this.supplementalTransportAdapterBootstraps = supplementalTransportAdapterBootstraps == null
                ? List.of()
                : List.copyOf(supplementalTransportAdapterBootstraps);
    }

    public void addSupplementalTransportAdapterBootstrap(
            TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
        if (transportAdapterBootstrap == null) {
            return;
        }
        List<TransportAdapterBootstrap<WorkerTransportMessage>> bootstraps =
                new ArrayList<>(supplementalTransportAdapterBootstraps);
        bootstraps.add(transportAdapterBootstrap);
        supplementalTransportAdapterBootstraps = List.copyOf(bootstraps);
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
}
