package com.xa.mass.sdk;

import com.xa.mass.engine.PollingIdleBackoffPolicy;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.sdk.auth.CredentialPrincipalStore;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.starter.builder.MassApplicationBuilder;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Consumer-facing SDK facade for embedding XA Mass Platform.
 *
 * <p>The same {@code xa-mass-embedded-sdk} artifact also carries the embedded runtime
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
        private CredentialPrincipalStore credentialPrincipalStore;

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

        public Builder credentialPrincipalStore(CredentialPrincipalStore credentialPrincipalStore) {
            this.credentialPrincipalStore = Objects.requireNonNull(credentialPrincipalStore, "credentialPrincipalStore");
            return this;
        }

        public MassSdkApplication build() {
            if (projectCatalogBootstrapRegistry != null) {
                return credentialPrincipalStore != null
                        ? new MassSdkApplication(delegate.build(), projectCatalogBootstrapRegistry, credentialPrincipalStore)
                        : new MassSdkApplication(delegate.build(), projectCatalogBootstrapRegistry);
            }
            return credentialPrincipalStore != null
                    ? new MassSdkApplication(delegate.build(), credentialPrincipalStore)
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

        public TransportOptions addWebSocketAdapter(Consumer<WebSocketAdapterOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.addWebSocketAdapter(inner -> configurator.accept(new WebSocketAdapterOptions(inner)));
            return this;
        }

        public TransportOptions socketAdapter(Consumer<SocketAdapterOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.socketAdapter(inner -> configurator.accept(new SocketAdapterOptions(inner)));
            return this;
        }

        public TransportOptions addSocketAdapter(Consumer<SocketAdapterOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.addSocketAdapter(inner -> configurator.accept(new SocketAdapterOptions(inner)));
            return this;
        }

        public TransportOptions pollingPendingDeliveryBufferFactory(
                Supplier<PollingPendingDeliveryBuffer> pollingPendingDeliveryBufferFactory) {
            delegate.pollingPendingDeliveryBufferFactory(pollingPendingDeliveryBufferFactory);
            return this;
        }

        public TransportOptions endpointLeaseStoreFactory(Supplier<TransportEndpointLeaseStore> endpointLeaseStoreFactory) {
            delegate.endpointLeaseStoreFactory(endpointLeaseStoreFactory);
            return this;
        }

        public TransportOptions redisPollingPendingDeliveryBuffer(String redisUri) {
            delegate.redisPollingPendingDeliveryBuffer(redisUri);
            return this;
        }

        public TransportOptions redisPollingPendingDeliveryBuffer(String redisUri, String namespacePrefix) {
            delegate.redisPollingPendingDeliveryBuffer(redisUri, namespacePrefix);
            return this;
        }

        public TransportOptions redisEndpointLeaseStore(String redisUri) {
            delegate.redisEndpointLeaseStore(redisUri);
            return this;
        }

        public TransportOptions redisEndpointLeaseStore(String redisUri, String namespacePrefix) {
            delegate.redisEndpointLeaseStore(redisUri, namespacePrefix);
            return this;
        }

        public TransportOptions redisDispatchHandoff(String redisUri) {
            delegate.redisDispatchHandoff(redisUri);
            return this;
        }

        public TransportOptions redisDispatchHandoff(String redisUri, String namespacePrefix) {
            delegate.redisDispatchHandoff(redisUri, namespacePrefix);
            return this;
        }

        public TransportOptions redisResultIngressQueue(String redisUri) {
            delegate.redisResultIngressQueue(redisUri);
            return this;
        }

        public TransportOptions redisResultIngressQueue(String redisUri, String namespacePrefix) {
            delegate.redisResultIngressQueue(redisUri, namespacePrefix);
            return this;
        }

        public TransportOptions redisDeliveryFailureInbox(String redisUri) {
            delegate.redisDeliveryFailureInbox(redisUri);
            return this;
        }

        public TransportOptions redisDeliveryFailureInbox(String redisUri, String namespacePrefix) {
            delegate.redisDeliveryFailureInbox(redisUri, namespacePrefix);
            return this;
        }

        public TransportOptions redisDistributedChannels(String redisUri) {
            delegate.redisDistributedChannels(redisUri);
            return this;
        }

        public TransportOptions redisDistributedChannels(String redisUri, String namespacePrefix) {
            delegate.redisDistributedChannels(redisUri, namespacePrefix);
            return this;
        }

        public TransportOptions transportRuntimeRole(TransportRuntimeRole runtimeRole) {
            delegate.transportRuntimeRole(runtimeRole);
            return this;
        }

        public TransportOptions maxPollingPendingDeliveryItems(int maxPollingPendingDeliveryItems) {
            delegate.maxPollingPendingDeliveryItems(maxPollingPendingDeliveryItems);
            return this;
        }

        public TransportOptions maxPollingPendingDeliveryItemsPerWorker(int maxPollingPendingDeliveryItemsPerWorker) {
            delegate.maxPollingPendingDeliveryItemsPerWorker(maxPollingPendingDeliveryItemsPerWorker);
            return this;
        }

        public TransportOptions endpointLeaseMillis(long endpointLeaseMillis) {
            delegate.endpointLeaseMillis(endpointLeaseMillis);
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

        public WebSocketAdapterOptions adapterId(String adapterId) {
            delegate.adapterId(adapterId);
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
                TransportServerFactory<WebSocketServerFactoryContext> transportServerFactory) {
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

        public SocketAdapterOptions adapterId(String adapterId) {
            delegate.adapterId(adapterId);
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

        public EngineOptions runtimeReadyDispatchIdleBackoffMaxMillis(long maxBackoffMillis) {
            delegate.runtimeReadyDispatchIdleBackoffMaxMillis(maxBackoffMillis);
            return this;
        }

        public EngineOptions runtimeReadyDispatchIdleBackoffPolicy(
                PollingIdleBackoffPolicy policy) {
            delegate.runtimeReadyDispatchIdleBackoffPolicy(policy);
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

        public EngineOptions taskShellStore(TaskShellStore taskShellStore) {
            delegate.taskShellStore(taskShellStore);
            return this;
        }

        public EngineOptions taskWorkRuntime(TaskWorkRuntime taskWorkRuntime) {
            delegate.taskWorkRuntime(taskWorkRuntime);
            return this;
        }

        public EngineOptions taskResultRuntime(TaskResultRuntime taskResultRuntime) {
            delegate.taskResultRuntime(taskResultRuntime);
            return this;
        }

        public EngineOptions workerDeclarationStore(WorkerDeclarationStore workerDeclarationStore) {
            delegate.workerDeclarationStore(workerDeclarationStore);
            return this;
        }

        public EngineOptions workerRegistry(WorkerRegistry workerRegistry) {
            delegate.workerRegistry(workerRegistry);
            return this;
        }

        public EngineOptions workerScoreBandSlotRuntime(WorkerScoreBandSlotRuntime workerScoreBandSlotRuntime) {
            delegate.workerScoreBandSlotRuntime(workerScoreBandSlotRuntime);
            return this;
        }

        public EngineOptions ruleStorage(RuleStorage ruleStorage) {
            delegate.ruleStorage(ruleStorage);
            return this;
        }

        public EngineOptions executionEventSink(ExecutionEventSink executionEventSink) {
            delegate.executionEventSink(executionEventSink);
            return this;
        }

    }
}
