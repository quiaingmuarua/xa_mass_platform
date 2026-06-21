package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoff;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Transport runtime configuration for embedded XA Mass application assembly.
 */
public class TransportConfig {

    public static final int DEFAULT_MAX_DELIVERY_QUEUED_ITEMS = 100_000;
    public static final int DEFAULT_MAX_DELIVERY_ITEMS_PER_ROUTE = 10_000;
    public static final int DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS = 10_000;

    private MessageTransporterFactory.TransporterType transporterType =
            MessageTransporterFactory.TransporterType.QUEUE_BASED;
    private MessageQueue<String> inputQueue;
    private MessageQueue<TransportOutboundMessage> outputQueue;

    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    private WorkerPresenceIngress customWorkerPresenceIngress;
    private Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory;
    private WebSocketAdapterConfig bundledWebSocketAdapterConfig = new WebSocketAdapterConfig();
    private SocketAdapterConfig bundledSocketAdapterConfig = new SocketAdapterConfig();
    private List<WebSocketAdapterConfig> supplementalWebSocketAdapterConfigs = List.of();
    private List<SocketAdapterConfig> supplementalSocketAdapterConfigs = List.of();
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private Supplier<TransportDeliveryStore> deliveryStoreFactory;
    private Supplier<TransportDeliveryCommandHandoff> deliveryCommandHandoffFactory;
    private Supplier<RedisTransportResultIngressChannel> taskResultInboxFactory;
    private Supplier<RedisTransportDeliveryFailureChannel> deliveryFailureInboxFactory;
    private TransportAdapterBootstrap primaryTransportAdapterBootstrap;
    private List<TransportAdapterBootstrap> supplementalTransportAdapterBootstraps = List.of();
    private int maxDeliveryQueuedItems = DEFAULT_MAX_DELIVERY_QUEUED_ITEMS;
    private int maxDeliveryItemsPerRoute = DEFAULT_MAX_DELIVERY_ITEMS_PER_ROUTE;
    private int transportRuntimeMaxPendingTasks = DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS;
    private int eventRuntimeMaxPendingTasks = DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS;
    private long eventHandlerTimeoutMillis;
    private long endpointLeaseMillis = 30_000L;
    private long adapterMailboxConsumerLeaseMillis = 30_000L;
    private TransportRuntimeRole runtimeRole = TransportRuntimeRole.EMBEDDED;

    public TransportConfig() {
    }

    public TransportConfig(TransportConfig source) {
        this.transporterType = source.transporterType;
        this.inputQueue = source.inputQueue;
        this.outputQueue = source.outputQueue;
        this.inputApiUrl = source.inputApiUrl;
        this.outputApiUrl = source.outputApiUrl;
        this.apiKey = source.apiKey;
        this.customWorkerPresenceIngress = source.customWorkerPresenceIngress;
        this.endpointLeaseStoreFactory = source.endpointLeaseStoreFactory;
        this.bundledWebSocketAdapterConfig = new WebSocketAdapterConfig(source.bundledWebSocketAdapterConfig);
        this.bundledSocketAdapterConfig = new SocketAdapterConfig(source.bundledSocketAdapterConfig);
        this.supplementalWebSocketAdapterConfigs = source.supplementalWebSocketAdapterConfigs.stream()
                .map(WebSocketAdapterConfig::new)
                .toList();
        this.supplementalSocketAdapterConfigs = source.supplementalSocketAdapterConfigs.stream()
                .map(SocketAdapterConfig::new)
                .toList();
        this.workerTransportRuntimeFactory = source.workerTransportRuntimeFactory;
        this.deliveryStoreFactory = source.deliveryStoreFactory;
        this.deliveryCommandHandoffFactory = source.deliveryCommandHandoffFactory;
        this.taskResultInboxFactory = source.taskResultInboxFactory;
        this.deliveryFailureInboxFactory = source.deliveryFailureInboxFactory;
        this.primaryTransportAdapterBootstrap = source.primaryTransportAdapterBootstrap;
        this.supplementalTransportAdapterBootstraps = List.copyOf(source.supplementalTransportAdapterBootstraps);
        this.maxDeliveryQueuedItems = source.maxDeliveryQueuedItems;
        this.maxDeliveryItemsPerRoute = source.maxDeliveryItemsPerRoute;
        this.transportRuntimeMaxPendingTasks = source.transportRuntimeMaxPendingTasks;
        this.eventRuntimeMaxPendingTasks = source.eventRuntimeMaxPendingTasks;
        this.eventHandlerTimeoutMillis = source.eventHandlerTimeoutMillis;
        this.endpointLeaseMillis = source.endpointLeaseMillis;
        this.adapterMailboxConsumerLeaseMillis = source.adapterMailboxConsumerLeaseMillis;
        this.runtimeRole = source.runtimeRole;
    }

