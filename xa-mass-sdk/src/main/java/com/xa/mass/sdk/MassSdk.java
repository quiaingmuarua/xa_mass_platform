package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.starter.builder.MassApplicationBuilder;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.TransportServerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Consumer-facing SDK facade for embedding XA Mass Platform.
 *
 * <p>The same {@code xa-mass-sdk} artifact also carries the embedded runtime
 * composition under {@code com.xa.mass.starter}. This facade keeps a clearer
 * entry surface for library callers while still allowing advanced access to
 * the lower-level runtime builder when needed. Treat {@code com.xa.mass.sdk.*}
 * as the stable public API and {@code com.xa.mass.starter.*} as advanced
 * embedded runtime wiring.
 */
public final class MassSdk {

    private static final String API_MODE_UNSUPPORTED_MESSAGE =
            "API-based transport is not implemented yet. Use queue/polling transport or provide a real transport adapter.";

    private MassSdk() {
    }

    public static Builder builder() {
        return new Builder(MassApplicationBuilder.create());
    }

    /**
     * Creates a development runtime with auto-provisioned in-memory queues.
     */
    public static MassSdkApplication development(int port) {
        return new MassSdkApplication(MassApplicationBuilder.createDevelopment(port));
    }

    /**
     * @deprecated Use {@link #development(int)} — queues are now provisioned internally.
     * Use {@link #builder()} with a custom {@code transport()} configuration if you need
     * to provide your own queue instances.
     */
    @Deprecated(forRemoval = false)
    public static MassSdkApplication development(int port,
                                                 MessageQueue<String> inputQueue,
                                                 MessageQueue<WorkerTransportMessage> outputQueue) {
        return new MassSdkApplication(
                MassApplicationBuilder.createDevelopment(port, inputQueue, outputQueue)
        );
    }

    /**
     * Creates a production runtime with auto-provisioned in-memory queues.
     */
    public static MassSdkApplication production(int port) {
        return new MassSdkApplication(MassApplicationBuilder.createProduction(port));
    }

    /**
     * @deprecated Use {@link #production(int)} — queues are now provisioned internally.
     */
    @Deprecated(forRemoval = false)
    public static MassSdkApplication production(int port,
                                                MessageQueue<String> inputQueue,
                                                MessageQueue<WorkerTransportMessage> outputQueue) {
        return new MassSdkApplication(
                MassApplicationBuilder.createProduction(port, inputQueue, outputQueue)
        );
    }

    /**
     * @deprecated API-based transport is not implemented and now fails fast.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    public static MassSdkApplication apiMode(int port,
                                             String inputApiUrl,
                                             String outputApiUrl,
                                             String apiKey) {
        throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
    }

    public static MassSdkApplication testMode(int port) {
        return new MassSdkApplication(MassApplicationBuilder.createTest(port));
    }

    public static final class Builder {
        private final MassApplicationBuilder delegate;
        private ProjectEventCatalogRegistry projectCatalogBootstrapRegistry;

        private Builder(MassApplicationBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public Builder server(int port) {
            delegate.server(port);
            return this;
        }

        /**
         * @deprecated Prefer {@link #transportServer(int, String)} so callers do not
         * expose WebSocket-only wording in stable SDK code.
         */
        @Deprecated(forRemoval = false)
        public Builder server(int port, String transportEndpointPath) {
            delegate.server(port, transportEndpointPath);
            return this;
        }

        public Builder transportServer(int port) {
            delegate.transportServer(port);
            return this;
        }

        public Builder transportServer(int port, String transportEndpointPath) {
            delegate.transportServer(port, transportEndpointPath);
            return this;
        }

        public Builder transport(Consumer<TransportOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.transport(inner -> configurator.accept(new TransportOptions(inner)));
            return this;
        }

        /**
         * @deprecated Prefer {@link #transport(Consumer)} so stable SDK code
         * does not treat WebSocket naming as the primary transport boundary.
         */
        @Deprecated(forRemoval = false)
        public Builder websocket(Consumer<WebSocketOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.websocket(inner -> configurator.accept(new WebSocketOptions(inner)));
            return this;
        }

