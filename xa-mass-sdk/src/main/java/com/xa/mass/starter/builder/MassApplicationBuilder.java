package com.xa.mass.starter.builder;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.MessageTransporterFactory;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
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
        if (engineSnapshot.isEnabled()) {
            engine = new MassEngine(engineSnapshot);
            logger.info("MassEngine built");
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
                + ",serverEnabled=" + webSocket.isServerEnabled()
                + ",port=" + webSocket.getServerPort()
                + ",path=" + webSocket.getEndpointPath()
                + ")");
        summaries.add("socket(enabled=" + socket.isEnabled()
                + ",serverEnabled=" + socket.isServerEnabled()
                + ",host=" + socket.getBindHost()
                + ",port=" + socket.getServerPort()
                + ")");

        TransportAdapterBootstrap<WorkerTransportMessage> primaryBootstrap =
                transportConfig.getPrimaryTransportAdapterBootstrap();
        if (primaryBootstrap != null) {
            summaries.add(describeBootstrap("primaryBootstrap", primaryBootstrap));
        }
        List<TransportAdapterBootstrap<WorkerTransportMessage>> additionalBootstraps =
                transportConfig.getSupplementalTransportAdapterBootstraps();
        for (int i = 0; i < additionalBootstraps.size(); i++) {
            summaries.add(describeBootstrap("supplemental[" + i + "]", additionalBootstraps.get(i)));
        }

        return summaries.toString();
    }

    private static String describeBootstrap(String source,
                                            TransportAdapterBootstrap<WorkerTransportMessage> bootstrap) {
        TransportAdapterDescriptor descriptor = bootstrap.descriptor();
        if (descriptor == null) {
            return source + "(descriptor=<none>)";
        }
        return source + "(adapterId=" + descriptor.getAdapterId()
                + ",transportHint=" + descriptor.getTransportHint()
                + ",aliases=" + descriptor.getAliases()
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

        public TransportBuilder socketAdapter(Consumer<SocketAdapterBuilder> socketAdapterConfigurator) {
            SocketAdapterBuilder builder = new SocketAdapterBuilder(config.getBundledSocketAdapterConfig());
            socketAdapterConfigurator.accept(builder);
            return this;
        }

        public TransportBuilder workerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
            config.setWorkerTransportRuntimeFactory(workerTransportRuntimeFactory);
            return this;
        }

        public TransportBuilder maxDeliveryQueuedItems(int maxDeliveryQueuedItems) {
            config.setMaxDeliveryQueuedItems(maxDeliveryQueuedItems);
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

        public TransportBuilder outputQueue(MessageQueue<WorkerTransportMessage> outputQueue) {
            config.setOutputQueue(outputQueue);
            return this;
        }

        public TransportBuilder addSupplementalTransportAdapterBootstrap(
                TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
            config.addSupplementalTransportAdapterBootstrap(transportAdapterBootstrap);
            return this;
        }

        public TransportBuilder queueMode() {
            config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
            return this;
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

        public EngineBuilder scheduler(TaskScheduler scheduler) {
            config.setScheduler(scheduler);
            return this;
        }

        public EngineBuilder taskManager(TaskManager taskManager) {
            config.setTaskManager(taskManager);
            return this;
        }

        public EngineBuilder workerManager(WorkerManager workerManager) {
            config.setWorkerManager(workerManager);
            return this;
        }

        public EngineBuilder ruleManager(RuleManager<Map<String, Object>> ruleManager) {
            config.setRuleManager(ruleManager);
            return this;
        }

    }
}
