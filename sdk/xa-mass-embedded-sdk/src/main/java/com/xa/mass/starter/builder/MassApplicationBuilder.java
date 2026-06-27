package com.xa.mass.starter.builder;

import com.xa.mass.engine.PollingIdleBackoffPolicy;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.starter.EngineRuntimeBridge;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.RedisTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.RedisPollingPendingDeliveryBuffer;
import com.xa.mass.transport.runtime.lease.RedisTransportEndpointLeaseStore;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds {@link MassApplication} instances from transport and engine configuration.
 */
public class MassApplicationBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MassApplicationBuilder.class);

    private TransportConfig transportConfig = new TransportConfig();
    private EngineConfig engineConfig = new EngineConfig();

    private MassApplicationBuilder() {
    }

    public static MassApplicationBuilder create() {
        return new MassApplicationBuilder();
    }

    public MassApplicationBuilder transport(Consumer<TransportBuilder> transportConfigurator) {
        TransportBuilder transportBuilder = new TransportBuilder(transportConfig);
        transportConfigurator.accept(transportBuilder);
        return this;
    }

    public MassApplicationBuilder engine(Consumer<EngineBuilder> engineConfigurator) {
        EngineBuilder engineBuilder = new EngineBuilder(engineConfig);
        engineConfigurator.accept(engineBuilder);
        return this;
    }

    public MassApplication build() {
        TransportConfig transportSnapshot = new TransportConfig(transportConfig);
        EngineConfig engineSnapshot = new EngineConfig(engineConfig);
        logger.info("Building MassApplication with configuration: adapters={}, transport={}, engine={}",
                describeAdapterSummary(transportSnapshot),
                transportSnapshot.isEnabled(),
                engineSnapshot.isEnabled());

        MassEngine engine = null;
        if (engineSnapshot.isEnabled()
                && transportSnapshot.getRuntimeRole() != TransportRuntimeRole.TRANSPORT_CONSUMER) {
            engine = new MassEngine(engineSnapshot);
            logger.info("MassEngine built");
        } else if (transportSnapshot.getRuntimeRole() == TransportRuntimeRole.TRANSPORT_CONSUMER) {
            logger.info("MassEngine is skipped for transport-consumer runtime role");
        } else {
            logger.info("MassEngine is disabled, skipping build");
        }

        return new MassApplication(
                engine,
                transportSnapshot,
                engineSnapshot
        );
    }

    private static String describeAdapterSummary(TransportConfig transportConfig) {
        List<String> summaries = new ArrayList<>();
        WebSocketAdapterConfig webSocket = transportConfig.getBundledWebSocketAdapterConfig();
        SocketAdapterConfig socket = transportConfig.getBundledSocketAdapterConfig();
        summaries.add("websocket(enabled=" + webSocket.isEnabled()
                + ",adapterId=" + webSocket.getAdapterId()
                + ",serverEnabled=" + webSocket.isServerEnabled()
                + ",port=" + webSocket.getServerPort()
                + ",path=" + webSocket.getEndpointPath()
                + ")");
        summaries.add("socket(enabled=" + socket.isEnabled()
                + ",adapterId=" + socket.getAdapterId()
                + ",serverEnabled=" + socket.isServerEnabled()
                + ",host=" + socket.getBindHost()
                + ",port=" + socket.getServerPort()
                + ")");
        for (WebSocketAdapterConfig extra : transportConfig.getSupplementalWebSocketAdapterConfigs()) {
            summaries.add("websocket+(" + extra.getAdapterId()
                    + ",enabled=" + extra.isEnabled()
                    + ",serverEnabled=" + extra.isServerEnabled()
                    + ",port=" + extra.getServerPort()
                    + ",path=" + extra.getEndpointPath()
                    + ")");
        }
        for (SocketAdapterConfig extra : transportConfig.getSupplementalSocketAdapterConfigs()) {
            summaries.add("socket+(" + extra.getAdapterId()
                    + ",enabled=" + extra.isEnabled()
                    + ",serverEnabled=" + extra.isServerEnabled()
                    + ",host=" + extra.getBindHost()
                    + ",port=" + extra.getServerPort()
                    + ")");
        }

        TransportAdapterBootstrap primaryBootstrap =
                transportConfig.getPrimaryTransportAdapterBootstrap();
        if (primaryBootstrap != null) {
            summaries.add(describeBootstrap("primaryBootstrap", primaryBootstrap));
        }
        List<TransportAdapterBootstrap> additionalBootstraps =
                transportConfig.getSupplementalTransportAdapterBootstraps();
        for (int i = 0; i < additionalBootstraps.size(); i++) {
            summaries.add(describeBootstrap("supplemental[" + i + "]", additionalBootstraps.get(i)));
        }

        return summaries.toString();
    }

    private static String describeBootstrap(String source,
                                            TransportAdapterBootstrap bootstrap) {
        TransportAdapterDescriptor descriptor = bootstrap.descriptor();
        if (descriptor == null) {
            return source + "(descriptor=<none>)";
        }
        return source + "(adapterId=" + descriptor.getAdapterId()
                + ",transportHint=" + descriptor.getTransportHint()
                + ")";
    }

    public static class TransportBuilder {
        protected final TransportConfig config;

        public TransportBuilder(TransportConfig config) {
            this.config = config;
        }

        public TransportBuilder webSocketAdapter(Consumer<WebSocketAdapterBuilder> webSocketAdapterConfigurator) {
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(
                    config.getBundledWebSocketAdapterConfig(),
                    config::setBundledWebSocketTransportServerFactory
            );
            webSocketAdapterConfigurator.accept(builder);
            return this;
        }

        public TransportBuilder addWebSocketAdapter(Consumer<WebSocketAdapterBuilder> webSocketAdapterConfigurator) {
            WebSocketAdapterConfig extra = new WebSocketAdapterConfig();
            AtomicReference<TransportServerFactory<WebSocketServerFactoryContext>> transportServerFactory =
                    new AtomicReference<>();
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(extra, transportServerFactory::set);
            webSocketAdapterConfigurator.accept(builder);
            config.addSupplementalWebSocketAdapterConfig(extra, transportServerFactory.get());
            return this;
        }

        public TransportBuilder socketAdapter(Consumer<SocketAdapterBuilder> socketAdapterConfigurator) {
            SocketAdapterBuilder builder = new SocketAdapterBuilder(config.getBundledSocketAdapterConfig());
            socketAdapterConfigurator.accept(builder);
            return this;
        }

        public TransportBuilder addSocketAdapter(Consumer<SocketAdapterBuilder> socketAdapterConfigurator) {
            SocketAdapterConfig extra = new SocketAdapterConfig();
            SocketAdapterBuilder builder = new SocketAdapterBuilder(extra);
            socketAdapterConfigurator.accept(builder);
            config.addSupplementalSocketAdapterConfig(extra);
            return this;
        }

        /**
         * Advanced embedded Java assembly seam for replacing the local runtime
         * binding factory.
         *
         * <p>This is an in-process JVM extension point, not an external worker
         * or cross-process adapter contract.
         */
        public TransportBuilder workerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
            config.setWorkerTransportRuntimeFactory(workerTransportRuntimeFactory);
            return this;
        }

        public TransportBuilder pollingPendingDeliveryBufferFactory(
                Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory) {
            config.setPollingPendingDeliveryBufferFactory(Objects.requireNonNull(
                    pollingPendingDeliveryBufferFactory,
                    "pollingPendingDeliveryBufferFactory"
            ));
            return this;
        }

        public TransportBuilder redisPollingPendingDeliveryBuffer(String redisUri) {
            return redisPollingPendingDeliveryBuffer(redisUri, RedisPollingPendingDeliveryBuffer.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisPollingPendingDeliveryBuffer(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = Objects.requireNonNull(redisUri, "redisUri").trim();
            if (normalizedRedisUri.isBlank()) {
                throw new IllegalArgumentException("redisUri must not be blank");
            }
            String normalizedNamespacePrefix = Objects.requireNonNull(namespacePrefix, "namespacePrefix").trim();
            if (normalizedNamespacePrefix.isBlank()) {
                throw new IllegalArgumentException("namespacePrefix must not be blank");
            }
            config.setPollingPendingDeliveryBufferFactory(() -> new RedisPollingPendingDeliveryBuffer(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    config.getMaxPollingPendingDeliveryItems(),
                    config.getMaxPollingPendingDeliveryItemsPerWorker()
            ));
            return this;
        }

        public TransportBuilder redisDispatchHandoff(String redisUri) {
            return redisDispatchHandoff(
                    redisUri,
                    RedisTransportDispatchHandoff.DEFAULT_NAMESPACE_PREFIX
            );
        }

        public TransportBuilder redisDispatchHandoff(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setDispatchHandoffFactory(() -> new RedisTransportDispatchHandoff(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTransportDispatchHandoff.DEFAULT_MAX_QUEUED_ITEMS_PER_QUEUE
            ));
            return this;
        }

        public TransportBuilder redisResultIngressQueue(String redisUri) {
            return redisResultIngressQueue(redisUri, RedisTransportResultIngressChannel.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisResultIngressQueue(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setTaskResultIngressQueueFactory(() -> new RedisTransportResultIngressChannel(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTransportResultIngressChannel.DEFAULT_MAX_QUEUED_RESULTS
            ));
            return this;
        }

        public TransportBuilder redisDeliveryFailureInbox(String redisUri) {
            return redisDeliveryFailureInbox(redisUri, RedisTransportDeliveryFailureChannel.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisDeliveryFailureInbox(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setDeliveryFailureInboxFactory(() -> new RedisTransportDeliveryFailureChannel(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTransportDeliveryFailureChannel.DEFAULT_MAX_QUEUED_FAILURES
            ));
            return this;
        }

        public TransportBuilder redisDistributedChannels(String redisUri) {
            return redisDispatchHandoff(redisUri, RedisTransportNamespaces.DISPATCH)
                    .redisResultIngressQueue(redisUri, RedisTransportNamespaces.RESULT_INGRESS)
                    .redisDeliveryFailureInbox(redisUri, RedisTransportNamespaces.DELIVERY_FAILURE)
                    .redisEndpointLeaseStore(redisUri, RedisTransportNamespaces.ENDPOINT_LEASE)
                    .redisPollingPendingDeliveryBuffer(redisUri, RedisPollingPendingDeliveryBuffer.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisDistributedChannels(String redisUri, String namespacePrefix) {
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            return redisDispatchHandoff(redisUri, normalizedNamespacePrefix + ":dispatch")
                    .redisResultIngressQueue(redisUri, normalizedNamespacePrefix + ":result-ingress")
                    .redisDeliveryFailureInbox(redisUri, normalizedNamespacePrefix + ":delivery-failure")
                    .redisEndpointLeaseStore(redisUri, normalizedNamespacePrefix + ":endpoint-lease")
                    .redisPollingPendingDeliveryBuffer(redisUri, normalizedNamespacePrefix + ":polling-delivery");
        }

        public TransportBuilder endpointLeaseStoreFactory(Supplier<com.xa.mass.transport.lease.TransportEndpointLeaseStore> endpointLeaseStoreFactory) {
            config.setEndpointLeaseStoreFactory(Objects.requireNonNull(endpointLeaseStoreFactory, "endpointLeaseStoreFactory"));
            return this;
        }

        public TransportBuilder redisEndpointLeaseStore(String redisUri) {
            return redisEndpointLeaseStore(redisUri, RedisTransportEndpointLeaseStore.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisEndpointLeaseStore(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = Objects.requireNonNull(redisUri, "redisUri").trim();
            if (normalizedRedisUri.isBlank()) {
                throw new IllegalArgumentException("redisUri must not be blank");
            }
            String normalizedNamespacePrefix = Objects.requireNonNull(namespacePrefix, "namespacePrefix").trim();
            if (normalizedNamespacePrefix.isBlank()) {
                throw new IllegalArgumentException("namespacePrefix must not be blank");
            }
            config.setEndpointLeaseStoreFactory(() -> new RedisTransportEndpointLeaseStore(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    config.getEndpointLeaseMillis()
            ));
            return this;
        }

        public TransportBuilder maxPollingPendingDeliveryItems(int maxPollingPendingDeliveryItems) {
            config.setMaxPollingPendingDeliveryItems(maxPollingPendingDeliveryItems);
            return this;
        }

        public TransportBuilder maxPollingPendingDeliveryItemsPerWorker(int maxPollingPendingDeliveryItemsPerWorker) {
            config.setMaxPollingPendingDeliveryItemsPerWorker(maxPollingPendingDeliveryItemsPerWorker);
            return this;
        }

        public TransportBuilder endpointLeaseMillis(long endpointLeaseMillis) {
            config.setEndpointLeaseMillis(endpointLeaseMillis);
            return this;
        }

        public TransportBuilder adapterMailboxConsumerAvailabilityMillis(long adapterMailboxConsumerAvailabilityMillis) {
            config.setAdapterMailboxConsumerAvailabilityMillis(adapterMailboxConsumerAvailabilityMillis);
            return this;
        }

        public TransportBuilder transportRuntimeRole(TransportRuntimeRole runtimeRole) {
            config.setRuntimeRole(runtimeRole);
            return this;
        }

        public TransportBuilder transportRuntimeMaxPendingTasks(int maxPendingTasks) {
            config.setTransportRuntimeMaxPendingTasks(maxPendingTasks);
            return this;
        }

        public TransportBuilder eventRuntimeMaxPendingTasks(int maxPendingTasks) {
            config.setEventRuntimeMaxPendingTasks(maxPendingTasks);
            return this;
        }

        public TransportBuilder eventHandlerTimeoutMillis(long eventHandlerTimeoutMillis) {
            config.setEventHandlerTimeoutMillis(eventHandlerTimeoutMillis);
            return this;
        }

        /**
         * Advanced embedded Java assembly seam for adding a local adapter
         * bootstrap contribution.
         *
         * <p>External adapters must not model themselves as Java bootstrap
         * objects; a future external adapter path needs typed queue/evidence/
         * outcome contracts instead.
         */
        public TransportBuilder addSupplementalTransportAdapterBootstrap(
                TransportAdapterBootstrap transportAdapterBootstrap) {
            config.addSupplementalTransportAdapterBootstrap(transportAdapterBootstrap);
            return this;
        }

        private String requireRedisUri(String redisUri) {
            String normalizedRedisUri = Objects.requireNonNull(redisUri, "redisUri").trim();
            if (normalizedRedisUri.isBlank()) {
                throw new IllegalArgumentException("redisUri must not be blank");
            }
            return normalizedRedisUri;
        }

        private String requireNamespacePrefix(String namespacePrefix) {
            String normalizedNamespacePrefix = Objects.requireNonNull(namespacePrefix, "namespacePrefix").trim();
            if (normalizedNamespacePrefix.isBlank()) {
                throw new IllegalArgumentException("namespacePrefix must not be blank");
            }
            return normalizedNamespacePrefix;
        }

    }

    public static class WebSocketAdapterBuilder {
        private final WebSocketAdapterConfig adapterConfig;
        private final Consumer<TransportServerFactory<WebSocketServerFactoryContext>> transportServerFactoryConsumer;

        public WebSocketAdapterBuilder(
                WebSocketAdapterConfig config,
                Consumer<TransportServerFactory<WebSocketServerFactoryContext>> transportServerFactoryConsumer) {
            this.adapterConfig = Objects.requireNonNull(config, "config");
            this.transportServerFactoryConsumer = Objects.requireNonNull(
                    transportServerFactoryConsumer,
                    "transportServerFactoryConsumer"
            );
        }

        public WebSocketAdapterBuilder enabled(boolean enabled) {
            adapterConfig.setEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder adapterId(String adapterId) {
            adapterConfig.setAdapterId(adapterId);
            return this;
        }

        public WebSocketAdapterBuilder serverEnabled(boolean enabled) {
            adapterConfig.setServerEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder server(int port) {
            return server(port, "/ws");
        }

        public WebSocketAdapterBuilder server(int port, String endpointPath) {
            adapterConfig.setServerPort(port);
            adapterConfig.setEndpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder endpointPath(String endpointPath) {
            adapterConfig.setEndpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder maxConnections(int maxConnections) {
            adapterConfig.setMaxConnections(maxConnections);
            return this;
        }

        public WebSocketAdapterBuilder transportServerFactory(
                TransportServerFactory<WebSocketServerFactoryContext> transportServerFactory) {
            transportServerFactoryConsumer.accept(transportServerFactory);
            return this;
        }
    }

    public static class SocketAdapterBuilder {
        private final SocketAdapterConfig config;

        public SocketAdapterBuilder(SocketAdapterConfig config) {
            this.config = config;
        }

        public SocketAdapterBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public SocketAdapterBuilder adapterId(String adapterId) {
            config.setAdapterId(adapterId);
            return this;
        }

        public SocketAdapterBuilder serverEnabled(boolean enabled) {
            config.setServerEnabled(enabled);
            return this;
        }

        public SocketAdapterBuilder server(int port) {
            config.setServerPort(port);
            return this;
        }

        public SocketAdapterBuilder maxConnections(int maxConnections) {
            config.setMaxConnections(maxConnections);
            return this;
        }
    }

    public static class EngineBuilder {
        private final EngineConfig config;

        public EngineBuilder(EngineConfig config) {
            this.config = config;
        }

        public EngineBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public EngineBuilder workerThreads(int workerThreads) {
            config.setWorkerThreads(workerThreads);
            return this;
        }

        public EngineBuilder assignmentRetryDelayMillis(long assignmentRetryDelayMillis) {
            config.setAssignmentRetryDelayMillis(assignmentRetryDelayMillis);
            return this;
        }

        public EngineBuilder runtimeReadyDispatchIdleBackoffMaxMillis(long maxBackoffMillis) {
            config.setRuntimeReadyDispatchIdleBackoffMaxMillis(maxBackoffMillis);
            return this;
        }

        public EngineBuilder runtimeReadyDispatchIdleBackoffPolicy(
                PollingIdleBackoffPolicy policy) {
            config.setRuntimeReadyDispatchIdleBackoffPolicy(policy);
            return this;
        }

        public EngineBuilder leaseWatchdogIntervalSeconds(long leaseWatchdogIntervalSeconds) {
            config.setLeaseWatchdogIntervalSeconds(leaseWatchdogIntervalSeconds);
            return this;
        }

        public EngineBuilder taskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
            config.setTaskMessageLeaseSeconds(taskMessageLeaseSeconds);
            return this;
        }

        public EngineBuilder bootstrapDataProvider(MassBootstrapDataProvider bootstrapDataProvider) {
            config.setBootstrapDataProvider(bootstrapDataProvider);
            return this;
        }

        public EngineBuilder taskShellStore(TaskShellStore taskShellStore) {
            config.setTaskShellStore(taskShellStore);
            return this;
        }

        public EngineBuilder taskWorkRuntime(TaskWorkRuntime taskWorkRuntime) {
            config.setTaskWorkRuntime(taskWorkRuntime);
            return this;
        }

        public EngineBuilder taskResultRuntime(TaskResultRuntime taskResultRuntime) {
            config.setTaskResultRuntime(taskResultRuntime);
            return this;
        }

        public EngineBuilder workerDeclarationStore(WorkerDeclarationStore workerDeclarationStore) {
            config.setWorkerDeclarationStore(workerDeclarationStore);
            return this;
        }

        public EngineBuilder workerRegistry(WorkerRegistry workerRegistry) {
            config.setWorkerRegistry(workerRegistry);
            return this;
        }

        public EngineBuilder workerScoreBandSlotRuntime(WorkerScoreBandSlotRuntime workerScoreBandSlotRuntime) {
            config.setWorkerScoreBandSlotRuntime(workerScoreBandSlotRuntime);
            return this;
        }

        public EngineBuilder ruleStorage(RuleStorage ruleStorage) {
            config.setRuleStorage(ruleStorage);
            return this;
        }

        public EngineBuilder executionEventSink(ExecutionEventSink executionEventSink) {
            config.setExecutionEventSink(executionEventSink);
            return this;
        }

    }
}
