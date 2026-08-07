package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.connection.TextMessageWorkerTransport;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates identity recovery, Register/Bind, and one long-lived Worker
 * transport session. Network reconnect belongs to {@link TextMessageClient};
 * command execution and pending results belong to
 * {@link TextMessageWorkerTransport}.
 */
public final class TextMessageWorkerRuntime implements WorkerLifecycle {

    @FunctionalInterface
    public interface ControlClientFactory {

        WorkerControlClient create();
    }

    @FunctionalInterface
    public interface NetworkClientFactory {

        TextMessageClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final String workerGroupId;
    private final WorkerTransportType transportType;
    private final WorkerIdentityStore identityStore;
    private final WorkerPropertiesProvider propertiesProvider;
    private final List<WorkerEventDefinition<?>> definitions;
    private final ControlClientFactory controlClientFactory;
    private final NetworkClientFactory networkClientFactory;
    private final Duration requestTimeout;
    private final Duration reconnectInterval;
    private final ScheduledExecutorService lifecycleExecutor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread lifecycleThread;

    private boolean started;
    private boolean closed;
    private long generation;
    private long nextSessionId;
    private State state = State.STOPPED;
    private String workerId;
    private URI endpointUri;
    private String diagnosticMessage;
    private WorkerPropertiesSnapshot activeProperties;
    private Session activeSession;

