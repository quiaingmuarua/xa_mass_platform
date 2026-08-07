package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.transport.client.jdk.JdkLineSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.RegisteredWorkerPreparation;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerLoop;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.worker.runtime.WorkerRetryPolicy;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JavaWorker implements WorkerLifecycle {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";
    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(10);
    private final WorkerLoop worker;

    private JavaWorker(WorkerLoop worker) {
        this.worker = worker;
    }

    public static Builder builder(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            String clientWorkerKey,
            WorkerTransportType transportType
    ) {
        return new Builder(
                runtimeApiBaseUrl,
                workerGroupId,
                clientWorkerKey,
                transportType
        );
    }

    @Override
    public void start() {
        worker.start();
    }

    @Override
    public void stop() {
        worker.stop();
    }

    @Override
    public boolean send(WorkerCommand command) {
        return worker.send(command);
    }

    @Override
    public Snapshot snapshot() {
        return worker.snapshot();
    }

    @Override
    public boolean isConnected() {
        return worker.isConnected();
    }

    @Override
    public void addListener(Listener listener) {
        worker.addListener(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        worker.removeListener(listener);
    }

    @Override
    public void close() {
        worker.close();
    }

    public static final class Builder {

        private final URI runtimeApiBaseUrl;
        private final String workerGroupId;
        private final String clientWorkerKey;
        private final WorkerTransportType transportType;
        private WorkerIdentityStore identityStore;
        private WorkerPropertiesProvider workerProperties;
        private Collection<? extends WorkerEventDefinition<?>> definitions;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private WorkerRetryPolicy retryPolicy = WorkerRetryPolicy.defaults();

        private Builder(
                URI runtimeApiBaseUrl,
                String workerGroupId,
                String clientWorkerKey,
                WorkerTransportType transportType
        ) {
            this.runtimeApiBaseUrl = requireRuntimeApiBaseUrl(
                    runtimeApiBaseUrl
            );
            this.workerGroupId = requireNonBlank(
                    workerGroupId,
                    "workerGroupId"
            );
            this.clientWorkerKey = requireNonBlank(
                    clientWorkerKey,
                    "clientWorkerKey"
            );
            this.transportType = requireTextMessageTransportType(
                    transportType
            );
        }

        public Builder identityStore(WorkerIdentityStore value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "identityStore must be present"
                );
            }
            identityStore = value;
            return this;
        }

        public Builder workerProperties(WorkerPropertiesProvider value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "workerProperties must be present"
                );
            }
            workerProperties = value;
            return this;
        }

        public Builder eventDefinitions(
                Collection<? extends WorkerEventDefinition<?>> value
        ) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "eventDefinitions must be present"
                );
            }
            definitions = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = requirePositive(value, "requestTimeout");
            return this;
        }

        public Builder retryPolicy(WorkerRetryPolicy value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "retryPolicy must be present"
                );
            }
            retryPolicy = value;
            return this;
        }

        public JavaWorker build() {
            if (identityStore == null) {
                throw new IllegalStateException(
                        "identityStore must be configured"
                );
            }
            if (workerProperties == null) {
                throw new IllegalStateException(
                        "workerProperties must be configured"
                );
            }
            if (definitions == null || definitions.isEmpty()) {
                throw new IllegalStateException(
                        "eventDefinitions must not be empty"
                );
            }
            WorkerPropertiesProvider completeProperties = () -> {
                Map<String, Object> supplied =
                        workerProperties.loadProperties();
                if (supplied == null) {
                    throw new IllegalArgumentException(
                            "workerProperties must be present"
                    );
                }
                if (supplied.containsKey(CLIENT_WORKER_KEY)) {
                    throw new IllegalArgumentException(
                            "workerProperties must not override "
                                    + CLIENT_WORKER_KEY
                    );
                }
                Map<String, Object> complete = new LinkedHashMap<>();
                complete.put(CLIENT_WORKER_KEY, clientWorkerKey);
                complete.putAll(supplied);
                return complete;
            };
            Duration resolvedRequestTimeout = requestTimeout;
            WorkerRetryPolicy resolvedRetryPolicy = retryPolicy;
            WorkerLoop worker = new WorkerLoop(
                    new RegisteredWorkerPreparation(
                            workerGroupId,
                            transportType,
                            identityStore,
                            completeProperties,
                            new OkHttpWorkerControlClient(
                                    runtimeApiBaseUrl
                            ),
                            resolvedRequestTimeout
                    ),
                    definitions,
                    endpointUri -> createNetworkClient(
                            transportType,
                            endpointUri,
                            resolvedRequestTimeout,
                            resolvedRetryPolicy
                                    .connectionPolicy()
                    ),
                    resolvedRetryPolicy
            );
            return new JavaWorker(worker);
        }

        private static TextMessageClient createNetworkClient(
                WorkerTransportType transportType,
                URI endpointUri,
                Duration requestTimeout,
                TextMessageReconnectPolicy reconnectPolicy
        ) {
            if (transportType == WorkerTransportType.WEBSOCKET) {
                return new OkHttpTextWebSocketClient(
                        endpointUri,
                        requestTimeout,
                        reconnectPolicy
                );
            }
            return new JdkLineSocketClient(
                    endpointUri,
                    requestTimeout,
                    reconnectPolicy
            );
        }
    }

    private static URI requireRuntimeApiBaseUrl(URI value) {
        if (value == null
                || !value.isAbsolute()
                || value.getHost() == null
                || (!("http".equalsIgnoreCase(value.getScheme()))
                && !("https".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static WorkerTransportType requireTextMessageTransportType(
            WorkerTransportType value
    ) {
        if (value != WorkerTransportType.WEBSOCKET
                && value != WorkerTransportType.SOCKET) {
            throw new IllegalArgumentException(
                    "transportType must be WEBSOCKET or SOCKET"
            );
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
