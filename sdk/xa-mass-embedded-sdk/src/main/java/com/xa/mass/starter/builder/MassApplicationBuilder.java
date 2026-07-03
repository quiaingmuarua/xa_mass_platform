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
import com.xa.mass.transport.starter.EmbeddedSocketAdapterDeclaration;
import com.xa.mass.transport.starter.EmbeddedTransportBackendDeclaration;
import com.xa.mass.transport.starter.EmbeddedWebSocketAdapterDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
        EmbeddedWebSocketAdapterDeclaration webSocket = transportConfig.getBundledWebSocketAdapterDeclaration();
        EmbeddedSocketAdapterDeclaration socket = transportConfig.getBundledSocketAdapterDeclaration();
        summaries.add("websocket(enabled=" + webSocket.enabled()
                + ",adapterId=" + webSocket.adapterId()
                + ",serverEnabled=" + webSocket.serverEnabled()
                + ",port=" + webSocket.serverPort()
                + ",path=" + webSocket.endpointPath()
                + ")");
        summaries.add("socket(enabled=" + socket.enabled()
                + ",adapterId=" + socket.adapterId()
                + ",serverEnabled=" + socket.serverEnabled()
                + ",host=" + socket.bindHost()
                + ",port=" + socket.serverPort()
                + ")");
        for (EmbeddedWebSocketAdapterDeclaration extra : transportConfig.getSupplementalWebSocketAdapterDeclarations()) {
            summaries.add("websocket+(" + extra.adapterId()
                    + ",enabled=" + extra.enabled()
                    + ",serverEnabled=" + extra.serverEnabled()
                    + ",port=" + extra.serverPort()
                    + ",path=" + extra.endpointPath()
                    + ")");
        }
        for (EmbeddedSocketAdapterDeclaration extra : transportConfig.getSupplementalSocketAdapterDeclarations()) {
            summaries.add("socket+(" + extra.adapterId()
                    + ",enabled=" + extra.enabled()
                    + ",serverEnabled=" + extra.serverEnabled()
                    + ",host=" + extra.bindHost()
                    + ",port=" + extra.serverPort()
                    + ")");
        }

        return summaries.toString();
    }

    public static class TransportBuilder {
        protected final TransportConfig config;

        public TransportBuilder(TransportConfig config) {
            this.config = config;
        }

        public TransportBuilder webSocketAdapter(Consumer<WebSocketAdapterBuilder> webSocketConfigurer) {
            EmbeddedWebSocketAdapterDeclaration declaration = config.getBundledWebSocketAdapterDeclaration();
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(declaration);
            webSocketConfigurer.accept(builder);
            config.setBundledWebSocketAdapterDeclaration(declaration);
            return this;
        }

        public TransportBuilder addWebSocketAdapter(Consumer<WebSocketAdapterBuilder> webSocketConfigurer) {
            EmbeddedWebSocketAdapterDeclaration extra = new EmbeddedWebSocketAdapterDeclaration();
            WebSocketAdapterBuilder builder = new WebSocketAdapterBuilder(extra);
            webSocketConfigurer.accept(builder);
            config.addSupplementalWebSocketAdapterDeclaration(extra);
            return this;
        }

        public TransportBuilder socketAdapter(Consumer<SocketAdapterBuilder> socketConfigurer) {
            EmbeddedSocketAdapterDeclaration declaration = config.getBundledSocketAdapterDeclaration();
            SocketAdapterBuilder builder = new SocketAdapterBuilder(declaration);
            socketConfigurer.accept(builder);
            config.setBundledSocketAdapterDeclaration(declaration);
            return this;
        }

        public TransportBuilder addSocketAdapter(Consumer<SocketAdapterBuilder> socketConfigurer) {
            EmbeddedSocketAdapterDeclaration extra = new EmbeddedSocketAdapterDeclaration();
            SocketAdapterBuilder builder = new SocketAdapterBuilder(extra);
            socketConfigurer.accept(builder);
            config.addSupplementalSocketAdapterDeclaration(extra);
            return this;
        }

        public TransportBuilder redisPollingDeliveryQueue(String redisUri) {
            return redisPollingDeliveryQueue(
                    redisUri,
                    EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_POLLING_DELIVERY_NAMESPACE
            );
        }

        public TransportBuilder redisPollingDeliveryQueue(String redisUri, String namespacePrefix) {
            config.setBackendDeclaration(config.getBackendDeclaration().toBuilder()
                    .pollingDeliveryRedis(requireRedisUri(redisUri), requireNamespacePrefix(namespacePrefix))
                    .build());
            return this;
        }

        public TransportBuilder redisDispatchQueue(String redisUri) {
            return redisDispatchQueue(
                    redisUri,
                    EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_DISPATCH_NAMESPACE
            );
        }

        public TransportBuilder redisDispatchQueue(String redisUri, String namespacePrefix) {
            config.setBackendDeclaration(config.getBackendDeclaration().toBuilder()
                    .dispatchRedis(requireRedisUri(redisUri), requireNamespacePrefix(namespacePrefix))
                    .build());
            return this;
        }

        public TransportBuilder redisResultIngressQueue(String redisUri) {
            return redisResultIngressQueue(
                    redisUri,
                    EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_RESULT_INGRESS_NAMESPACE
            );
        }

        public TransportBuilder redisResultIngressQueue(String redisUri, String namespacePrefix) {
            config.setBackendDeclaration(config.getBackendDeclaration().toBuilder()
                    .resultIngressRedis(requireRedisUri(redisUri), requireNamespacePrefix(namespacePrefix))
                    .build());
            return this;
        }

        public TransportBuilder redisDistributedChannels(String redisUri) {
            return redisDispatchQueue(redisUri, EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_DISPATCH_NAMESPACE)
                    .redisResultIngressQueue(redisUri, EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_RESULT_INGRESS_NAMESPACE)
                    .redisEndpointLeaseStore(redisUri, EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_ENDPOINT_LEASE_NAMESPACE)
                    .redisPollingDeliveryQueue(redisUri, EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_POLLING_DELIVERY_NAMESPACE);
        }

        public TransportBuilder redisDistributedChannels(String redisUri, String namespacePrefix) {
            String normalizedNamespacePrefix = requireNamespacePrefix(namespacePrefix);
            return redisDispatchQueue(redisUri, normalizedNamespacePrefix + ":dispatch")
                    .redisResultIngressQueue(redisUri, normalizedNamespacePrefix + ":result-ingress")
                    .redisEndpointLeaseStore(redisUri, normalizedNamespacePrefix + ":endpoint-lease")
                    .redisPollingDeliveryQueue(redisUri, normalizedNamespacePrefix + ":polling-delivery");
        }

        public TransportBuilder redisEndpointLeaseStore(String redisUri) {
            return redisEndpointLeaseStore(
                    redisUri,
                    EmbeddedTransportBackendDeclaration.DEFAULT_REDIS_ENDPOINT_LEASE_NAMESPACE
            );
        }

        public TransportBuilder redisEndpointLeaseStore(String redisUri, String namespacePrefix) {
            config.setBackendDeclaration(config.getBackendDeclaration().toBuilder()
                    .endpointLeaseRedis(requireRedisUri(redisUri), requireNamespacePrefix(namespacePrefix))
                    .build());
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
        private final EmbeddedWebSocketAdapterDeclaration adapterDeclaration;

        public WebSocketAdapterBuilder(EmbeddedWebSocketAdapterDeclaration declaration) {
            this.adapterDeclaration = Objects.requireNonNull(declaration, "declaration");
        }

        public WebSocketAdapterBuilder enabled(boolean enabled) {
            adapterDeclaration.enabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder adapterId(String adapterId) {
            adapterDeclaration.adapterId(adapterId);
            return this;
        }

        public WebSocketAdapterBuilder serverEnabled(boolean enabled) {
            adapterDeclaration.serverEnabled(enabled);
            return this;
        }

        public WebSocketAdapterBuilder server(int port) {
            return server(port, "/ws");
        }

        public WebSocketAdapterBuilder server(int port, String endpointPath) {
            adapterDeclaration.serverPort(port);
            adapterDeclaration.endpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder endpointPath(String endpointPath) {
            adapterDeclaration.endpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterBuilder maxConnections(int maxConnections) {
            adapterDeclaration.maxConnections(maxConnections);
            return this;
        }
    }

    public static class SocketAdapterBuilder {
        private final EmbeddedSocketAdapterDeclaration declaration;

        public SocketAdapterBuilder(EmbeddedSocketAdapterDeclaration declaration) {
            this.declaration = Objects.requireNonNull(declaration, "declaration");
        }

        public SocketAdapterBuilder enabled(boolean enabled) {
            declaration.enabled(enabled);
            return this;
        }

        public SocketAdapterBuilder adapterId(String adapterId) {
            declaration.adapterId(adapterId);
            return this;
        }

        public SocketAdapterBuilder serverEnabled(boolean enabled) {
            declaration.serverEnabled(enabled);
            return this;
        }

        public SocketAdapterBuilder server(int port) {
            declaration.serverPort(port);
            return this;
        }

        public SocketAdapterBuilder maxConnections(int maxConnections) {
            declaration.maxConnections(maxConnections);
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
