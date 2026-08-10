package com.xa.mass.worker.android;

import android.content.Context;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.RegisteredWorkerPreparation;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.worker.runtime.WorkerRunController;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class AndroidWorker implements WorkerLifecycle {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";
    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(10);
    private static final Set<String> ACTIVE_COORDINATES =
            ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private final String processCoordinate;
    private final WorkerRunController worker;
    private final WorkerLifecycle.Listener lifecycleListener;
    private boolean processLeaseHeld;
    private boolean closed;

    private AndroidWorker(
            String processCoordinate,
            WorkerRunController worker
    ) {
        this.processCoordinate = processCoordinate;
        this.worker = worker;
        lifecycleListener = this::releaseProcessLeaseWhenStopped;
        worker.addListener(lifecycleListener);
    }

    public static Builder builder(
            Context applicationContext,
            URI runtimeApiBaseUrl,
            String workerGroupId
    ) {
        return new Builder(
                applicationContext,
                runtimeApiBaseUrl,
                workerGroupId
        );
    }

    @Override
    public void start() {
        boolean leaseAcquired = false;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("AndroidWorker is closed");
            }
            if (!processLeaseHeld) {
                if (!ACTIVE_COORDINATES.add(processCoordinate)) {
                    throw new IllegalStateException(
                            "An Android Worker for this application and "
                                    + "WorkerGroup is already active"
                    );
                }
                processLeaseHeld = true;
                leaseAcquired = true;
            }
        }
        try {
            worker.start();
        } catch (RuntimeException | Error error) {
            if (leaseAcquired) {
                releaseProcessLease();
            }
            throw error;
        } finally {
            if (worker.snapshot().state() == State.STOPPED) {
                releaseProcessLease();
            }
        }
    }

    @Override
    public void stop() {
        worker.stop();
    }

    @Override
    public Snapshot snapshot() {
        return worker.snapshot();
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
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        worker.close();
        worker.removeListener(lifecycleListener);
        releaseProcessLease();
    }

    private void releaseProcessLeaseWhenStopped(Snapshot snapshot) {
        synchronized (lock) {
            if (snapshot.state() != State.STOPPED
                    || worker.snapshot().state() != State.STOPPED) {
                return;
            }
            releaseProcessLeaseLocked();
        }
    }

    private void releaseProcessLease() {
        synchronized (lock) {
            releaseProcessLeaseLocked();
        }
    }

    private void releaseProcessLeaseLocked() {
        if (processLeaseHeld) {
            processLeaseHeld = false;
            ACTIVE_COORDINATES.remove(processCoordinate);
        }
    }

    public static final class Builder {

        private final Context applicationContext;
        private final URI runtimeApiBaseUrl;
        private final String workerGroupId;
        private AndroidWorkerProperties workerProperties;
        private Collection<? extends WorkerEventDefinition<?>> definitions;
        private Executor handlerExecutor;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private TextMessageReconnectPolicy reconnectPolicy =
                TextMessageReconnectPolicy.defaults();

        private Builder(
                Context applicationContext,
                URI runtimeApiBaseUrl,
                String workerGroupId
        ) {
            if (applicationContext == null) {
                throw new IllegalArgumentException(
                        "applicationContext must be present"
                );
            }
            Context resolved = applicationContext.getApplicationContext();
            this.applicationContext = resolved == null
                    ? applicationContext
                    : resolved;
            this.runtimeApiBaseUrl = requireRuntimeApiBaseUrl(
                    runtimeApiBaseUrl
            );
            this.workerGroupId = requireNonBlank(
                    workerGroupId,
                    "workerGroupId"
            );
        }

        public Builder workerProperties(AndroidWorkerProperties value) {
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

        public Builder handlerExecutor(Executor value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "handlerExecutor must be present"
                );
            }
            handlerExecutor = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = requirePositive(value, "requestTimeout");
            return this;
        }

        public Builder reconnectPolicy(TextMessageReconnectPolicy value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "reconnectPolicy must be present"
                );
            }
            reconnectPolicy = value;
            return this;
        }

        public AndroidWorker build() {
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
            if (handlerExecutor == null) {
                throw new IllegalStateException(
                        "handlerExecutor must be configured"
                );
            }
            AndroidClientWorkerKeyStore clientKeyStore =
                    new AndroidClientWorkerKeyStore(
                            applicationContext,
                            workerGroupId
                    );
            AndroidWorkerIdentityStore identityStore =
                    new AndroidWorkerIdentityStore(
                            applicationContext,
                            workerGroupId
                    );
            WorkerPropertiesProvider completeProperties = () -> {
                Map<String, Object> supplied = workerProperties.getProperties(
                        applicationContext
                );
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
                complete.put(
                        CLIENT_WORKER_KEY,
                        clientKeyStore.loadOrCreate()
                );
                complete.putAll(supplied);
                return complete;
            };
            Duration resolvedRequestTimeout = requestTimeout;
            TextMessageReconnectPolicy resolvedReconnectPolicy =
                    reconnectPolicy;
            WorkerRunController worker = new WorkerRunController(
                    new RegisteredWorkerPreparation(
                            workerGroupId,
                            WorkerTransportType.WEBSOCKET,
                            identityStore,
                            completeProperties,
                            new AndroidOkHttpWorkerControlClient(
                                    runtimeApiBaseUrl
                            ),
                            resolvedRequestTimeout
                    ),
                    new WorkerCommandDispatcher(definitions),
                    endpointUri -> new AndroidOkHttpTextWebSocketClient(
                            endpointUri,
                            resolvedRequestTimeout,
                            resolvedReconnectPolicy
                    ),
                    handlerExecutor
            );
            return new AndroidWorker(
                    applicationContext.getPackageName()
                            + "\n" + workerGroupId,
                    worker
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

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
