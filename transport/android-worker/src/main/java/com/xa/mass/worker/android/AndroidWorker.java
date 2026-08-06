package com.xa.mass.worker.android;

import android.content.Context;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AndroidWorker implements AutoCloseable {

    private static final int UNSTABLE_CONNECTION_LIMIT = 3;
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

    @FunctionalInterface
    interface ControlClientFactory {

        WorkerControlClient create();
    }

    @FunctionalInterface
    interface NetworkClientFactory {

        TextWebSocketClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final Context applicationContext;
    private final String workerGroupId;
    private final String configuredClientWorkerKey;
    private final String processCoordinate;
    private final AndroidWorkerIdentityStore identityStore;
    private final AndroidWorkerEndpointCacheStore endpointCacheStore;
    private final AndroidWorkerProperties workerProperties;
    private final List<WorkerEventDefinition<?>> definitions;
    private final ControlClientFactory controlClientFactory;
    private final NetworkClientFactory networkClientFactory;
    private final Duration requestTimeout;
    private final ScheduledExecutorService lifecycleExecutor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread lifecycleThread;

    private boolean started;
    private boolean closed;
    private boolean processLeaseHeld;
    private long generation;
    private long nextSessionId;
    private long nextConnectionId;
    private State state = State.STOPPED;
    private String workerId;
    private URI endpointUri;
    private String diagnosticMessage;
    private Session activeSession;
    private AndroidWorkerIdentityStore.Identity activeIdentity;
    private Map<String, Object> activeWorkerProperties;
    private String activePropertiesSha256;
    private int unstableConnections;
    private boolean endpointRefreshAttempted;

    AndroidWorker(
            Context applicationContext,
            String workerGroupId,
            String configuredClientWorkerKey,
            AndroidWorkerIdentityStore identityStore,
            AndroidWorkerEndpointCacheStore endpointCacheStore,
            AndroidWorkerProperties workerProperties,
            Collection<? extends WorkerEventDefinition<?>> definitions,
            ControlClientFactory controlClientFactory,
            NetworkClientFactory networkClientFactory,
            Duration requestTimeout
    ) {
        if (applicationContext == null) {
            throw new IllegalArgumentException(
                    "applicationContext must be present"
            );
        }
        Context resolvedApplicationContext =
                applicationContext.getApplicationContext();
        this.applicationContext = resolvedApplicationContext == null
                ? applicationContext
                : resolvedApplicationContext;
        this.workerGroupId = requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        this.configuredClientWorkerKey = configuredClientWorkerKey == null
                ? null
                : requireNonBlank(
                        configuredClientWorkerKey,
                        "clientWorkerKey"
                );
        processCoordinate = this.applicationContext.getPackageName()
                + "\n" + this.workerGroupId;
        if (identityStore == null
                || endpointCacheStore == null
                || workerProperties == null
                || controlClientFactory == null
                || networkClientFactory == null) {
            throw new IllegalArgumentException(
                    "Android Worker dependencies must be present"
            );
        }
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "definitions must not be empty"
            );
        }
        this.identityStore = identityStore;
        this.endpointCacheStore = endpointCacheStore;
        this.workerProperties = workerProperties;
        this.definitions = Collections.unmodifiableList(
                new ArrayList<>(definitions)
        );
        if (this.definitions.contains(null)) {
            throw new IllegalArgumentException(
                    "definitions must not contain null"
            );
        }
        this.controlClientFactory = controlClientFactory;
        this.networkClientFactory = networkClientFactory;
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        lifecycleExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "xa-android-worker-lifecycle"
                    );
                    thread.setDaemon(true);
                    lifecycleThread = thread;
                    return thread;
                }
        );
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
        long currentGeneration;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "AndroidWorker is closed"
                );
            }
            if (started) {
                return;
            }
            if (!ACTIVE_COORDINATES.add(processCoordinate)) {
                throw new IllegalStateException(
                        "An Android Worker for this application and "
                                + "WorkerGroup is already active"
                );
            }
            processLeaseHeld = true;
            started = true;
            currentGeneration = ++generation;
            state = State.REGISTERING;
            diagnosticMessage = null;
            unstableConnections = 0;
            endpointRefreshAttempted = false;
        }
        publish();
        execute(() -> startSession(currentGeneration), currentGeneration);
    }

    public void stop() {
        Session session;
        synchronized (lock) {
            if (closed || (!started && state == State.STOPPED)) {
                return;
            }
            started = false;
            generation++;
            session = activeSession;
            activeSession = null;
            activeIdentity = null;
            activeWorkerProperties = null;
            activePropertiesSha256 = null;
            state = State.STOPPED;
            diagnosticMessage = null;
        }
        if (session != null) {
            session.close();
        }
        releaseProcessLease();
        publish();
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must be present");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("AndroidWorker is closed");
            }
            listeners.add(listener);
        }
        publishTo(listener);
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    state,
                    workerId,
                    endpointUri,
                    diagnosticMessage
            );
        }
    }

    public void refreshProperties() {
        long currentGeneration;
        long sessionId;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("AndroidWorker is closed");
            }
            if (!started || activeSession == null) {
                return;
            }
            currentGeneration = generation;
            sessionId = activeSession.id();
        }
        execute(
                () -> refreshProperties(currentGeneration, sessionId),
                currentGeneration
        );
    }

    @Override
    public void close() {
        Session session;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            started = false;
            generation++;
            session = activeSession;
            activeSession = null;
            activeIdentity = null;
            activeWorkerProperties = null;
            activePropertiesSha256 = null;
            state = State.CLOSED;
        }
        if (session != null) {
            session.close();
        }
        releaseProcessLease();
        publishFinalAndShutdown();
    }

    private void startSession(long currentGeneration) {
        Session session;
        try {
            session = new Session(
                    nextSessionId(),
                    requireControlClient()
            );
        } catch (RuntimeException error) {
            fail(currentGeneration, safeMessage(error));
            return;
        }
        if (!installSession(currentGeneration, session)) {
            session.close();
            return;
        }

        try {
            Map<String, Object> properties =
                    AndroidWorkerPropertiesFingerprint.snapshot(
                            workerProperties.getProperties(
                                    applicationContext
                            )
                    );
            String propertiesSha256 =
                    AndroidWorkerPropertiesFingerprint.sha256(properties);
            AndroidWorkerIdentityStore.Identity identity =
                    identityStore.loadOrCreateIdentity(
                            configuredClientWorkerKey
                    );
            String resolvedWorkerId = identity.workerId();
            if (resolvedWorkerId == null) {
                resolvedWorkerId = session.control().register(
                        identity.workerGroupId(),
                        identity.clientWorkerKey(),
                        requestTimeout
                );
                identityStore.persistWorkerId(resolvedWorkerId);
                identity = identityStore.loadOrCreateIdentity(
                        configuredClientWorkerKey
                );
            }
            if (!recordIdentityAndProperties(
                    currentGeneration,
                    session,
                    identity,
                    properties,
                    propertiesSha256
            )) {
                session.close();
                return;
            }

            Optional<AndroidWorkerEndpointCacheStore.Entry> cached =
                    endpointCacheStore.load();
            if (cached.isPresent() && cacheMatches(
                    cached.get(),
                    identity,
                    propertiesSha256
            )) {
                installTransport(
                        currentGeneration,
                        session,
                        cached.get().endpointUri(),
                        true
                );
                return;
            }
            if (cached.isPresent()) {
                clearCacheBestEffort(currentGeneration);
            }
            bindAndConnect(
                    currentGeneration,
                    session,
                    identity,
                    properties,
                    propertiesSha256
            );
        } catch (Exception error) {
            fail(currentGeneration, safeMessage(error));
            session.close();
        }
    }

    private void bindAndConnect(
            long currentGeneration,
            Session session,
            AndroidWorkerIdentityStore.Identity identity,
            Map<String, Object> properties,
            String propertiesSha256
    ) throws Exception {
        transition(currentGeneration, session, State.BINDING, null);
        URI resolvedEndpoint = bind(
                session,
                identity,
                properties
        );
        storeCacheBestEffort(
                currentGeneration,
                identity,
                resolvedEndpoint,
                propertiesSha256
        );
        installTransport(
                currentGeneration,
                session,
                resolvedEndpoint,
                false
        );
    }

    private URI bind(
            Session session,
            AndroidWorkerIdentityStore.Identity identity,
            Map<String, Object> properties
    ) throws Exception {
        return session.control().bind(
                identity.workerGroupId(),
                identity.clientWorkerKey(),
                identity.workerId(),
                WorkerTransportType.WEBSOCKET,
                properties,
                requestTimeout
        );
    }

    private void installTransport(
            long currentGeneration,
            Session session,
            URI resolvedEndpoint,
            boolean endpointFromCache
    ) {
        long sessionId = session.id();
        long connectionId = nextConnectionId();
        TextWebSocketClient rawClient = networkClientFactory.create(
                resolvedEndpoint
        );
        ObservedTextWebSocketClient observedClient =
                new ObservedTextWebSocketClient(
                        rawClient,
                        observer(
                                currentGeneration,
                                sessionId,
                                connectionId
                        )
                );
        WebSocketWorkerTransport worker = new WebSocketWorkerTransport(
                observedClient,
                requireWorkerId(currentGeneration, session),
                definitions
        );
        WebSocketWorkerTransport previous;
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration, session)) {
                worker.close();
                return;
            }
            previous = session.replaceWorker(
                    worker,
                    resolvedEndpoint,
                    endpointFromCache,
                    connectionId
            );
            endpointUri = resolvedEndpoint;
            state = State.CONNECTING;
        }
        publish();
        if (previous != null) {
            previous.close();
        }
        try {
            worker.start();
        } catch (RuntimeException error) {
            worker.close();
            fail(currentGeneration, safeMessage(error));
        }
    }

    private ObservedTextWebSocketClient.Observer observer(
            long currentGeneration,
            long sessionId,
            long connectionId
    ) {
        return new ObservedTextWebSocketClient.Observer() {
            @Override
            public void onOpen() {
                execute(
                        () -> connectionOpened(
                                currentGeneration,
                                sessionId,
                                connectionId
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onDisconnected() {
                execute(
                        () -> connectionDisconnected(
                                currentGeneration,
                                sessionId,
                                connectionId
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onFailure(Throwable error) {
                execute(
                        () -> connectionFailed(
                                currentGeneration,
                                sessionId,
                                connectionId,
                                error
                        ),
                        currentGeneration
                );
            }
        };
    }

    private void connectionOpened(
            long currentGeneration,
            long sessionId,
            long connectionId
    ) {
        long openAttempt;
        synchronized (lock) {
            Session session = currentConnectionLocked(
                    currentGeneration,
                    sessionId,
                    connectionId
            );
            if (session == null) {
                return;
            }
            session.markOpened();
            openAttempt = session.openAttempt();
            state = State.TRANSPORT_CONNECTED;
            diagnosticMessage = null;
        }
        publish();
        lifecycleExecutor.schedule(
                () -> connectionBecameStable(
                        currentGeneration,
                        sessionId,
                        connectionId,
                        openAttempt
                ),
                requestTimeout.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void connectionBecameStable(
            long currentGeneration,
            long sessionId,
            long connectionId,
            long openAttempt
    ) {
        synchronized (lock) {
            Session session = currentConnectionLocked(
                    currentGeneration,
                    sessionId,
                    connectionId
            );
            if (session == null
                    || session.openAttempt() != openAttempt
                    || !session.workerConnected()) {
                return;
            }
            session.markStable();
            unstableConnections = 0;
            diagnosticMessage = null;
        }
        publish();
    }

    private void connectionFailed(
            long currentGeneration,
            long sessionId,
            long connectionId,
            Throwable error
    ) {
        boolean changed = false;
        synchronized (lock) {
            if (currentConnectionLocked(
                    currentGeneration,
                    sessionId,
                    connectionId
            ) != null) {
                diagnosticMessage = "WebSocket connection failed: "
                        + safeMessage(error);
                changed = true;
            }
        }
        if (changed) {
            publish();
        }
    }

    private void connectionDisconnected(
            long currentGeneration,
            long sessionId,
            long connectionId
    ) {
        boolean refreshEndpoint = false;
        synchronized (lock) {
            Session session = currentConnectionLocked(
                    currentGeneration,
                    sessionId,
                    connectionId
            );
            if (session == null) {
                return;
            }
            boolean wasStable = session.markDisconnected();
            state = State.CONNECTING;
            if (wasStable) {
                unstableConnections = 0;
            } else if (session.endpointFromCache()) {
                unstableConnections++;
                if (unstableConnections >= UNSTABLE_CONNECTION_LIMIT
                        && !endpointRefreshAttempted) {
                    endpointRefreshAttempted = true;
                    refreshEndpoint = true;
                    state = State.BINDING;
                }
            }
        }
        publish();
        if (refreshEndpoint) {
            refreshEndpoint(currentGeneration, sessionId);
        }
    }

    private void refreshEndpoint(
            long currentGeneration,
            long sessionId
    ) {
        Session session;
        AndroidWorkerIdentityStore.Identity identity;
        Map<String, Object> properties;
        String propertiesSha256;
        synchronized (lock) {
            session = currentSessionLocked(
                    currentGeneration,
                    sessionId
            );
            if (session == null) {
                return;
            }
            identity = activeIdentity;
            properties = activeWorkerProperties;
            propertiesSha256 = activePropertiesSha256;
        }

        try {
            URI refreshedEndpoint = bind(session, identity, properties);
            storeCacheBestEffort(
                    currentGeneration,
                    identity,
                    refreshedEndpoint,
                    propertiesSha256
            );
            boolean sameEndpoint;
            synchronized (lock) {
                Session current = currentSessionLocked(
                        currentGeneration,
                        sessionId
                );
                if (current == null) {
                    return;
                }
                sameEndpoint = refreshedEndpoint.equals(
                        current.endpointUri()
                );
                current.markEndpointRefreshed();
                diagnosticMessage = null;
                if (sameEndpoint) {
                    endpointUri = refreshedEndpoint;
                    state = current.workerConnected()
                            ? State.TRANSPORT_CONNECTED
                            : State.CONNECTING;
                }
            }
            if (!sameEndpoint) {
                installTransport(
                        currentGeneration,
                        session,
                        refreshedEndpoint,
                        false
                );
            } else {
                publish();
            }
        } catch (Exception error) {
            synchronized (lock) {
                Session current = currentSessionLocked(
                        currentGeneration,
                        sessionId
                );
                if (current == null) {
                    return;
                }
                diagnosticMessage = "Endpoint refresh failed: "
                        + safeMessage(error);
                state = current.workerConnected()
                        ? State.TRANSPORT_CONNECTED
                        : State.CONNECTING;
            }
            publish();
        }
    }

    private void refreshProperties(
            long currentGeneration,
            long sessionId
    ) {
        Session session;
        AndroidWorkerIdentityStore.Identity identity;
        String previousSha256;
        synchronized (lock) {
            session = currentSessionLocked(currentGeneration, sessionId);
            if (session == null) {
                return;
            }
            identity = activeIdentity;
            previousSha256 = activePropertiesSha256;
        }

        try {
            Map<String, Object> properties =
                    AndroidWorkerPropertiesFingerprint.snapshot(
                            workerProperties.getProperties(
                                    applicationContext
                            )
                    );
            String propertiesSha256 =
                    AndroidWorkerPropertiesFingerprint.sha256(properties);
            if (propertiesSha256.equals(previousSha256)) {
                return;
            }
            transition(
                    currentGeneration,
                    session,
                    State.BINDING,
                    null
            );
            URI refreshedEndpoint = bind(session, identity, properties);
            storeCacheBestEffort(
                    currentGeneration,
                    identity,
                    refreshedEndpoint,
                    propertiesSha256
            );
            boolean sameEndpoint;
            synchronized (lock) {
                Session current = currentSessionLocked(
                        currentGeneration,
                        sessionId
                );
                if (current == null) {
                    return;
                }
                activeWorkerProperties = properties;
                activePropertiesSha256 = propertiesSha256;
                sameEndpoint = refreshedEndpoint.equals(
                        current.endpointUri()
                );
                current.markEndpointRefreshed();
                endpointUri = refreshedEndpoint;
                diagnosticMessage = null;
                if (sameEndpoint) {
                    state = current.workerConnected()
                            ? State.TRANSPORT_CONNECTED
                            : State.CONNECTING;
                }
            }
            if (sameEndpoint) {
                publish();
            } else {
                installTransport(
                        currentGeneration,
                        session,
                        refreshedEndpoint,
                        false
                );
            }
        } catch (Exception error) {
            synchronized (lock) {
                Session current = currentSessionLocked(
                        currentGeneration,
                        sessionId
                );
                if (current == null) {
                    return;
                }
                diagnosticMessage = "Worker properties refresh failed: "
                        + safeMessage(error);
                state = current.workerConnected()
                        ? State.TRANSPORT_CONNECTED
                        : State.CONNECTING;
            }
            publish();
        }
    }

    private boolean recordIdentityAndProperties(
            long currentGeneration,
            Session session,
            AndroidWorkerIdentityStore.Identity identity,
            Map<String, Object> properties,
            String propertiesSha256
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration, session)) {
                return false;
            }
            if (!workerGroupId.equals(identity.workerGroupId())) {
                throw new IllegalStateException(
                        "Stored WorkerGroup does not match the runtime"
                );
            }
            workerId = identity.workerId();
            activeIdentity = identity;
            activeWorkerProperties = properties;
            activePropertiesSha256 = propertiesSha256;
        }
        publish();
        return true;
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

    private void transition(
            long currentGeneration,
            Session session,
            State nextState,
            String message
    ) {
        boolean changed = false;
        synchronized (lock) {
            if (isCurrentLocked(currentGeneration, session)) {
                state = nextState;
                diagnosticMessage = message;
                changed = true;
            }
        }
        if (changed) {
            publish();
        }
    }

    private String requireWorkerId(
            long currentGeneration,
            Session session
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration, session)
                    || workerId == null) {
                throw new IllegalStateException(
                        "Worker identity is unavailable"
                );
            }
            return workerId;
        }
    }

    private void storeCacheBestEffort(
            long currentGeneration,
            AndroidWorkerIdentityStore.Identity identity,
            URI resolvedEndpoint,
            String propertiesSha256
    ) {
        try {
            endpointCacheStore.store(
                    identity.workerGroupId(),
                    identity.workerId(),
                    resolvedEndpoint,
                    propertiesSha256
            );
        } catch (RuntimeException error) {
            boolean changed = false;
            synchronized (lock) {
                if (isCurrentLocked(currentGeneration)) {
                    diagnosticMessage = "Endpoint cache write failed: "
                            + safeMessage(error);
                    changed = true;
                }
            }
            if (changed) {
                publish();
            }
        }
    }

    private void clearCacheBestEffort(long currentGeneration) {
        try {
            endpointCacheStore.clear();
        } catch (RuntimeException error) {
            boolean changed = false;
            synchronized (lock) {
                if (isCurrentLocked(currentGeneration)) {
                    diagnosticMessage = "Endpoint cache clear failed: "
                            + safeMessage(error);
                    changed = true;
                }
            }
            if (changed) {
                publish();
            }
        }
    }

    private boolean cacheMatches(
            AndroidWorkerEndpointCacheStore.Entry cached,
            AndroidWorkerIdentityStore.Identity identity,
            String propertiesSha256
    ) {
        return identity.workerGroupId().equals(cached.workerGroupId())
                && identity.workerId().equals(cached.workerId())
                && propertiesSha256.equals(cached.propertiesSha256());
    }

    private Session currentSessionLocked(
            long currentGeneration,
            long sessionId
    ) {
        if (!isCurrentLocked(currentGeneration)
                || activeSession == null
                || activeSession.id() != sessionId) {
            return null;
        }
        return activeSession;
    }

    private Session currentConnectionLocked(
            long currentGeneration,
            long sessionId,
            long connectionId
    ) {
        Session session = currentSessionLocked(
                currentGeneration,
                sessionId
        );
        if (session == null || session.connectionId() != connectionId) {
            return null;
        }
        return session;
    }

    private boolean isCurrentLocked(
            long currentGeneration,
            Session session
    ) {
        return isCurrentLocked(currentGeneration)
                && activeSession == session;
    }

    private boolean isCurrentLocked(long currentGeneration) {
        return started && !closed && generation == currentGeneration;
    }

    private void fail(long currentGeneration, String message) {
        Session session;
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return;
            }
            started = false;
            session = activeSession;
            activeSession = null;
            activeIdentity = null;
            activeWorkerProperties = null;
            activePropertiesSha256 = null;
            state = State.ERROR;
            diagnosticMessage = message;
        }
        if (session != null) {
            session.close();
        }
        releaseProcessLease();
        publish();
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

    private void execute(Runnable runnable, long currentGeneration) {
        try {
            lifecycleExecutor.execute(runnable);
        } catch (RejectedExecutionException error) {
            fail(currentGeneration, "Worker lifecycle is unavailable");
        }
    }

    private void publish() {
        if (Thread.currentThread() == lifecycleThread) {
            publishNow();
            return;
        }
        try {
            lifecycleExecutor.execute(this::publishNow);
        } catch (RejectedExecutionException ignored) {
            // Terminal close may reject a stale state notification.
        }
    }

    private void publishTo(Listener listener) {
        try {
            lifecycleExecutor.execute(() -> {
                if (!listeners.contains(listener)) {
                    return;
                }
                try {
                    listener.onSnapshot(snapshot());
                } catch (RuntimeException ignored) {
                    // A host observer cannot interrupt Worker lifecycle.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A listener added during terminal close receives no callback.
        }
    }

    private void publishNow() {
        Snapshot current = snapshot();
        for (Listener listener : listeners) {
            try {
                listener.onSnapshot(current);
            } catch (RuntimeException ignored) {
                // A host observer cannot interrupt Worker lifecycle progress.
            }
        }
    }

    private void publishFinalAndShutdown() {
        if (Thread.currentThread() == lifecycleThread) {
            publishNow();
            listeners.clear();
            lifecycleExecutor.shutdownNow();
            return;
        }

        CountDownLatch published = new CountDownLatch(1);
        try {
            lifecycleExecutor.execute(() -> {
                try {
                    publishNow();
                    listeners.clear();
                } finally {
                    published.countDown();
                }
            });
            lifecycleExecutor.shutdown();
            published.await(1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            listeners.clear();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            lifecycleExecutor.shutdownNow();
        }
    }

    private long nextSessionId() {
        synchronized (lock) {
            return ++nextSessionId;
        }
    }

    private long nextConnectionId() {
        synchronized (lock) {
            return ++nextConnectionId;
        }
    }

    private WorkerControlClient requireControlClient() {
        WorkerControlClient client = controlClientFactory.create();
        if (client == null) {
            throw new IllegalStateException(
                    "controlClientFactory returned null"
            );
        }
        return client;
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

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "Unknown failure";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    public static final class Builder {

        private final Context applicationContext;
        private final URI runtimeApiBaseUrl;
        private final String workerGroupId;
        private String clientWorkerKey;
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
            Context resolvedApplicationContext =
                    applicationContext.getApplicationContext();
            this.applicationContext = resolvedApplicationContext == null
                    ? applicationContext
                    : resolvedApplicationContext;
            this.runtimeApiBaseUrl = requireRuntimeApiBaseUrl(
                    runtimeApiBaseUrl
            );
            this.workerGroupId = requireNonBlank(
                    workerGroupId,
                    "workerGroupId"
            );
        }

        public Builder clientWorkerKey(String value) {
            clientWorkerKey = requireNonBlank(
                    value,
                    "clientWorkerKey"
            );
            return this;
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
            Duration resolvedRequestTimeout = requestTimeout;
            Duration resolvedReconnectInterval = reconnectInterval;
            return new AndroidWorker(
                    applicationContext,
                    workerGroupId,
                    clientWorkerKey,
                    new AndroidWorkerIdentityStore(
                            applicationContext,
                            workerGroupId
                    ),
                    new AndroidWorkerEndpointCacheStore(
                            applicationContext,
                            workerGroupId
                    ),
                    workerProperties,
                    definitions,
                    () -> new AndroidOkHttpWorkerControlClient(
                            runtimeApiBaseUrl
                    ),
                    endpointUri ->
                            new AndroidOkHttpTextWebSocketClient(
                                    endpointUri,
                                    resolvedRequestTimeout,
                                    resolvedReconnectInterval
                            ),
                    resolvedRequestTimeout
            );
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

    private static final class Session implements AutoCloseable {

        private final long id;
        private final WorkerControlClient control;
        private WebSocketWorkerTransport worker;
        private URI endpointUri;
        private long connectionId;
        private boolean endpointFromCache;
        private boolean stable;
        private long openAttempt;
        private boolean closed;

        private Session(long id, WorkerControlClient control) {
            this.id = id;
            this.control = control;
        }

        long id() {
            return id;
        }

        WorkerControlClient control() {
            return control;
        }

        synchronized WebSocketWorkerTransport replaceWorker(
                WebSocketWorkerTransport replacement,
                URI replacementEndpoint,
                boolean replacementFromCache,
                long replacementConnectionId
        ) {
            if (closed) {
                replacement.close();
                return null;
            }
            WebSocketWorkerTransport previous = worker;
            worker = replacement;
            endpointUri = replacementEndpoint;
            endpointFromCache = replacementFromCache;
            connectionId = replacementConnectionId;
            stable = false;
            openAttempt++;
            return previous;
        }

        synchronized URI endpointUri() {
            return endpointUri;
        }

        synchronized long connectionId() {
            return connectionId;
        }

        synchronized boolean endpointFromCache() {
            return endpointFromCache;
        }

        synchronized void markEndpointRefreshed() {
            endpointFromCache = false;
        }

        synchronized void markOpened() {
            stable = false;
            openAttempt++;
        }

        synchronized long openAttempt() {
            return openAttempt;
        }

        synchronized void markStable() {
            stable = true;
        }

        synchronized boolean markDisconnected() {
            boolean wasStable = stable;
            stable = false;
            openAttempt++;
            return wasStable;
        }

        synchronized boolean workerConnected() {
            return worker != null && worker.isConnected();
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
            control.close();
        }
    }
}
