package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.starter.builder.MassApplicationBuilder;

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

    public static MassSdkApplication development(int port,
                                                 MessageQueue<Envelope> inputQueue,
                                                 MessageQueue<Envelope> outputQueue) {
        return new MassSdkApplication(
                MassApplicationBuilder.createDevelopment(port, inputQueue, outputQueue)
        );
    }

    public static MassSdkApplication production(int port,
                                                MessageQueue<Envelope> inputQueue,
                                                MessageQueue<Envelope> outputQueue) {
        return new MassSdkApplication(
                MassApplicationBuilder.createProduction(port, inputQueue, outputQueue)
        );
    }

    public static MassSdkApplication apiMode(int port,
                                             String inputApiUrl,
                                             String outputApiUrl,
                                             String apiKey) {
        return new MassSdkApplication(
                MassApplicationBuilder.createApiMode(port, inputApiUrl, outputApiUrl, apiKey)
        );
    }

    public static MassSdkApplication testMode(int port) {
        return new MassSdkApplication(MassApplicationBuilder.createTest(port));
    }

    public static final class Builder {
        private final MassApplicationBuilder delegate;

        private Builder(MassApplicationBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public Builder server(int port) {
            delegate.server(port);
            return this;
        }

        public Builder server(int port, String webSocketPath) {
            delegate.server(port, webSocketPath);
            return this;
        }

        public Builder gateway(Consumer<GatewayOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.gateway(inner -> configurator.accept(new GatewayOptions(inner)));
            return this;
        }

        public Builder engine(Consumer<EngineOptions> configurator) {
            Objects.requireNonNull(configurator, "configurator");
            delegate.engine(inner -> configurator.accept(new EngineOptions(inner)));
            return this;
        }

        public MassSdkApplication build() {
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

    public static final class GatewayOptions {
        private final MassApplicationBuilder.GatewayBuilder delegate;

        private GatewayOptions(MassApplicationBuilder.GatewayBuilder delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public GatewayOptions enabled(boolean enabled) {
            delegate.enabled(enabled);
            return this;
        }

        public GatewayOptions maxConnections(int maxConnections) {
            delegate.maxConnections(maxConnections);
            return this;
        }

        public GatewayOptions inputQueue(MessageQueue<Envelope> inputQueue) {
            delegate.inputQueue(inputQueue);
            return this;
        }

        public GatewayOptions outputQueue(MessageQueue<Envelope> outputQueue) {
            delegate.outputQueue(outputQueue);
            return this;
        }

        public GatewayOptions apiMode(String inputApiUrl, String outputApiUrl, String apiKey) {
            delegate.apiMode(inputApiUrl, outputApiUrl, apiKey);
            return this;
        }

        public GatewayOptions queueMode() {
            delegate.queueMode();
            return this;
        }

        /**
         * @deprecated Prefer the SDK gateway option methods. This remains only
         * for advanced embedding paths that need lower-level runtime configuration.
         */
        @Deprecated(forRemoval = false)
        public MassApplicationBuilder.GatewayBuilder unwrap() {
            return delegate;
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
         * @deprecated Mock/bootstrap data should be wired through
         * {@link #bootstrapDataProvider(MassBootstrapDataProvider)}.
         */
        @Deprecated(forRemoval = false)
        public EngineOptions mockData(String workerConfigPath,
                                      String workerContextConfigPath,
                                      String taskConfigPath,
                                      String ruleConfigPath) {
            delegate.mockData(workerConfigPath, workerContextConfigPath, taskConfigPath, ruleConfigPath);
            return this;
        }

        /**
         * @deprecated Mock/bootstrap data should be wired through
         * {@link #bootstrapDataProvider(MassBootstrapDataProvider)}.
         */
        @Deprecated(forRemoval = false)
        public EngineOptions mockData(String mockConfigPath) {
            delegate.mockData(mockConfigPath);
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
