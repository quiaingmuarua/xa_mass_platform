package com.xa.mass.integration.androidworker;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.xa.mass.transport.android.websocket
        .AndroidOkHttpTextWebSocketClient;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
import com.xa.mass.transport.client.okhttp
        .OkHttpWorkerControlClient.TransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class AndroidWorkerDemoController implements AutoCloseable {

    static final String WORKER_GROUP_ID = "android-demo-workers";
    static final String EVENT_CODE = "android.demo.state.read";

    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(10);
    private static final Duration RECONNECT_INTERVAL =
            Duration.ofSeconds(1);
    private static final long CONNECTION_PROBE_INTERVAL_MILLIS = 250;

    enum State {
        STOPPED,
        REGISTERING,
        BINDING,
        CONNECTING,
        TRANSPORT_CONNECTED,
        ERROR
    }

    interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    private final Object lock = new Object();
    private final Context applicationContext;
    private final URI runtimeApiBaseUrl;
    private final Duration requestTimeout;
    private final Duration reconnectInterval;
    private final long connectionProbeIntervalMillis;
    private final AndroidWorkerIdentityStore identityStore;
    private final Listener listener;
    private final Handler mainHandler;
    private final ExecutorService lifecycleExecutor;

    private boolean started;
    private boolean closed;
    private long generation;
    private State state = State.STOPPED;
    private String workerId;
    private URI endpointUri;
    private int processedCommands;
    private String lastEvent;
    private String errorMessage;
    private Session activeSession;

    AndroidWorkerDemoController(
            Context context,
            URI runtimeApiBaseUrl,
            Listener listener
    ) {
        this(
                context,
                runtimeApiBaseUrl,
                listener,
                REQUEST_TIMEOUT,
                RECONNECT_INTERVAL,
                CONNECTION_PROBE_INTERVAL_MILLIS
        );
    }

    AndroidWorkerDemoController(
            Context context,
            URI runtimeApiBaseUrl,
            Listener listener,
            Duration requestTimeout,
            Duration reconnectInterval,
            long connectionProbeIntervalMillis
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must be present");
        }
        if (runtimeApiBaseUrl == null
                || !runtimeApiBaseUrl.isAbsolute()
                || runtimeApiBaseUrl.getHost() == null
                || (!"http".equalsIgnoreCase(
                        runtimeApiBaseUrl.getScheme()
                )
                && !"https".equalsIgnoreCase(
                        runtimeApiBaseUrl.getScheme()
                ))) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must be present");
        }
        this.applicationContext = context.getApplicationContext();
        this.runtimeApiBaseUrl = runtimeApiBaseUrl;
        this.listener = listener;
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        if (connectionProbeIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "connectionProbeIntervalMillis must be positive"
            );
        }
        this.connectionProbeIntervalMillis =
                connectionProbeIntervalMillis;
        identityStore = new AndroidWorkerIdentityStore(
                applicationContext,
                WORKER_GROUP_ID
        );
        mainHandler = new Handler(Looper.getMainLooper());
        lifecycleExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "android-worker-demo-lifecycle"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        long currentGeneration;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "AndroidWorkerDemoController is closed"
                );
            }
            if (started) {
                return;
            }
            started = true;
            currentGeneration = ++generation;
            state = State.REGISTERING;
            endpointUri = null;
            errorMessage = null;
            publishLocked();
        }
        try {
            lifecycleExecutor.execute(
                    () -> startSession(currentGeneration)
            );
        } catch (RejectedExecutionException error) {
            fail(currentGeneration, "Worker lifecycle is unavailable", error);
        }
    }

    void stop() {
        Session session;
        synchronized (lock) {
            if (!started && state == State.STOPPED) {
                return;
            }
            started = false;
            generation++;
            session = activeSession;
            activeSession = null;
            state = State.STOPPED;
            errorMessage = null;
            publishLocked();
        }
        if (session != null) {
            session.close();
        }
    }

    int incrementCounter() {
        int value = identityStore.incrementCounter();
        publish();
        return value;
    }

    int resetCounter() {
        int value = identityStore.resetCounter();
        publish();
        return value;
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
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
        stop();
        lifecycleExecutor.shutdownNow();
    }

    private void startSession(long currentGeneration) {
        OkHttpWorkerControlClient control =
                new OkHttpWorkerControlClient(runtimeApiBaseUrl);
        Session session = new Session(control);
        if (!installSession(currentGeneration, session)) {
            session.close();
            return;
        }

        try {
            AndroidWorkerIdentityStore.Identity identity =
                    identityStore.loadOrCreateIdentity();
            String resolvedWorkerId = identity.workerId();
            if (resolvedWorkerId == null) {
                resolvedWorkerId = control.register(
                        identity.workerGroupId(),
                        identity.clientWorkerKey(),
                        requestTimeout
                );
                identityStore.persistWorkerId(resolvedWorkerId);
            }
            if (!advanceToBinding(currentGeneration, resolvedWorkerId)) {
                session.close();
                return;
            }

            URI resolvedEndpoint = control.bind(
                    identity.workerGroupId(),
                    identity.clientWorkerKey(),
                    resolvedWorkerId,
                    TransportType.WEBSOCKET,
                    workerProperties(),
                    requestTimeout
            );
            session.closeControl();

            TextWebSocketClient networkClient =
                    new AndroidOkHttpTextWebSocketClient(
                            resolvedEndpoint,
                            requestTimeout,
                            reconnectInterval
                    );
            WebSocketWorkerTransport worker =
                    new WebSocketWorkerTransport(
                            networkClient,
                            resolvedWorkerId,
                            definitions(currentGeneration)
                    );
            session.installWorker(worker);
            if (!advanceToConnecting(
                    currentGeneration,
                    resolvedEndpoint
            )) {
                session.close();
                return;
            }
            worker.start();
            scheduleConnectionProbe(currentGeneration, worker);
        } catch (Exception error) {
            fail(
                    currentGeneration,
                    safeMessage(error),
                    error
            );
            session.close();
        }
    }

    private boolean installSession(
            long currentGeneration,
            Session session
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return false;
            }
            activeSession = session;
            return true;
        }
    }

    private boolean advanceToBinding(
            long currentGeneration,
            String resolvedWorkerId
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return false;
            }
            workerId = resolvedWorkerId;
            state = State.BINDING;
            publishLocked();
            return true;
        }
    }

    private boolean advanceToConnecting(
            long currentGeneration,
            URI resolvedEndpoint
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return false;
            }
            endpointUri = resolvedEndpoint;
            state = State.CONNECTING;
            publishLocked();
            return true;
        }
    }

    private void scheduleConnectionProbe(
            long currentGeneration,
            WebSocketWorkerTransport worker
    ) {
        mainHandler.postDelayed(
                () -> probeConnection(currentGeneration, worker),
                connectionProbeIntervalMillis
        );
    }

    private void probeConnection(
            long currentGeneration,
            WebSocketWorkerTransport worker
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return;
            }
            state = worker.isConnected()
                    ? State.TRANSPORT_CONNECTED
                    : State.CONNECTING;
            publishLocked();
        }
        scheduleConnectionProbe(currentGeneration, worker);
    }

    private Collection<WorkerEventDefinition<?>> definitions(
            long currentGeneration
    ) {
        WorkerEventDefinition<Map<String, Object>> definition =
                WorkerEventDefinition.of(
                        "TASK",
                        EVENT_CODE,
                        WorkerEventParameterResolvers.jsonMap(),
                        parameters -> executeStateRead(currentGeneration)
                );
        return Collections.singletonList(definition);
    }

    private String executeStateRead(long currentGeneration) {
        int counter = identityStore.counter();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageName", applicationContext.getPackageName());
        result.put("sdkInt", Build.VERSION.SDK_INT);
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("model", Build.MODEL);
        result.put("counter", counter);

        synchronized (lock) {
            if (isCurrentLocked(currentGeneration)) {
                processedCommands++;
                lastEvent = EVENT_CODE + " counter=" + counter;
                publishLocked();
            }
        }
        return Jsons.toJson(result);
    }

    private Map<String, Object> workerProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("runtime", "android");
        properties.put("packageName", applicationContext.getPackageName());
        properties.put("versionName", versionName());
        properties.put("sdkInt", Build.VERSION.SDK_INT);
        properties.put("manufacturer", Build.MANUFACTURER);
        properties.put("model", Build.MODEL);
        return Collections.unmodifiableMap(properties);
    }

    private String versionName() {
        try {
            PackageInfo info = applicationContext.getPackageManager()
                    .getPackageInfo(
                            applicationContext.getPackageName(),
                            0
                    );
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }

    private void fail(
            long currentGeneration,
            String message,
            Throwable ignored
    ) {
        Session session;
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return;
            }
            started = false;
            session = activeSession;
            activeSession = null;
            state = State.ERROR;
            errorMessage = message;
            publishLocked();
        }
        if (session != null) {
            session.close();
        }
    }

    private boolean isCurrentLocked(long candidateGeneration) {
        return started
                && !closed
                && generation == candidateGeneration;
    }

    private void publish() {
        synchronized (lock) {
            publishLocked();
        }
    }

    private void publishLocked() {
        Snapshot snapshot = snapshotLocked();
        mainHandler.post(() -> listener.onSnapshot(snapshot));
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                state,
                workerId,
                endpointUri,
                identityStore.counter(),
                processedCommands,
                lastEvent,
                errorMessage
        );
    }

    private static Duration requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    static final class Snapshot {

        private final State state;
        private final String workerId;
        private final URI endpointUri;
        private final int counter;
        private final int processedCommands;
        private final String lastEvent;
        private final String errorMessage;

        private Snapshot(
                State state,
                String workerId,
                URI endpointUri,
                int counter,
                int processedCommands,
                String lastEvent,
                String errorMessage
        ) {
            this.state = state;
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.counter = counter;
            this.processedCommands = processedCommands;
            this.lastEvent = lastEvent;
            this.errorMessage = errorMessage;
        }

        State state() {
            return state;
        }

        String workerId() {
            return workerId;
        }

        URI endpointUri() {
            return endpointUri;
        }

        int counter() {
            return counter;
        }

        int processedCommands() {
            return processedCommands;
        }

        String lastEvent() {
            return lastEvent;
        }

        String errorMessage() {
            return errorMessage;
        }
    }

    private static final class Session implements AutoCloseable {

        private OkHttpWorkerControlClient control;
        private WebSocketWorkerTransport worker;
        private boolean closed;

        private Session(OkHttpWorkerControlClient control) {
            this.control = control;
        }

        synchronized void closeControl() {
            if (control != null) {
                control.close();
                control = null;
            }
        }

        synchronized void installWorker(
                WebSocketWorkerTransport worker
        ) {
            if (closed) {
                worker.close();
                return;
            }
            this.worker = worker;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (worker != null) {
                worker.close();
                worker = null;
            }
            closeControl();
        }
    }
}
