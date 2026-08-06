package com.xa.mass.worker.android;

import android.content.Context;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.TextMessageWorkerRuntime;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AndroidWorker implements AutoCloseable {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";
    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(10);
    private static final Duration DEFAULT_RECONNECT_INTERVAL =
            Duration.ofMillis(250);
    private static final Set<String> ACTIVE_COORDINATES =
            ConcurrentHashMap.newKeySet();

    public enum State {
        STOPPED,
        REGISTERING,
        BINDING,
        CONNECTING,
        TRANSPORT_CONNECTED,
        ERROR,
        CLOSED
    }

    public interface Listener {

        void onSnapshot(Snapshot snapshot);
    }

    private final Object lock = new Object();
    private final String processCoordinate;
    private final TextMessageWorkerRuntime runtime;
    private final Map<Listener, TextMessageWorkerRuntime.Listener> listeners =
            new ConcurrentHashMap<>();
    private boolean processLeaseHeld;
    private boolean closed;

    private AndroidWorker(
            String processCoordinate,
            TextMessageWorkerRuntime runtime
    ) {
        this.processCoordinate = processCoordinate;
        this.runtime = runtime;
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

    public void start() {
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
            }
        }
        try {
            runtime.start();
        } catch (RuntimeException error) {
            releaseProcessLease();
            throw error;
        }
    }

    public void stop() {
        runtime.stop();
        releaseProcessLease();
    }

    public void refreshProperties() {
        runtime.refreshProperties();
    }

    public Snapshot snapshot() {
        return snapshot(runtime.snapshot());
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must be present");
        }
        TextMessageWorkerRuntime.Listener bridge = current ->
                listener.onSnapshot(snapshot(current));
        TextMessageWorkerRuntime.Listener previous = listeners.putIfAbsent(
                listener,
                bridge
        );
        if (previous == null) {
            runtime.addListener(bridge);
        }
    }

    public void removeListener(Listener listener) {
        if (listener == null) {
            return;
        }
        TextMessageWorkerRuntime.Listener bridge = listeners.remove(listener);
        if (bridge != null) {
            runtime.removeListener(bridge);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        runtime.close();
        releaseProcessLease();
        listeners.clear();
    }

    private void releaseProcessLease() {
        boolean release;
        synchronized (lock) {
            release = processLeaseHeld;
            processLeaseHeld = false;
        }
        if (release) {
            ACTIVE_COORDINATES.remove(processCoordinate);
        }
    }

    private static Snapshot snapshot(
            TextMessageWorkerRuntime.Snapshot current
    ) {
        return new Snapshot(
                State.valueOf(current.state().name()),
                current.workerId(),
                current.endpointUri(),
                current.diagnosticMessage()
        );
    }

    public static final class Builder {

        private final Context applicationContext;
        private final URI runtimeApiBaseUrl;
        private final String workerGroupId;
        private AndroidWorkerProperties workerProperties;
        private Collection<? extends WorkerEventDefinition<?>> definitions;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration reconnectInterval = DEFAULT_RECONNECT_INTERVAL;

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

        public Builder requestTimeout(Duration value) {
            requestTimeout = requirePositive(value, "requestTimeout");
            return this;
        }

        public Builder reconnectInterval(Duration value) {
            reconnectInterval = requirePositive(
                    value,
                    "reconnectInterval"
            );
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
            Duration resolvedReconnectInterval = reconnectInterval;
            TextMessageWorkerRuntime runtime = new TextMessageWorkerRuntime(
                    workerGroupId,
                    WorkerTransportType.WEBSOCKET,
                    identityStore,
                    completeProperties,
                    definitions,
                    () -> new AndroidOkHttpWorkerControlClient(
                            runtimeApiBaseUrl
                    ),
                    endpointUri -> new AndroidOkHttpTextWebSocketClient(
                            endpointUri,
                            resolvedRequestTimeout,
                            resolvedReconnectInterval
                    ),
                    resolvedRequestTimeout,
                    resolvedReconnectInterval
            );
            return new AndroidWorker(
                    applicationContext.getPackageName()
                            + "\n" + workerGroupId,
                    runtime
            );
        }
    }

    public static final class Snapshot {

        private final State state;
        private final String workerId;
        private final URI endpointUri;
        private final String diagnosticMessage;

        private Snapshot(
                State state,
                String workerId,
                URI endpointUri,
                String diagnosticMessage
        ) {
            this.state = state;
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.diagnosticMessage = diagnosticMessage;
        }

        public State state() {
            return state;
        }

        public String workerId() {
            return workerId;
        }

        public URI endpointUri() {
            return endpointUri;
        }

        public String diagnosticMessage() {
            return diagnosticMessage;
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