        public Builder engine(Consumer<EngineOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.engine(inner -> configurator.accept(new EngineOptions(inner)));
            return this;
        }

        /**
         * Seeds the SDK application's bootstrap project registry before runtime
         * event definitions are projected from the underlying event runtime.
         */
        public Builder projectCatalogBootstrap(ProjectEventCatalogRegistry projectCatalogBootstrapRegistry) {
            this.projectCatalogBootstrapRegistry =
                    Objects.requireNonNull(projectCatalogBootstrapRegistry, "projectCatalogBootstrapRegistry");
            return this;
        }

        /**
         * @deprecated Prefer {@link #projectCatalogBootstrap(ProjectEventCatalogRegistry)}
         * to avoid implying that the provided registry remains the canonical
         * runtime event catalog after startup.
         */
        @Deprecated(forRemoval = false)
        public Builder projectEventCatalog(ProjectEventCatalogRegistry projectEventCatalogRegistry) {
            return projectCatalogBootstrap(projectEventCatalogRegistry);
        }

        public MassSdkApplication build() {
            if (projectCatalogBootstrapRegistry != null) {
                return new MassSdkApplication(delegate.build(), projectCatalogBootstrapRegistry);
            }
            return new MassSdkApplication(delegate.build());
        }

        /**
         * @deprecated Prefer the SDK builder methods. This remains only for
         * advanced embedding paths that need lower-level runtime configuration.
         */
        @Deprecated(forRemoval = false)
        public MassApplicationBuilder unwrap() {
            return delegate;
        }
    }

    public static class TransportOptions {
        protected final MassApplicationBuilder.TransportBuilder delegate;

        protected TransportOptions(MassApplicationBuilder.TransportBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public TransportOptions enabled(boolean enabled) {
            delegate.enabled(enabled);
            return this;
        }

        public TransportOptions webSocketAdapter(Consumer<WebSocketAdapterOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.webSocketAdapter(inner -> configurator.accept(new WebSocketAdapterOptions(inner)));
            return this;
        }

        public TransportOptions transportServerEnabled(boolean enabled) {
            delegate.transportServerEnabled(enabled);
            return this;
        }

        public TransportOptions transportEndpointPath(String transportEndpointPath) {
            delegate.transportEndpointPath(transportEndpointPath);
            return this;
        }

        /**
         * Advanced embedding seam for replacing the default inbound transport
         * server adapter.
         */
        public TransportOptions transportServerFactory(
                TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
            delegate.transportServerFactory(transportServerFactory);
            return this;
        }

        /**
         * Advanced embedding seam for replacing the assembled set of worker
         * transport bindings used by the runtime.
         */
        public TransportOptions workerTransportRuntimeFactory(WorkerTransportRuntimeFactory workerTransportRuntimeFactory) {
            delegate.workerTransportRuntimeFactory(workerTransportRuntimeFactory);
            return this;
        }

        public TransportOptions maxConnections(int maxConnections) {
            delegate.maxConnections(maxConnections);
            return this;
        }

        public TransportOptions inputQueue(MessageQueue<String> inputQueue) {
            delegate.inputQueue(inputQueue);
            return this;
        }

        public TransportOptions outputQueue(MessageQueue<WorkerTransportMessage> outputQueue) {
            delegate.outputQueue(outputQueue);
            return this;
        }

        public TransportOptions addTransportAdapterBootstrap(
                TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
            delegate.addTransportAdapterBootstrap(transportAdapterBootstrap);
            return this;
        }

        /**
         * @deprecated API-based transport is not implemented and now fails fast.
         */
        @Deprecated(since = "2.0.0", forRemoval = false)
        public TransportOptions apiMode(String inputApiUrl, String outputApiUrl, String apiKey) {
            throw new UnsupportedOperationException(API_MODE_UNSUPPORTED_MESSAGE);
        }

        public TransportOptions queueMode() {
            delegate.queueMode();
            return this;
        }

        /**
         * @deprecated Prefer the SDK transport option methods. This remains only
         * for advanced embedding paths that need lower-level runtime configuration.
         */
        @Deprecated(forRemoval = false)
        public MassApplicationBuilder.TransportBuilder unwrap() {
            return delegate;
        }
    }

    public static final class WebSocketAdapterOptions {
        private final MassApplicationBuilder.WebSocketAdapterBuilder delegate;

        private WebSocketAdapterOptions(MassApplicationBuilder.WebSocketAdapterBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public WebSocketAdapterOptions enabled(boolean enabled) {
            delegate.enabled(enabled);
            return this;
        }

        public WebSocketAdapterOptions serverEnabled(boolean enabled) {
            delegate.serverEnabled(enabled);
            return this;
        }

        public WebSocketAdapterOptions server(int port) {
            delegate.server(port);
            return this;
        }

        public WebSocketAdapterOptions server(int port, String endpointPath) {
            delegate.server(port, endpointPath);
            return this;
        }

        public WebSocketAdapterOptions endpointPath(String endpointPath) {
            delegate.endpointPath(endpointPath);
            return this;
        }

        public WebSocketAdapterOptions maxConnections(int maxConnections) {
            delegate.maxConnections(maxConnections);
            return this;
        }

        public WebSocketAdapterOptions transportServerFactory(
                TransportServerFactory<TransportServerFactoryContext> transportServerFactory) {
            delegate.transportServerFactory(transportServerFactory);
            return this;
        }
    }

    /**
     * @deprecated Prefer {@link TransportOptions}; WebSocket is one adapter,
     * not the stable SDK transport-composition naming boundary.
     */
    @Deprecated(forRemoval = false)
    public static final class WebSocketOptions extends TransportOptions {

        private WebSocketOptions(MassApplicationBuilder.WebSocketBuilder delegate) {
            super(delegate);
        }

        /**
         * @deprecated Prefer {@link TransportOptions#unwrap()}.
         */
        @Deprecated(forRemoval = false)
        @Override
        public MassApplicationBuilder.WebSocketBuilder unwrap() {
            return (MassApplicationBuilder.WebSocketBuilder) super.unwrap();
        }
    }

    public static final class EngineOptions {
        private final MassApplicationBuilder.EngineBuilder delegate;

        private EngineOptions(MassApplicationBuilder.EngineBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public EngineOptions enabled(boolean enabled) {
            delegate.enabled(enabled);
            return this;
        }

        public EngineOptions workerThreads(int workerThreads) {
            delegate.workerThreads(workerThreads);
            return this;
        }

        public EngineOptions assignmentRetryDelayMillis(long assignmentRetryDelayMillis) {
            delegate.assignmentRetryDelayMillis(assignmentRetryDelayMillis);
            return this;
        }

        public EngineOptions leaseWatchdogIntervalSeconds(long leaseWatchdogIntervalSeconds) {
            delegate.leaseWatchdogIntervalSeconds(leaseWatchdogIntervalSeconds);
            return this;
        }

        public EngineOptions taskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
            delegate.taskMessageLeaseSeconds(taskMessageLeaseSeconds);
            return this;
        }

        public EngineOptions bootstrapDataProvider(MassBootstrapDataProvider bootstrapDataProvider) {
            delegate.bootstrapDataProvider(bootstrapDataProvider);
            return this;
        }

        public EngineOptions scheduler(TaskScheduler scheduler) {
            delegate.scheduler(scheduler);
            return this;
        }

        public EngineOptions taskManager(TaskManager taskManager) {
            delegate.taskManager(taskManager);
            return this;
        }

        public EngineOptions workerManager(WorkerManager workerManager) {
            delegate.workerManager(workerManager);
            return this;
        }

        public EngineOptions ruleManager(RuleManager<Map<String, Object>> ruleManager) {
            delegate.ruleManager(ruleManager);
            return this;
        }

        /**
         * @deprecated Prefer the SDK engine option methods. This remains only
         * for advanced embedding paths that need lower-level runtime configuration.
         */
        @Deprecated(forRemoval = false)
        public MassApplicationBuilder.EngineBuilder unwrap() {
            return delegate;
        }
    }
}
