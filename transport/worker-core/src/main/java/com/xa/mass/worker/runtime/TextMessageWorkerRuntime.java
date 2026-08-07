package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.connection.TextMessageWorkerTransport;
import com.xa.mass.worker.transport.connection.TextMessageWorkerTransport.PendingResultSlot;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
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
 * Supervises Worker preparation and one bounded-reconnecting text transport.
 */
public final class TextMessageWorkerRuntime implements WorkerLifecycle {

    @FunctionalInterface
    public interface NetworkClientFactory {

        TextMessageClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final String workerGroupId;
    private final WorkerTransportType transportType;
    private final WorkerIdentityStore identityStore;
    private final WorkerPropertiesProvider propertiesProvider;
    private final WorkerCommandExecutor commandExecutor;
    private final WorkerControlClient controlClient;
    private final NetworkClientFactory networkClientFactory;
    private final Duration requestTimeout;
    private final WorkerRetryPolicy retryPolicy;
    private final ScheduledExecutorService lifecycleExecutor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread lifecycleThread;

    private long generation;
    private long nextTransportId;
    private State state = State.STOPPED;
    private PrepareOperation prepareOperation = PrepareOperation.NONE;
    private String workerId;
    private URI endpointUri;
    private String diagnosticMessage;
    private WorkerPropertiesSnapshot activeProperties;
    private TextMessageWorkerTransport activeTransport;
    private long activeTransportId;
    private PendingResultSlot pendingResultSlot;

    public TextMessageWorkerRuntime(
            String workerGroupId,
            WorkerTransportType transportType,
            WorkerIdentityStore identityStore,
            WorkerPropertiesProvider propertiesProvider,
            Collection<? extends WorkerEventDefinition<?>> definitions,
            WorkerControlClient controlClient,
            NetworkClientFactory networkClientFactory,
            Duration requestTimeout,
            WorkerRetryPolicy retryPolicy
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
        this.commandExecutor = new WorkerCommandDispatcher(definitions);
        this.controlClient = Objects.requireNonNull(
                controlClient,
                "controlClient"
        );
        this.networkClientFactory = Objects.requireNonNull(
                networkClientFactory,
                "networkClientFactory"
        );
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.retryPolicy = Objects.requireNonNull(
                retryPolicy,
                "retryPolicy"
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
            if (state == State.CLOSED) {
                throw new IllegalStateException(
                        "TextMessageWorkerRuntime is closed"
                );
            }
            if (state == State.STARTING || state == State.RUNNING) {
                return;
            }
            currentGeneration = ++generation;
            state = State.STARTING;
            prepareOperation = PrepareOperation.NONE;
            endpointUri = null;
            diagnosticMessage = null;
            activeProperties = null;
            activeTransport = null;
            activeTransportId = 0L;
            pendingResultSlot = new PendingResultSlot();
        }
        publish();
        execute(() -> beginStart(currentGeneration), currentGeneration);
    }

    @Override
    public void stop() {
        TextMessageWorkerTransport transport;
        PendingResultSlot resultSlot;
        synchronized (lock) {
            if (state == State.CLOSED || state == State.STOPPED) {
                return;
            }
            generation++;
            transport = activeTransport;
            resultSlot = pendingResultSlot;
            clearRunResourcesLocked();
            state = State.STOPPED;
            prepareOperation = PrepareOperation.NONE;
            diagnosticMessage = null;
        }
        closeQuietly(transport);
        closeQuietly(resultSlot);
        publish();
    }

    @Override
    public void refreshProperties() {
        long currentGeneration;
        long transportId;
        synchronized (lock) {
            if (state == State.CLOSED) {
                throw new IllegalStateException(
                        "TextMessageWorkerRuntime is closed"
                );
            }
            if (state != State.RUNNING
                    || activeTransport == null) {
                return;
            }
            currentGeneration = generation;
            transportId = activeTransportId;
        }
        execute(
                () -> refreshProperties(currentGeneration, transportId),
                currentGeneration
        );
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (state == State.CLOSED) {
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
                    prepareOperation,
                    connectionStateLocked(),
                    workerId,
                    endpointUri,
                    diagnosticMessage
            );
        }
    }

