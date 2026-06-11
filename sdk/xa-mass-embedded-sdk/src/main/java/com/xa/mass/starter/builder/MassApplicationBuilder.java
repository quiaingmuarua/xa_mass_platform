package com.xa.mass.starter.builder;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.engine.PollingIdleBackoffPolicy;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.starter.EngineRuntimeBridge;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.RuntimeEventBusEngineBridge;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.RuntimeEventBusWorkerSystemEventChannel;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;
import com.xa.mass.transport.runtime.RedisTaskResultIngestChannel;
import com.xa.mass.transport.runtime.RedisTransportDispatchFailureChannel;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.dispatch.RedisNodeTargetedTaskDispatchHandoff;
import com.xa.mass.transport.runtime.dispatch.RedisTaskDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.runtime.node.RedisTransportNodeRegistry;
import com.xa.mass.transport.runtime.presence.RedisWorkerPresenceStore;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        autoWireRuntimeEventBusBridge(transportSnapshot, engineSnapshot);
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

    private static void autoWireRuntimeEventBusBridge(TransportConfig transportSnapshot, EngineConfig engineSnapshot) {
        if (!engineSnapshot.isEnabled()) {
            return;
        }
        if (transportSnapshot.getRuntimeRole() == TransportRuntimeRole.TRANSPORT_CONSUMER) {
            return;
        }
        if (engineSnapshot.getRuntimeBridge() != EngineRuntimeBridge.noop()) {
            return;
        }

        TransportRuntimeComposition transportRuntimeComposition = transportSnapshot.snapshotRuntimeComposition();
        WorkerSystemEventChannel systemEventChannel = transportRuntimeComposition.resolveSystemEventChannel();
        if (systemEventChannel instanceof RuntimeEventBusWorkerSystemEventChannel) {
            engineSnapshot.setRuntimeBridge(RuntimeEventBusEngineBridge.runtimeBus());
        }
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
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(config.getBundledWebSocketAdapterConfig());
            webSocketAdapterConfigurator.accept(builder);
            return this;
        }

        public TransportBuilder addWebSocketAdapter(Consumer<WebSocketAdapterBuilder> webSocketAdapterConfigurator) {
            WebSocketAdapterConfig extra = new WebSocketAdapterConfig();
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(extra);
            webSocketAdapterConfigurator.accept(builder);
            config.addSupplementalWebSocketAdapterConfig(extra);
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

        public TransportBuilder workerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
            config.setWorkerTransportRuntimeFactory(workerTransportRuntimeFactory);
            return this;
        }

        public TransportBuilder deliveryStoreFactory(Supplier<TransportDeliveryStore> deliveryStoreFactory) {
            config.setDeliveryStoreFactory(Objects.requireNonNull(deliveryStoreFactory, "deliveryStoreFactory"));
            return this;
        }

        public TransportBuilder redisDeliveryStore(String redisUri) {
            return redisDeliveryStore(redisUri, RedisTransportDeliveryStore.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisDeliveryStore(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = Objects.requireNonNull(redisUri, "redisUri").trim();
            if (normalizedRedisUri.isBlank()) {
                throw new IllegalArgumentException("redisUri must not be blank");
            }
            String normalizedNamespacePrefix = Objects.requireNonNull(namespacePrefix, "namespacePrefix").trim();
            if (normalizedNamespacePrefix.isBlank()) {
                throw new IllegalArgumentException("namespacePrefix must not be blank");
            }
            config.setDeliveryStoreFactory(() -> new RedisTransportDeliveryStore(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    config.getMaxDeliveryQueuedItems(),
                    config.getMaxDeliveryItemsPerRoute()
            ));
            return this;
        }

        public TransportBuilder redisDispatchHandoff(String redisUri) {
            return redisDispatchHandoff(redisUri, RedisTaskDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisDispatchHandoff(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setTaskDispatchHandoffFactory(() -> new RedisTaskDispatchHandoff(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTaskDispatchHandoff.DEFAULT_MAX_QUEUED_BATCHES
            ));
            return this;
        }

        public TransportBuilder redisNodeTargetedDispatchHandoff(String redisUri) {
            return redisNodeTargetedDispatchHandoff(redisUri, RedisNodeTargetedTaskDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisNodeTargetedDispatchHandoff(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setTaskDispatchHandoffFactory(() -> new RedisNodeTargetedTaskDispatchHandoff(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    config.getTransportNodeId(),
                    RedisNodeTargetedTaskDispatchHandoff.DEFAULT_MAX_QUEUED_BATCHES_PER_NODE
            ));
            return this;
        }

        public TransportBuilder redisResultInbox(String redisUri) {
            return redisResultInbox(redisUri, RedisTaskResultIngestChannel.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisResultInbox(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setTaskResultInboxFactory(() -> new RedisTaskResultIngestChannel(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTaskResultIngestChannel.DEFAULT_MAX_QUEUED_RESULTS
            ));
            return this;
        }

        public TransportBuilder redisDispatchFailureInbox(String redisUri) {
            return redisDispatchFailureInbox(redisUri, RedisTransportDispatchFailureChannel.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisDispatchFailureInbox(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setDispatchFailureInboxFactory(() -> new RedisTransportDispatchFailureChannel(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTransportDispatchFailureChannel.DEFAULT_MAX_QUEUED_FAILURES
            ));
            return this;
        }

        public TransportBuilder redisDistributedChannels(String redisUri) {
            return redisNodeTargetedDispatchHandoff(redisUri, RedisTransportNamespaces.DISPATCH_NODE)
                    .redisResultInbox(redisUri, RedisTransportNamespaces.RESULT_INBOX)
                    .redisDispatchFailureInbox(redisUri, RedisTransportNamespaces.DISPATCH_FAILURE)
                    .redisPresenceStore(redisUri, RedisTransportNamespaces.PRESENCE)
                    .redisDeliveryStore(redisUri, RedisTransportNamespaces.DELIVERY)
                    .redisTransportNodeRegistry(redisUri, RedisTransportNamespaces.NODES);
        }

        public TransportBuilder redisDistributedChannels(String redisUri, String namespacePrefix) {
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            return redisNodeTargetedDispatchHandoff(redisUri, normalizedNamespacePrefix + ":dispatch-node")
                    .redisResultInbox(redisUri, normalizedNamespacePrefix + ":result-inbox")
                    .redisDispatchFailureInbox(redisUri, normalizedNamespacePrefix + ":dispatch-failure")
                    .redisPresenceStore(redisUri, normalizedNamespacePrefix + ":presence")
                    .redisDeliveryStore(redisUri, normalizedNamespacePrefix + ":delivery")
                    .redisTransportNodeRegistry(redisUri, normalizedNamespacePrefix + ":nodes");
        }

        public TransportBuilder presenceStoreFactory(Supplier<com.xa.mass.transport.presence.WorkerPresenceStore> presenceStoreFactory) {
            config.setPresenceStoreFactory(Objects.requireNonNull(presenceStoreFactory, "presenceStoreFactory"));
            return this;
        }

        public TransportBuilder redisPresenceStore(String redisUri) {
            return redisPresenceStore(redisUri, RedisWorkerPresenceStore.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisPresenceStore(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = Objects.requireNonNull(redisUri, "redisUri").trim();
            if (normalizedRedisUri.isBlank()) {
                throw new IllegalArgumentException("redisUri must not be blank");
            }
            String normalizedNamespacePrefix = Objects.requireNonNull(namespacePrefix, "namespacePrefix").trim();
            if (normalizedNamespacePrefix.isBlank()) {
                throw new IllegalArgumentException("namespacePrefix must not be blank");
            }
            config.setPresenceStoreFactory(() -> new RedisWorkerPresenceStore(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    config.getWorkerPresenceLeaseMillis(),
                    config.getTransportNodeId()
            ));
            return this;
        }

        public TransportBuilder redisTransportNodeRegistry(String redisUri) {
            return redisTransportNodeRegistry(redisUri, RedisTransportNodeRegistry.DEFAULT_NAMESPACE_PREFIX);
        }

        public TransportBuilder redisTransportNodeRegistry(String redisUri, String namespacePrefix) {
            String normalizedRedisUri = requireRedisUri(redisUri);
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            config.setTransportNodeRegistryFactory(() -> new RedisTransportNodeRegistry(
                    normalizedRedisUri,
                    normalizedNamespacePrefix,
                    RedisTransportNodeRegistry.DEFAULT_LEASE_MILLIS
            ));
            return this;
        }

        public TransportBuilder transportNodeId(String transportNodeId) {
            config.setTransportNodeId(transportNodeId);
            return this;
        }

        public TransportBuilder maxDeliveryQueuedItems(int maxDeliveryQueuedItems) {
            config.setMaxDeliveryQueuedItems(maxDeliveryQueuedItems);
            return this;
        }

        public TransportBuilder maxDeliveryItemsPerRoute(int maxDeliveryItemsPerRoute) {
            config.setMaxDeliveryItemsPerRoute(maxDeliveryItemsPerRoute);
            return this;
        }

        public TransportBuilder workerPresenceLeaseMillis(long workerPresenceLeaseMillis) {
            config.setWorkerPresenceLeaseMillis(workerPresenceLeaseMillis);
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

        public TransportBuilder inputQueue(MessageQueue<String> inputQueue) {
            config.setInputQueue(inputQueue);
            return this;
        }

        public TransportBuilder outputQueue(MessageQueue<TransportOutboundMessage> outputQueue) {
            config.setOutputQueue(outputQueue);
            return this;
        }

        public TransportBuilder addSupplementalTransportAdapterBootstrap(
                TransportAdapterBootstrap transportAdapterBootstrap) {
            config.addSupplementalTransportAdapterBootstrap(transportAdapterBootstrap);
            return this;
        }

        public TransportBuilder queueMode() {
            config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
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

        /**
         * Overrides the default worker system-event channel. Useful for custom
         * transport adapters or testing with a mock channel.
         */
        public TransportBuilder systemEventChannel(WorkerSystemEventChannel channel) {
            config.setCustomSystemEventChannel(channel);
            return this;
        }
    }

    public static class WebSocketAdapterBuilder {
        private final WebSocketAdapterConfig config;

        public WebSocketAdapterBuilder(WebSocketAdapterConfig config) {
            this.config = config;
        }

        public WebSocketAdapterBuilder enabled(boolean enabled) {
            config.setEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder adapterId(String adapterId) {
            config.setAdapterId(adapterId);
            return this;
        }

        public WebSocketAdapterBuilder serverEnabled(boolean enabled) {
            config.setServerEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder server(int port) {
            return server(port, "/ws");
        }

        public WebSocketAdapterBuilder server(int port, String endpointPath) {
            config.setServerPort(port);
            config.setEndpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder endpointPath(String endpointPath) {
            config.setEndpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder maxConnections(int maxConnections) {
            config.setMaxConnections(maxConnections);
            return this;
        }

        public WebSocketAdapterBuilder transportServerFactory(
                TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
            config.setTransportServerFactory(transportServerFactory);
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
