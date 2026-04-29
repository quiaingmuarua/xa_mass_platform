package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.sdk.auth.SubmitterRegistry;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.starter.builder.MassApplicationBuilder;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
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

    private MassSdk() {
    }

    public static Builder builder() {
        return new Builder(MassApplicationBuilder.create());
    }

    public static final class Builder {
        private final MassApplicationBuilder delegate;
        private ProjectEventCatalogRegistry projectCatalogBootstrapRegistry;
        private SubmitterRegistry submitterRegistry;

        private Builder(MassApplicationBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public Builder transport(Consumer<TransportOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.transport(inner -> configurator.accept(new TransportOptions(inner)));
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

        public Builder submitterRegistry(SubmitterRegistry submitterRegistry) {
            this.submitterRegistry = Objects.requireNonNull(submitterRegistry, "submitterRegistry");
            return this;
        }

        public MassSdkApplication build() {
            if (projectCatalogBootstrapRegistry != null) {
                return submitterRegistry != null
                        ? new MassSdkApplication(delegate.build(), projectCatalogBootstrapRegistry, submitterRegistry)
                        : new MassSdkApplication(delegate.build(), projectCatalogBootstrapRegistry);
            }
            return submitterRegistry != null
                    ? new MassSdkApplication(delegate.build(), submitterRegistry)
                    : new MassSdkApplication(delegate.build());
        }

    }

    public static class TransportOptions {
        protected final MassApplicationBuilder.TransportBuilder delegate;

        protected TransportOptions(MassApplicationBuilder.TransportBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public TransportOptions webSocketAdapter(Consumer<WebSocketAdapterOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.webSocketAdapter(inner -> configurator.accept(new WebSocketAdapterOptions(inner)));
            return this;
        }

        public TransportOptions socketAdapter(Consumer<SocketAdapterOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.socketAdapter(inner -> configurator.accept(new SocketAdapterOptions(inner)));
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

        public TransportOptions maxDeliveryQueuedItems(int maxDeliveryQueuedItems) {
            delegate.maxDeliveryQueuedItems(maxDeliveryQueuedItems);
            return this;
        }

        public TransportOptions transportRuntimeMaxPendingTasks(int maxPendingTasks) {
            delegate.transportRuntimeMaxPendingTasks(maxPendingTasks);
            return this;
        }

        public TransportOptions eventRuntimeMaxPendingTasks(int maxPendingTasks) {
            delegate.eventRuntimeMaxPendingTasks(maxPendingTasks);
            return this;
        }

        public TransportOptions eventHandlerTimeoutMillis(long eventHandlerTimeoutMillis) {
            delegate.eventHandlerTimeoutMillis(eventHandlerTimeoutMillis);
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

        public TransportOptions addSupplementalTransportAdapterBootstrap(
                TransportAdapterBootstrap<WorkerTransportMessage> transportAdapterBootstrap) {
            delegate.addSupplementalTransportAdapterBootstrap(transportAdapterBootstrap);
            return this;
        }

        public TransportOptions queueMode() {
            delegate.queueMode();
            return this;
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

    public static final class SocketAdapterOptions {
        private final MassApplicationBuilder.SocketAdapterBuilder delegate;

        private SocketAdapterOptions(MassApplicationBuilder.SocketAdapterBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public SocketAdapterOptions enabled(boolean enabled) {
            delegate.enabled(enabled);
            return this;
        }

        public SocketAdapterOptions serverEnabled(boolean enabled) {
            delegate.serverEnabled(enabled);
            return this;
        }

        public SocketAdapterOptions server(int port) {
            delegate.server(port);
            return this;
        }

        public SocketAdapterOptions maxConnections(int maxConnections) {
            delegate.maxConnections(maxConnections);
            return this;
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

    }
}