    public TextMessageWorkerRuntime(
            String workerGroupId,
            WorkerTransportType transportType,
            WorkerIdentityStore identityStore,
            WorkerPropertiesProvider propertiesProvider,
            Collection<? extends WorkerEventDefinition<?>> definitions,
            ControlClientFactory controlClientFactory,
            NetworkClientFactory networkClientFactory,
            Duration requestTimeout,
            Duration reconnectInterval
    ) {
        this.workerGroupId = requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        this.transportType = requireTextMessageTransportType(transportType);
        this.identityStore = Objects.requireNonNull(
                identityStore,
                "identityStore"
        );
        this.propertiesProvider = Objects.requireNonNull(
                propertiesProvider,
                "propertiesProvider"
        );
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "definitions must not be empty"
            );
        }
        List<WorkerEventDefinition<?>> copied = new ArrayList<>(definitions);
        if (copied.contains(null)) {
            throw new IllegalArgumentException(
                    "definitions must not contain null"
            );
        }
        this.definitions = Collections.unmodifiableList(copied);
        this.controlClientFactory = Objects.requireNonNull(
                controlClientFactory,
                "controlClientFactory"
        );
        this.networkClientFactory = Objects.requireNonNull(
                networkClientFactory,
                "networkClientFactory"
        );
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        lifecycleExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "xa-text-message-worker-lifecycle"
                    );
                    thread.setDaemon(true);
                    lifecycleThread = thread;
                    return thread;
                }
        );
    }

    @Override
    public void start() {
        long currentGeneration;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "TextMessageWorkerRuntime is closed"
                );
            }
            if (started) {
                return;
            }
            started = true;
            currentGeneration = ++generation;
            state = State.STARTING;
            diagnosticMessage = null;
            endpointUri = null;
        }
        publish();
        execute(() -> beginStart(currentGeneration), currentGeneration);
    }

    @Override
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
            activeProperties = null;
            endpointUri = null;
            state = State.STOPPED;
            diagnosticMessage = null;
        }
        closeQuietly(session);
        publish();
    }

    @Override
    public void refreshProperties() {
        long currentGeneration;
        long sessionId;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "TextMessageWorkerRuntime is closed"
                );
            }
            if (!started || activeSession == null
                    || activeSession.transport() == null) {
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
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "TextMessageWorkerRuntime is closed"
                );
            }
            listeners.add(listener);
        }
        publishTo(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
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

    @Override
    public boolean isConnected() {
        synchronized (lock) {
            return state == State.TRANSPORT_CONNECTED
                    && activeSession != null
                    && activeSession.transport() != null
                    && activeSession.transport().isConnected();
        }
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
            activeProperties = null;
            endpointUri = null;
            state = State.CLOSED;
        }
        closeQuietly(session);
        publishFinalAndShutdown();
    }

    private void beginStart(long currentGeneration) {
        Session session = null;
        try {
            WorkerPropertiesSnapshot properties = loadProperties();
            Optional<String> cached = identityStore.loadWorkerId();
            String cachedWorkerId = cached.isPresent()
                    ? requireCanonicalWorkerId(cached.get())
                    : null;
            WorkerControlClient control = controlClientFactory.create();
            if (control == null) {
                throw new IllegalStateException(
                        "controlClientFactory returned null"
                );
            }
            session = new Session(nextSessionId(), control);
            if (!installSession(
                    currentGeneration,
                    session,
                    properties,
                    cachedWorkerId
            )) {
                session.close();
                return;
            }
            if (cachedWorkerId == null) {
                transition(
                        currentGeneration,
                        session,
                        State.REGISTERING,
                        null
                );
                attemptRegister(currentGeneration, session);
            } else {
                attemptBind(currentGeneration, session);
            }
        } catch (Exception error) {
            closeQuietly(session);
            fail(currentGeneration, safeMessage(error));
        }
    }

    private void attemptRegister(
            long currentGeneration,
            Session session
    ) {
        if (!isCurrent(currentGeneration, session)) {
            return;
        }
        try {
            String registeredWorkerId = requireCanonicalWorkerId(
                    session.control().register(
                            workerGroupId,
                            session.properties().properties(),
                            requestTimeout
                    )
            );
            identityStore.saveWorkerId(registeredWorkerId);
            synchronized (lock) {
                if (!isCurrentLocked(currentGeneration, session)) {
                    return;
                }
                workerId = registeredWorkerId;
                session.workerId(registeredWorkerId);
            }
            publish();
            attemptBind(currentGeneration, session);
        } catch (Exception error) {
            handleControlFailure(
                    currentGeneration,
                    session,
                    State.REGISTERING,
                    "Worker registration failed",
                    error,
                    () -> attemptRegister(currentGeneration, session)
            );
        }
    }

    private void attemptBind(
            long currentGeneration,
            Session session
    ) {
        if (!transition(
                currentGeneration,
                session,
                State.BINDING,
                null
        )) {
            return;
        }
        try {
            URI resolvedEndpoint = session.control().bind(
                    workerGroupId,
                    session.workerId(),
                    transportType,
                    session.properties().properties(),
                    requestTimeout
            );
            installTransport(
                    currentGeneration,
                    session,
                    resolvedEndpoint
            );
        } catch (Exception error) {
            handleControlFailure(
                    currentGeneration,
                    session,
                    State.BINDING,
                    "Worker endpoint binding failed",
                    error,
                    () -> attemptBind(currentGeneration, session)
            );
        }
    }

    private void handleControlFailure(
            long currentGeneration,
            Session session,
            State retryState,
            String prefix,
            Exception error,
            Runnable retry
    ) {
        if (!isRetryableControlFailure(error)) {
            fail(currentGeneration, prefix + ": " + safeMessage(error));
            return;
        }
        if (!transition(
                currentGeneration,
                session,
                retryState,
                prefix + ": " + safeMessage(error)
        )) {
            return;
        }
        try {
            lifecycleExecutor.schedule(
                    retry,
                    reconnectInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // stop/close owns cancellation of a pending retry.
        }
    }

    private void installTransport(
            long currentGeneration,
            Session session,
            URI resolvedEndpoint
    ) {
        TextMessageClient rawClient = networkClientFactory.create(
                Objects.requireNonNull(resolvedEndpoint, "endpointUri")
        );
        if (rawClient == null) {
            throw new IllegalStateException(
                    "networkClientFactory returned null"
            );
        }
        TextMessageWorkerTransport replacement =
                new TextMessageWorkerTransport(
                        rawClient,
                        session.workerId(),
                        definitions,
                        observer(currentGeneration, session.id())
                );
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration, session)) {
                closeQuietly(replacement);
                return;
            }
            session.installTransport(resolvedEndpoint, replacement);
            endpointUri = resolvedEndpoint;
            state = State.CONNECTING;
            diagnosticMessage = null;
        }
        publish();
        try {
            replacement.start();
        } catch (RuntimeException error) {
            synchronized (lock) {
                if (isCurrentLocked(currentGeneration, session)
                        && session.transport() == replacement) {
                    session.clearTransport(replacement);
                }
            }
            closeQuietly(replacement);
            fail(currentGeneration, safeMessage(error));
        }
    }

    private TextMessageWorkerTransport.Observer observer(
            long currentGeneration,
            long sessionId
    ) {
        return new TextMessageWorkerTransport.Observer() {
            @Override
            public void onReady() {
                execute(
                        () -> transportReady(
                                currentGeneration,
                                sessionId
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onDisconnected() {
                execute(
                        () -> transportDisconnected(
                                currentGeneration,
                                sessionId
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onFailure(Throwable error) {
                execute(
                        () -> transportFailed(
                                currentGeneration,
                                sessionId,
                                error
                        ),
                        currentGeneration
                );
            }
        };
    }

    private void transportReady(
            long currentGeneration,
            long sessionId
    ) {
        if (updateTransportState(
                currentGeneration,
                sessionId,
                State.TRANSPORT_CONNECTED,
                null
        )) {
            publish();
        }
    }

    private void transportDisconnected(
            long currentGeneration,
            long sessionId
    ) {
        if (updateTransportState(
                currentGeneration,
                sessionId,
                State.CONNECTING,
                null
        )) {
            publish();
        }
    }

    private void transportFailed(
            long currentGeneration,
            long sessionId,
            Throwable error
    ) {
        if (updateTransportState(
                currentGeneration,
                sessionId,
                State.CONNECTING,
                "Text-message transport failed: " + safeMessage(error)
        )) {
            publish();
        }
    }

    private boolean updateTransportState(
            long currentGeneration,
            long sessionId,
            State next,
            String message
    ) {
        synchronized (lock) {
            Session session = currentSessionLocked(
                    currentGeneration,
                    sessionId
            );
            if (session == null || session.transport() == null) {
                return false;
            }
            if (next == State.TRANSPORT_CONNECTED
                    && !session.transport().isConnected()) {
                return false;
            }
            state = next;
            diagnosticMessage = message;
            return true;
        }
    }

    private void refreshProperties(
            long currentGeneration,
            long sessionId
    ) {
        Session session;
        WorkerPropertiesSnapshot previous;
        synchronized (lock) {
            session = currentSessionLocked(currentGeneration, sessionId);
            if (session == null || session.transport() == null) {
                return;
            }
            previous = activeProperties;
        }

        try {
            WorkerPropertiesSnapshot refreshed = loadProperties();
            if (!previous.clientWorkerKey().equals(
                    refreshed.clientWorkerKey()
            )) {
                throw new IllegalStateException(
                        "workerProperties.clientWorkerKey cannot change"
                );
            }
            if (previous.canonicalJson().equals(
                    refreshed.canonicalJson()
            )) {
                return;
            }
            URI refreshedEndpoint = session.control().bind(
                    workerGroupId,
                    session.workerId(),
                    transportType,
                    refreshed.properties(),
                    requestTimeout
            );
            URI previousEndpoint;
            synchronized (lock) {
                Session current = currentSessionLocked(
                        currentGeneration,
                        sessionId
                );
                if (current == null) {
                    return;
                }
                previousEndpoint = current.endpointUri();
            }
            if (!previousEndpoint.equals(refreshedEndpoint)) {
                synchronized (lock) {
                    Session current = currentSessionLocked(
                            currentGeneration,
                            sessionId
                    );
                    if (current == null) {
                        return;
                    }
                    current.properties(refreshed);
                    activeProperties = refreshed;
                    diagnosticMessage = "Worker endpoint changed; "
                            + "stop and start to use the new endpoint";
                }
                publish();
            } else {
                synchronized (lock) {
                    Session current = currentSessionLocked(
                            currentGeneration,
                            sessionId
                    );
                    if (current == null) {
                        return;
                    }
                    current.properties(refreshed);
                    activeProperties = refreshed;
                    diagnosticMessage = null;
                }
                publish();
            }
        } catch (Exception error) {
            synchronized (lock) {
                if (currentSessionLocked(
                        currentGeneration,
                        sessionId
                ) == null) {
                    return;
                }
                diagnosticMessage = "Worker properties refresh failed: "
                        + safeMessage(error);
            }
            publish();
        }
    }

    private WorkerPropertiesSnapshot loadProperties() throws Exception {
        return WorkerPropertiesSnapshot.from(
                propertiesProvider.loadProperties()
        );
    }

    private boolean installSession(
            long currentGeneration,
            Session session,
            WorkerPropertiesSnapshot properties,
            String cachedWorkerId
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return false;
            }
            activeSession = session;
            activeProperties = properties;
            session.properties(properties);
            session.workerId(cachedWorkerId);
            workerId = cachedWorkerId;
            return true;
        }
    }

    private boolean transition(
            long currentGeneration,
            Session session,
            State next,
            String message
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration, session)) {
                return false;
            }
            state = next;
            diagnosticMessage = message;
        }
        publish();
        return true;
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
            activeProperties = null;
            endpointUri = null;
            state = State.ERROR;
            diagnosticMessage = message;
        }
        closeQuietly(session);
        publish();
    }

    private boolean isCurrent(
            long currentGeneration,
            Session session
    ) {
        synchronized (lock) {
            return isCurrentLocked(currentGeneration, session);
        }
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

    private void execute(Runnable runnable, long currentGeneration) {
        try {
            lifecycleExecutor.execute(runnable);
        } catch (RejectedExecutionException error) {
            fail(currentGeneration, "Worker lifecycle is unavailable");
        }
    }

    private void publish() {
        Snapshot current = snapshot();
        if (Thread.currentThread() == lifecycleThread) {
            publishNow(current);
            return;
        }
        try {
            lifecycleExecutor.execute(() -> publishNow(current));
        } catch (RejectedExecutionException ignored) {
            // Terminal close may reject a stale notification.
        }
    }

    private void publishTo(Listener listener) {
        try {
            lifecycleExecutor.execute(() -> {
                if (listeners.contains(listener)) {
                    notifyListener(listener, snapshot());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A listener added during close receives no callback.
        }
    }

    private void publishNow(Snapshot current) {
        for (Listener listener : listeners) {
            notifyListener(listener, current);
        }
    }

    private static void notifyListener(
            Listener listener,
            Snapshot snapshot
    ) {
        try {
            listener.onSnapshot(snapshot);
        } catch (RuntimeException ignored) {
            // Host observers cannot interrupt Worker lifecycle.
        }
    }

    private void publishFinalAndShutdown() {
        Snapshot current = snapshot();
        if (Thread.currentThread() == lifecycleThread) {
            publishNow(current);
            listeners.clear();
            lifecycleExecutor.shutdownNow();
            return;
        }
        try {
            lifecycleExecutor.execute(() -> {
                publishNow(current);
                listeners.clear();
            });
        } catch (RejectedExecutionException ignored) {
            listeners.clear();
        } finally {
            lifecycleExecutor.shutdown();
        }
    }

    private long nextSessionId() {
        synchronized (lock) {
            return ++nextSessionId;
        }
    }

    private static boolean isRetryableControlFailure(Exception error) {
        if (error instanceof IOException) {
            return true;
        }
        if (error instanceof WorkerException) {
            return ((WorkerException) error).errorCode()
                    == WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE;
        }
        return false;
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

    private static String requireCanonicalWorkerId(String value) {
        try {
            if (value == null
                    || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "workerId must be a canonical UUID",
                    error
            );
        }
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

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Lifecycle teardown is best-effort at this local boundary.
        }
    }

    private static final class Session implements AutoCloseable {

        private final long id;
        private final WorkerControlClient control;
        private String workerId;
        private WorkerPropertiesSnapshot properties;
        private URI endpointUri;
        private TextMessageWorkerTransport transport;
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

        synchronized String workerId() {
            return workerId;
        }

        synchronized void workerId(String value) {
            workerId = value;
        }

        synchronized WorkerPropertiesSnapshot properties() {
            return properties;
        }

        synchronized void properties(WorkerPropertiesSnapshot value) {
            properties = value;
        }

        synchronized URI endpointUri() {
            return endpointUri;
        }

        synchronized void installTransport(
                URI value,
                TextMessageWorkerTransport replacement
        ) {
            if (closed) {
                replacement.close();
                return;
            }
            if (transport != null) {
                throw new IllegalStateException(
                        "Session already owns a text-message transport"
                );
            }
            endpointUri = value;
            transport = replacement;
        }

        synchronized TextMessageWorkerTransport transport() {
            return transport;
        }

        synchronized void clearTransport(
                TextMessageWorkerTransport expected
        ) {
            if (transport == expected) {
                transport = null;
                endpointUri = null;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (transport != null) {
                    transport.close();
                }
            } finally {
                transport = null;
                endpointUri = null;
                control.close();
            }
        }
    }
}