    public boolean isEnabled() {
        return bundledWebSocketAdapterConfig.isEnabled()
                || bundledWebSocketAdapterConfig.isServerEnabled()
                || bundledSocketAdapterConfig.isEnabled()
                || bundledSocketAdapterConfig.isServerEnabled()
                || hasAnyEnabledAdapterConfig(supplementalWebSocketAdapterConfigs)
                || hasAnyEnabledAdapterConfig(supplementalSocketAdapterConfigs)
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

    public MessageQueue<TransportOutboundMessage> getOutputQueue() {
        return outputQueue;
    }

    public void setOutputQueue(MessageQueue<TransportOutboundMessage> outputQueue) {
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

    public WorkerPresenceIngress getCustomWorkerPresenceIngress() {
        return customWorkerPresenceIngress;
    }

    public void setCustomWorkerPresenceIngress(WorkerPresenceIngress customWorkerPresenceIngress) {
        this.customWorkerPresenceIngress = customWorkerPresenceIngress;
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

    public List<WebSocketAdapterConfig> getSupplementalWebSocketAdapterConfigs() {
        return supplementalWebSocketAdapterConfigs.stream()
                .map(WebSocketAdapterConfig::new)
                .toList();
    }

    public void addSupplementalWebSocketAdapterConfig(WebSocketAdapterConfig config) {
        if (config == null) {
            return;
        }
        List<WebSocketAdapterConfig> updated = new ArrayList<>(supplementalWebSocketAdapterConfigs);
        updated.add(new WebSocketAdapterConfig(config));
        supplementalWebSocketAdapterConfigs = List.copyOf(updated);
    }

    public List<SocketAdapterConfig> getSupplementalSocketAdapterConfigs() {
        return supplementalSocketAdapterConfigs.stream()
                .map(SocketAdapterConfig::new)
                .toList();
    }

    public void addSupplementalSocketAdapterConfig(SocketAdapterConfig config) {
        if (config == null) {
            return;
        }
        List<SocketAdapterConfig> updated = new ArrayList<>(supplementalSocketAdapterConfigs);
        updated.add(new SocketAdapterConfig(config));
        supplementalSocketAdapterConfigs = List.copyOf(updated);
    }

    public WorkerTransportRuntimeFactory getWorkerTransportRuntimeFactory() {
        return workerTransportRuntimeFactory;
    }

    public Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory() {
        return endpointLeaseStoreFactory;
    }

    public void setEndpointLeaseStoreFactory(Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory) {
        this.endpointLeaseStoreFactory = endpointLeaseStoreFactory;
    }

    public long getEndpointLeaseMillis() {
        return endpointLeaseMillis;
    }

    public void setEndpointLeaseMillis(long endpointLeaseMillis) {
        if (endpointLeaseMillis <= 0L) {
            throw new IllegalArgumentException("endpointLeaseMillis must be greater than 0");
        }
        this.endpointLeaseMillis = endpointLeaseMillis;
    }

    public long getAdapterMailboxConsumerLeaseMillis() {
        return adapterMailboxConsumerLeaseMillis;
    }

    public void setAdapterMailboxConsumerLeaseMillis(long adapterMailboxConsumerLeaseMillis) {
        if (adapterMailboxConsumerLeaseMillis <= 0L) {
            throw new IllegalArgumentException("adapterMailboxConsumerLeaseMillis must be greater than 0");
        }
        this.adapterMailboxConsumerLeaseMillis = adapterMailboxConsumerLeaseMillis;
    }

    public void setWorkerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
        this.workerTransportRuntimeFactory = workerTransportRuntimeFactory;
    }

    public Supplier<TransportDeliveryStore> getDeliveryStoreFactory() {
        return deliveryStoreFactory;
    }

    public void setDeliveryStoreFactory(Supplier<TransportDeliveryStore> deliveryStoreFactory) {
        this.deliveryStoreFactory = deliveryStoreFactory;
    }

    public Supplier<TransportDeliveryCommandHandoff> getDeliveryCommandHandoffFactory() {
        return deliveryCommandHandoffFactory;
    }

    public void setDeliveryCommandHandoffFactory(
            Supplier<TransportDeliveryCommandHandoff> deliveryCommandHandoffFactory) {
        this.deliveryCommandHandoffFactory = deliveryCommandHandoffFactory;
    }

    public Supplier<RedisTransportResultIngressChannel> getTaskResultInboxFactory() {
        return taskResultInboxFactory;
    }

    public void setTaskResultInboxFactory(Supplier<RedisTransportResultIngressChannel> taskResultInboxFactory) {
        this.taskResultInboxFactory = taskResultInboxFactory;
    }

    public Supplier<RedisTransportDeliveryFailureChannel> getDeliveryFailureInboxFactory() {
        return deliveryFailureInboxFactory;
    }

    public void setDeliveryFailureInboxFactory(Supplier<RedisTransportDeliveryFailureChannel> deliveryFailureInboxFactory) {
        this.deliveryFailureInboxFactory = deliveryFailureInboxFactory;
    }

    public TransportAdapterBootstrap getPrimaryTransportAdapterBootstrap() {
        return primaryTransportAdapterBootstrap;
    }

    public void setPrimaryTransportAdapterBootstrap(
            TransportAdapterBootstrap primaryTransportAdapterBootstrap) {
        this.primaryTransportAdapterBootstrap = primaryTransportAdapterBootstrap;
    }

    public List<TransportAdapterBootstrap> getSupplementalTransportAdapterBootstraps() {
        return supplementalTransportAdapterBootstraps;
    }

    public void setSupplementalTransportAdapterBootstraps(
            List<TransportAdapterBootstrap> supplementalTransportAdapterBootstraps) {
        this.supplementalTransportAdapterBootstraps = supplementalTransportAdapterBootstraps == null
                ? List.of()
                : List.copyOf(supplementalTransportAdapterBootstraps);
    }

    public void addSupplementalTransportAdapterBootstrap(
            TransportAdapterBootstrap transportAdapterBootstrap) {
        if (transportAdapterBootstrap == null) {
            return;
        }
        List<TransportAdapterBootstrap> bootstraps =
                new ArrayList<>(supplementalTransportAdapterBootstraps);
        bootstraps.add(transportAdapterBootstrap);
        supplementalTransportAdapterBootstraps = List.copyOf(bootstraps);
    }

    public int getMaxDeliveryQueuedItems() {
        return maxDeliveryQueuedItems;
    }

    public void setMaxDeliveryQueuedItems(int maxDeliveryQueuedItems) {
        if (maxDeliveryQueuedItems <= 0) {
            throw new IllegalArgumentException("maxDeliveryQueuedItems must be positive");
        }
        this.maxDeliveryQueuedItems = maxDeliveryQueuedItems;
    }

    public int getMaxDeliveryItemsPerRoute() {
        return maxDeliveryItemsPerRoute;
    }

    public void setMaxDeliveryItemsPerRoute(int maxDeliveryItemsPerRoute) {
        if (maxDeliveryItemsPerRoute <= 0) {
            throw new IllegalArgumentException("maxDeliveryItemsPerRoute must be positive");
        }
        this.maxDeliveryItemsPerRoute = maxDeliveryItemsPerRoute;
    }

    public long getEventHandlerTimeoutMillis() {
        return eventHandlerTimeoutMillis;
    }

    public TransportRuntimeRole getRuntimeRole() {
        return runtimeRole;
    }

    public void setRuntimeRole(TransportRuntimeRole runtimeRole) {
        this.runtimeRole = runtimeRole == null ? TransportRuntimeRole.EMBEDDED : runtimeRole;
    }

    public int getTransportRuntimeMaxPendingTasks() {
        return transportRuntimeMaxPendingTasks;
    }

    public void setTransportRuntimeMaxPendingTasks(int transportRuntimeMaxPendingTasks) {
        if (transportRuntimeMaxPendingTasks <= 0) {
            throw new IllegalArgumentException("transportRuntimeMaxPendingTasks must be positive");
        }
        this.transportRuntimeMaxPendingTasks = transportRuntimeMaxPendingTasks;
    }

    public int getEventRuntimeMaxPendingTasks() {
        return eventRuntimeMaxPendingTasks;
    }

    public void setEventRuntimeMaxPendingTasks(int eventRuntimeMaxPendingTasks) {
        if (eventRuntimeMaxPendingTasks <= 0) {
            throw new IllegalArgumentException("eventRuntimeMaxPendingTasks must be positive");
        }
        this.eventRuntimeMaxPendingTasks = eventRuntimeMaxPendingTasks;
    }

    public void setEventHandlerTimeoutMillis(long eventHandlerTimeoutMillis) {
        if (eventHandlerTimeoutMillis < 0) {
            throw new IllegalArgumentException("eventHandlerTimeoutMillis must be greater than or equal to 0");
        }
        this.eventHandlerTimeoutMillis = eventHandlerTimeoutMillis;
    }

    Supplier<TransportDeliveryStore> deliveryStoreFactory() {
        return deliveryStoreFactory;
    }

    Supplier<TransportDeliveryCommandHandoff> deliveryCommandHandoffFactory() {
        return deliveryCommandHandoffFactory;
    }

    Supplier<RedisTransportResultIngressChannel> taskResultInboxFactory() {
        return taskResultInboxFactory;
    }

    Supplier<RedisTransportDeliveryFailureChannel> deliveryFailureInboxFactory() {
        return deliveryFailureInboxFactory;
    }

    public TransportRuntimeComposition snapshotRuntimeComposition() {
        return new TransportRuntimeComposition(this);
    }

    private static boolean hasAnyEnabledAdapterConfig(List<?> configs) {
        for (Object config : configs) {
            if (config instanceof WebSocketAdapterConfig webSocket
                    && (webSocket.isEnabled() || webSocket.isServerEnabled())) {
                return true;
            }
            if (config instanceof SocketAdapterConfig socket
                    && (socket.isEnabled() || socket.isServerEnabled())) {
                return true;
            }
        }
        return false;
    }
}

