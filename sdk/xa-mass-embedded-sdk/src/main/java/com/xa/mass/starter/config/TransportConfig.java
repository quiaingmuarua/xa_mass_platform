package com.xa.mass.starter.config;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.RedisTaskResultIngestChannel;
import com.xa.mass.transport.runtime.RedisTransportDispatchFailureChannel;
import com.xa.mass.transport.runtime.RuntimeEventBusWorkerSystemEventChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.base.runtime.dispatch.TaskDispatchHandoff;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
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

    private WorkerSystemEventChannel customSystemEventChannel;
    private WorkerEndpointRegistry workerEndpointRegistry;
    private Supplier<WorkerEndpointRegistry> endpointRegistryFactory;
    private Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver;
    private Supplier<TransportRouteOwnerStore> routeOwnerStoreFactory;
    private WebSocketAdapterConfig bundledWebSocketAdapterConfig = new WebSocketAdapterConfig();
    private SocketAdapterConfig bundledSocketAdapterConfig = new SocketAdapterConfig();
    private List<WebSocketAdapterConfig> supplementalWebSocketAdapterConfigs = List.of();
    private List<SocketAdapterConfig> supplementalSocketAdapterConfigs = List.of();
    private WorkerTransportRuntimeFactory workerTransportRuntimeFactory;
    private Supplier<TransportDeliveryStore> deliveryStoreFactory;
    private Supplier<TaskDispatchHandoff> taskDispatchHandoffFactory;
    private Supplier<RedisTaskResultIngestChannel> taskResultInboxFactory;
    private Supplier<RedisTransportDispatchFailureChannel> dispatchFailureInboxFactory;
    private Supplier<TransportNodeRegistry> transportNodeRegistryFactory;
    private TransportAdapterBootstrap primaryTransportAdapterBootstrap;
    private List<TransportAdapterBootstrap> supplementalTransportAdapterBootstraps = List.of();
    private int maxDeliveryQueuedItems = DEFAULT_MAX_DELIVERY_QUEUED_ITEMS;
    private int maxDeliveryItemsPerRoute = DEFAULT_MAX_DELIVERY_ITEMS_PER_ROUTE;
    private int transportRuntimeMaxPendingTasks = DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS;
    private int eventRuntimeMaxPendingTasks = DEFAULT_RUNTIME_EXECUTOR_MAX_PENDING_TASKS;
    private long eventHandlerTimeoutMillis;
    private long routeOwnerLeaseMillis = 30_000L;
    private TransportRuntimeRole runtimeRole = TransportRuntimeRole.EMBEDDED;
    private String transportNodeId = java.util.UUID.randomUUID().toString();

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
        this.routeOwnerStoreFactory = source.routeOwnerStoreFactory;
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
        this.taskDispatchHandoffFactory = source.taskDispatchHandoffFactory;
        this.taskResultInboxFactory = source.taskResultInboxFactory;
        this.dispatchFailureInboxFactory = source.dispatchFailureInboxFactory;
        this.transportNodeRegistryFactory = source.transportNodeRegistryFactory;
        this.primaryTransportAdapterBootstrap = source.primaryTransportAdapterBootstrap;
        this.supplementalTransportAdapterBootstraps = List.copyOf(source.supplementalTransportAdapterBootstraps);
        this.maxDeliveryQueuedItems = source.maxDeliveryQueuedItems;
        this.maxDeliveryItemsPerRoute = source.maxDeliveryItemsPerRoute;
        this.transportRuntimeMaxPendingTasks = source.transportRuntimeMaxPendingTasks;
        this.eventRuntimeMaxPendingTasks = source.eventRuntimeMaxPendingTasks;
        this.eventHandlerTimeoutMillis = source.eventHandlerTimeoutMillis;
        this.routeOwnerLeaseMillis = source.routeOwnerLeaseMillis;
        this.runtimeRole = source.runtimeRole;
        this.transportNodeId = source.transportNodeId;
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

    public Supplier<TransportRouteOwnerStore> routeOwnerStoreFactory() {
        return routeOwnerStoreFactory;
    }

    public void setRouteOwnerStoreFactory(Supplier<TransportRouteOwnerStore> routeOwnerStoreFactory) {
        this.routeOwnerStoreFactory = routeOwnerStoreFactory;
    }

    public long getRouteOwnerLeaseMillis() {
        return routeOwnerLeaseMillis;
    }

    public void setRouteOwnerLeaseMillis(long routeOwnerLeaseMillis) {
        if (routeOwnerLeaseMillis <= 0L) {
            throw new IllegalArgumentException("routeOwnerLeaseMillis must be greater than 0");
        }
        this.routeOwnerLeaseMillis = routeOwnerLeaseMillis;
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

    public Supplier<TaskDispatchHandoff> getTaskDispatchHandoffFactory() {
        return taskDispatchHandoffFactory;
    }

    public void setTaskDispatchHandoffFactory(Supplier<TaskDispatchHandoff> taskDispatchHandoffFactory) {
        this.taskDispatchHandoffFactory = taskDispatchHandoffFactory;
    }

    public Supplier<RedisTaskResultIngestChannel> getTaskResultInboxFactory() {
        return taskResultInboxFactory;
    }

    public void setTaskResultInboxFactory(Supplier<RedisTaskResultIngestChannel> taskResultInboxFactory) {
        this.taskResultInboxFactory = taskResultInboxFactory;
    }

    public Supplier<RedisTransportDispatchFailureChannel> getDispatchFailureInboxFactory() {
        return dispatchFailureInboxFactory;
    }

    public void setDispatchFailureInboxFactory(Supplier<RedisTransportDispatchFailureChannel> dispatchFailureInboxFactory) {
        this.dispatchFailureInboxFactory = dispatchFailureInboxFactory;
    }

    public Supplier<TransportNodeRegistry> getTransportNodeRegistryFactory() {
        return transportNodeRegistryFactory;
    }

    public void setTransportNodeRegistryFactory(Supplier<TransportNodeRegistry> transportNodeRegistryFactory) {
        this.transportNodeRegistryFactory = transportNodeRegistryFactory;
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

    public String getTransportNodeId() {
        return transportNodeId;
    }

    public void setTransportNodeId(String transportNodeId) {
        if (transportNodeId == null || transportNodeId.isBlank()) {
            throw new IllegalArgumentException("transportNodeId must not be blank");
        }
        this.transportNodeId = transportNodeId.trim();
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

    Supplier<WorkerEndpointRegistry> endpointRegistryFactory() {
        return endpointRegistryFactory;
    }

    Supplier<TransportDeliveryStore> deliveryStoreFactory() {
        return deliveryStoreFactory;
    }

    Supplier<TaskDispatchHandoff> taskDispatchHandoffFactory() {
        return taskDispatchHandoffFactory;
    }

    Supplier<RedisTaskResultIngestChannel> taskResultInboxFactory() {
        return taskResultInboxFactory;
    }

    Supplier<RedisTransportDispatchFailureChannel> dispatchFailureInboxFactory() {
        return dispatchFailureInboxFactory;
    }

    Supplier<TransportNodeRegistry> transportNodeRegistryFactory() {
        return transportNodeRegistryFactory;
    }

    Function<WorkerEndpointRegistry, WorkerSystemEventChannel> systemEventChannelResolver() {
        return systemEventChannelResolver;
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

