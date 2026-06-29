package com.xa.mass.starter.config;

import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.polling.delivery.InMemoryPollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fixed runtime composition snapshot derived from {@link TransportConfig}.
 *
 * <p>This keeps runtime-owned resolver state out of
 * {@link com.xa.mass.starter.MassApplication} so the application manages only
 * assembled transport components rather than a live config object.
 */
public class TransportRuntimeComposition {

    private final Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory;
    private final WebSocketAdapterConfig bundledWebSocketAdapterConfig;
    private final TransportServerFactory<WebSocketServerFactoryContext> bundledWebSocketTransportServerFactory;
    private final SocketAdapterConfig bundledSocketAdapterConfig;
    private final List<TransportConfig.WebSocketAdapterAssembly> supplementalWebSocketAdapterAssemblies;
    private final List<SocketAdapterConfig> supplementalSocketAdapterConfigs;
    private final Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory;
    private final Supplier<TransportDispatchQueue> dispatchQueueFactory;
    private final Supplier<RedisTransportResultIngressChannel> taskResultIngressQueueFactory;
    private final int maxPollingPendingDeliveryItems;
    private final int maxPollingPendingDeliveryItemsPerWorker;
    private final int transportRuntimeMaxPendingTasks;
    private final int eventRuntimeMaxPendingTasks;
    private final long eventHandlerTimeoutMillis;
    private final long endpointLeaseMillis;
    private final TransportRuntimeRole runtimeRole;

    public TransportRuntimeComposition(TransportConfig source) {
        this.endpointLeaseStoreFactory = source.endpointLeaseStoreFactory();
        this.bundledWebSocketAdapterConfig = new WebSocketAdapterConfig(source.getBundledWebSocketAdapterConfig());
        this.bundledWebSocketTransportServerFactory = source.getBundledWebSocketTransportServerFactory();
        this.bundledSocketAdapterConfig = new SocketAdapterConfig(source.getBundledSocketAdapterConfig());
        this.supplementalWebSocketAdapterAssemblies = source.getSupplementalWebSocketAdapterAssemblies().stream()
                .map(assembly -> new TransportConfig.WebSocketAdapterAssembly(
                        assembly.config(),
                        assembly.transportServerFactory()
                ))
                .toList();
        this.supplementalSocketAdapterConfigs = source.getSupplementalSocketAdapterConfigs().stream()
                .map(SocketAdapterConfig::new)
                .toList();
        this.pollingPendingDeliveryBufferFactory = source.pollingPendingDeliveryBufferFactory();
        this.dispatchQueueFactory = source.getDispatchQueueFactory();
        this.taskResultIngressQueueFactory = source.taskResultIngressQueueFactory();
        this.maxPollingPendingDeliveryItems = source.getMaxPollingPendingDeliveryItems();
        this.maxPollingPendingDeliveryItemsPerWorker = source.getMaxPollingPendingDeliveryItemsPerWorker();
        this.transportRuntimeMaxPendingTasks = source.getTransportRuntimeMaxPendingTasks();
        this.eventRuntimeMaxPendingTasks = source.getEventRuntimeMaxPendingTasks();
        this.eventHandlerTimeoutMillis = source.getEventHandlerTimeoutMillis();
        this.endpointLeaseMillis = source.getEndpointLeaseMillis();
        this.runtimeRole = source.getRuntimeRole();
    }

    public boolean isEnabled() {
        return EmbeddedAdapterSpecAssembler.from(this).isUserEnabled();
    }

    public WebSocketAdapterConfig getBundledWebSocketAdapterConfig() {
        return new WebSocketAdapterConfig(bundledWebSocketAdapterConfig);
    }

    public SocketAdapterConfig getBundledSocketAdapterConfig() {
        return new SocketAdapterConfig(bundledSocketAdapterConfig);
    }

    public List<WebSocketAdapterConfig> getSupplementalWebSocketAdapterConfigs() {
        return supplementalWebSocketAdapterAssemblies.stream()
                .map(TransportConfig.WebSocketAdapterAssembly::config)
                .toList();
    }

    List<TransportConfig.WebSocketAdapterAssembly> getSupplementalWebSocketAdapterAssemblies() {
        return supplementalWebSocketAdapterAssemblies.stream()
                .map(assembly -> new TransportConfig.WebSocketAdapterAssembly(
                        assembly.config(),
                        assembly.transportServerFactory()
                ))
                .toList();
    }

    TransportServerFactory<WebSocketServerFactoryContext> getBundledWebSocketTransportServerFactory() {
        return bundledWebSocketTransportServerFactory;
    }

    public List<SocketAdapterConfig> getSupplementalSocketAdapterConfigs() {
        return supplementalSocketAdapterConfigs.stream()
                .map(SocketAdapterConfig::new)
                .toList();
    }

    public Supplier<PollingPendingDeliveryBuffer> resolvePollingPendingDeliveryBufferFactory() {
        return pollingPendingDeliveryBufferFactory != null
                ? pollingPendingDeliveryBufferFactory
                : () -> new InMemoryPollingPendingDeliveryBuffer(
                        maxPollingPendingDeliveryItems,
                        maxPollingPendingDeliveryItemsPerWorker
                );
    }

    public TransportDispatchQueue resolveTransportDispatchQueue(int defaultCapacity) {
        return dispatchQueueFactory != null
                ? dispatchQueueFactory.get()
                : new InMemoryTransportDispatchHandoff(defaultCapacity);
    }

    public RedisTransportResultIngressChannel resolveTaskResultIngressQueue() {
        if (taskResultIngressQueueFactory == null) {
            throw new IllegalStateException("Task result ingress queue is not configured for split transport runtime");
        }
        return taskResultIngressQueueFactory.get();
    }

    public Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory() {
        return endpointLeaseStoreFactory;
    }

    public long getEndpointLeaseMillis() {
        return endpointLeaseMillis;
    }

    public List<EmbeddedAdapterRuntimeSpec> resolveEmbeddedAdapterRuntimeSpecs() {
        return EmbeddedAdapterSpecAssembler.from(this).specs();
    }

    public int getMaxPollingPendingDeliveryItems() {
        return maxPollingPendingDeliveryItems;
    }

    public long getEventHandlerTimeoutMillis() {
        return eventHandlerTimeoutMillis;
    }

    public int getMaxPollingPendingDeliveryItemsPerWorker() {
        return maxPollingPendingDeliveryItemsPerWorker;
    }

    public int getTransportRuntimeMaxPendingTasks() {
        return transportRuntimeMaxPendingTasks;
    }

    public TransportRuntimeRole getRuntimeRole() {
        return runtimeRole;
    }

    public int getEventRuntimeMaxPendingTasks() {
        return eventRuntimeMaxPendingTasks;
    }

    public Map<String, TransportServerFactory<WebSocketServerFactoryContext>> resolveWebSocketServerFactoriesByAdapterId() {
        return EmbeddedAdapterSpecAssembler.from(this).webSocketServerFactoriesByAdapterId();
    }
}