    @Override
    public boolean isConnected() {
        synchronized (lock) {
            return state == State.RUNNING
                    && activeTransport != null
                    && activeTransport.isConnected();
        }
    }

    @Override
    public void close() {
        TextMessageWorkerTransport transport;
        PendingResultSlot resultSlot;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            generation++;
            transport = activeTransport;
            resultSlot = pendingResultSlot;
            clearRunResourcesLocked();
            state = State.CLOSED;
            prepareOperation = PrepareOperation.NONE;
        }
        closeQuietly(transport);
        closeQuietly(resultSlot);
        closeQuietly(controlClient);
        publishFinalAndShutdown();
    }

    private void beginStart(long currentGeneration) {
        try {
            WorkerPropertiesSnapshot properties = loadProperties();
            Optional<String> cached = identityStore.loadWorkerId();
            String cachedWorkerId = cached.isPresent()
                    ? requireCanonicalWorkerId(cached.get())
                    : null;
            synchronized (lock) {
                if (!isCurrentLocked(currentGeneration)) {
                    return;
                }
                workerId = cachedWorkerId;
            }
            attemptPrepare(currentGeneration, properties, 1);
        } catch (Exception error) {
            fail(currentGeneration, safeMessage(error));
        }
    }

    private void beginReprepare(long currentGeneration) {
        try {
            WorkerPropertiesSnapshot properties = loadProperties();
            synchronized (lock) {
                if (!isCurrentLocked(currentGeneration)) {
                    return;
                }
                if (workerId == null) {
                    throw new IllegalStateException(
                            "workerId is missing during endpoint rebind"
                    );
                }
            }
            attemptPrepare(currentGeneration, properties, 1);
        } catch (Exception error) {
            fail(currentGeneration, safeMessage(error));
        }
    }

    private void attemptPrepare(
            long currentGeneration,
            WorkerPropertiesSnapshot properties,
            int attempt
    ) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        URI resolvedEndpoint;
        String currentWorkerId;
        try {
            synchronized (lock) {
                if (!isCurrentLocked(currentGeneration)) {
                    return;
                }
                currentWorkerId = workerId;
            }
            if (currentWorkerId == null) {
                if (!transitionPrepare(
                        currentGeneration,
                        PrepareOperation.REGISTERING,
                        null
                )) {
                    return;
                }
                currentWorkerId = requireCanonicalWorkerId(
                        controlClient.register(
                                workerGroupId,
                                properties.properties(),
                                requestTimeout
                        )
                );
                try {
                    identityStore.saveWorkerId(currentWorkerId);
                } catch (IOException error) {
                    throw new IllegalStateException(
                            "Unable to persist workerId",
                            error
                    );
                }
                synchronized (lock) {
                    if (!isCurrentLocked(currentGeneration)) {
                        return;
                    }
                    workerId = currentWorkerId;
                }
                publish();
            }

            if (!transitionPrepare(
                    currentGeneration,
                    PrepareOperation.BINDING,
                    null
            )) {
                return;
            }
            resolvedEndpoint = controlClient.bind(
                    workerGroupId,
                    currentWorkerId,
                    transportType,
                    properties.properties(),
                    requestTimeout
            );
        } catch (Exception error) {
            handlePrepareFailure(
                    currentGeneration,
                    properties,
                    attempt,
                    error
            );
            return;
        }

        try {
            installTransport(
                    currentGeneration,
                    properties,
                    currentWorkerId,
                    resolvedEndpoint
            );
        } catch (Exception error) {
            handlePrepareFailure(
                    currentGeneration,
                    properties,
                    attempt,
                    error
            );
        }
    }

    private void handlePrepareFailure(
            long currentGeneration,
            WorkerPropertiesSnapshot properties,
            int attempt,
            Exception error
    ) {
        String message = "Worker preparation failed: " + safeMessage(error);
        if (!isRetryableControlFailure(error)
                || attempt >= retryPolicy.maxPrepareAttempts()) {
            fail(currentGeneration, message);
            return;
        }
        if (!transitionPrepare(
                currentGeneration,
                currentPrepareOperation(),
                message
        )) {
            return;
        }
        try {
            lifecycleExecutor.schedule(
                    () -> attemptPrepare(
                            currentGeneration,
                            properties,
                            attempt + 1
                    ),
                    retryPolicy.prepareRetryInterval().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // stop/close owns cancellation of stale prepare retries.
        }
    }

    private void installTransport(
            long currentGeneration,
            WorkerPropertiesSnapshot properties,
            String currentWorkerId,
            URI resolvedEndpoint
    ) {
        Objects.requireNonNull(resolvedEndpoint, "endpointUri");
        TextMessageClient rawClient = networkClientFactory.create(
                resolvedEndpoint
        );
        if (rawClient == null) {
            throw new IllegalStateException(
                    "networkClientFactory returned null"
            );
        }

        long transportId;
        PendingResultSlot resultSlot;
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                closeQuietly(rawClient);
                return;
            }
            transportId = ++nextTransportId;
            resultSlot = pendingResultSlot;
        }
        TextMessageWorkerTransport replacement;
        try {
            replacement = new TextMessageWorkerTransport(
                    rawClient,
                    currentWorkerId,
                    commandExecutor,
                    resultSlot,
                    observer(currentGeneration, transportId)
            );
        } catch (RuntimeException error) {
            closeQuietly(rawClient);
            throw error;
        }

        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)
                    || activeTransport != null) {
                closeQuietly(replacement);
                return;
            }
            activeTransport = replacement;
            activeTransportId = transportId;
            activeProperties = properties;
            endpointUri = resolvedEndpoint;
            state = State.RUNNING;
            prepareOperation = PrepareOperation.NONE;
            diagnosticMessage = null;
        }
        publish();
        try {
            replacement.start();
        } catch (RuntimeException error) {
            synchronized (lock) {
                if (isCurrentTransportLocked(
                        currentGeneration,
                        transportId
                )) {
                    activeTransport = null;
                    activeTransportId = 0L;
                }
            }
            closeQuietly(replacement);
            fail(currentGeneration, safeMessage(error));
        }
    }

    private TextMessageWorkerTransport.Observer observer(
            long currentGeneration,
            long transportId
    ) {
        return new TextMessageWorkerTransport.Observer() {
            @Override
            public void onReady() {
                execute(
                        () -> transportReady(
                                currentGeneration,
                                transportId
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onDisconnected() {
                execute(
                        () -> transportDisconnected(
                                currentGeneration,
                                transportId
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onFailure(Throwable error) {
                execute(
                        () -> transportFailed(
                                currentGeneration,
                                transportId,
                                error
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onReconnectExhausted() {
                execute(
                        () -> transportReconnectExhausted(
                                currentGeneration,
                                transportId
                        ),
                        currentGeneration
                );
            }
        };
    }

    private void transportReady(
            long currentGeneration,
            long transportId
    ) {
        synchronized (lock) {
            if (!isCurrentTransportLocked(currentGeneration, transportId)
                    || !activeTransport.isConnected()) {
                return;
            }
            diagnosticMessage = null;
        }
        publish();
    }

    private void transportDisconnected(
            long currentGeneration,
            long transportId
    ) {
        synchronized (lock) {
            if (!isCurrentTransportLocked(currentGeneration, transportId)) {
                return;
            }
        }
        publish();
    }

    private void transportFailed(
            long currentGeneration,
            long transportId,
            Throwable error
    ) {
        synchronized (lock) {
            if (!isCurrentTransportLocked(currentGeneration, transportId)) {
                return;
            }
            diagnosticMessage = "Text-message connection failed: "
                    + safeMessage(error);
        }
        publish();
    }

    private void transportReconnectExhausted(
            long currentGeneration,
            long transportId
    ) {
        TextMessageWorkerTransport exhausted;
        synchronized (lock) {
            if (!isCurrentTransportLocked(currentGeneration, transportId)) {
                return;
            }
            exhausted = activeTransport;
            activeTransport = null;
            activeTransportId = 0L;
            activeProperties = null;
            endpointUri = null;
            state = State.STARTING;
            prepareOperation = PrepareOperation.NONE;
            diagnosticMessage = "Connection retry budget exhausted; "
                    + "preparing Worker endpoint again";
        }
        closeQuietly(exhausted);
        publish();
        beginReprepare(currentGeneration);
    }

    private void refreshProperties(
            long currentGeneration,
            long transportId
    ) {
        WorkerPropertiesSnapshot previous;
        String currentWorkerId;
        URI currentEndpoint;
        synchronized (lock) {
            if (!isCurrentTransportLocked(currentGeneration, transportId)) {
                return;
            }
            previous = activeProperties;
            currentWorkerId = workerId;
            currentEndpoint = endpointUri;
            prepareOperation = PrepareOperation.BINDING;
            diagnosticMessage = null;
        }
        publish();

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
                finishRefresh(currentGeneration, transportId, null);
                return;
            }
            URI refreshedEndpoint;
            refreshedEndpoint = controlClient.bind(
                    workerGroupId,
                    currentWorkerId,
                    transportType,
                    refreshed.properties(),
                    requestTimeout
            );
            if (!currentEndpoint.equals(refreshedEndpoint)) {
                fail(
                        currentGeneration,
                        "Worker endpoint changed during properties refresh; "
                                + "restart is required"
                );
                return;
            }
            synchronized (lock) {
                if (!isCurrentTransportLocked(
                        currentGeneration,
                        transportId
                )) {
                    return;
                }
                activeProperties = refreshed;
                prepareOperation = PrepareOperation.NONE;
                diagnosticMessage = null;
            }
            publish();
        } catch (Exception error) {
            finishRefresh(
                    currentGeneration,
                    transportId,
                    "Worker properties refresh failed: "
                            + safeMessage(error)
            );
        }
    }

    private void finishRefresh(
            long currentGeneration,
            long transportId,
            String message
    ) {
        synchronized (lock) {
            if (!isCurrentTransportLocked(currentGeneration, transportId)) {
                return;
            }
            prepareOperation = PrepareOperation.NONE;
            diagnosticMessage = message;
        }
        publish();
    }

    private WorkerPropertiesSnapshot loadProperties() throws Exception {
        return WorkerPropertiesSnapshot.from(
                propertiesProvider.loadProperties()
        );
    }

    private boolean transitionPrepare(
            long currentGeneration,
            PrepareOperation operation,
            String message
    ) {
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return false;
            }
            state = State.STARTING;
            prepareOperation = operation;
            diagnosticMessage = message;
        }
        publish();
        return true;
    }

    private PrepareOperation currentPrepareOperation() {
        synchronized (lock) {
            return prepareOperation;
        }
    }

    private void fail(long currentGeneration, String message) {
        TextMessageWorkerTransport transport;
        PendingResultSlot resultSlot;
        synchronized (lock) {
            if (!isCurrentLocked(currentGeneration)) {
                return;
            }
            transport = activeTransport;
            resultSlot = pendingResultSlot;
            clearRunResourcesLocked();
            state = State.ERROR;
            prepareOperation = PrepareOperation.NONE;
            diagnosticMessage = message;
        }
        closeQuietly(transport);
        closeQuietly(resultSlot);
        publish();
    }

    private void clearRunResourcesLocked() {
        activeTransport = null;
        activeTransportId = 0L;
        activeProperties = null;
        endpointUri = null;
        pendingResultSlot = null;
    }

    private ConnectionState connectionStateLocked() {
        if (activeTransport == null) {
            return ConnectionState.DISCONNECTED;
        }
        return activeTransport.isConnected()
                ? ConnectionState.CONNECTED
                : ConnectionState.CONNECTING;
    }

    private boolean isCurrent(long currentGeneration) {
        synchronized (lock) {
            return isCurrentLocked(currentGeneration);
        }
    }

    private boolean isCurrentLocked(long currentGeneration) {
        return generation == currentGeneration
                && (state == State.STARTING || state == State.RUNNING);
    }

    private boolean isCurrentTransportLocked(
            long currentGeneration,
            long transportId
    ) {
        return isCurrentLocked(currentGeneration)
                && activeTransport != null
                && activeTransportId == transportId;
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
}
