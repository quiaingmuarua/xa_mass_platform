package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.execution.WorkerCommandExecutor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Coordinates one explicitly requested Worker run.
 *
 * <p>Each accepted start performs one preparation and installs at most one
 * runtime. Endpoint termination ends the run; only the host can start another.
 */
public final class WorkerRunController implements WorkerLifecycle {

    @FunctionalInterface
    public interface NetworkClientFactory {

        TextMessageClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final WorkerPreparation preparation;
    private final WorkerCommandExecutor commandExecutor;
    private final NetworkClientFactory networkClientFactory;
    private final WorkerExecutionResources executionResources;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private State state = State.STOPPED;
    private boolean closed;
    private boolean stopRequested;
    private boolean startTaskActive;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerRuntime activeRuntime;
    private String diagnosticMessage;

    public WorkerRunController(
            WorkerPreparation preparation,
            WorkerCommandExecutor commandExecutor,
            NetworkClientFactory networkClientFactory,
            WorkerExecutionResources executionResources
    ) {
        this.preparation = Objects.requireNonNull(
                preparation,
                "preparation"
        );
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.networkClientFactory = Objects.requireNonNull(
                networkClientFactory,
                "networkClientFactory"
        );
        this.executionResources = Objects.requireNonNull(
                executionResources,
                "executionResources"
        );
    }

    @Override
    public void start() {
        Throwable submissionFailure = null;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "WorkerRunController is closed"
                );
            }
            if (state == State.RUNNING) {
                return;
            }
            state = State.RUNNING;
            stopRequested = false;
            startTaskActive = true;
            preparedWorker = null;
            activeRuntime = null;
            diagnosticMessage = null;
            try {
                executionResources.controlExecutor().execute(
                        this::runStartTask
                );
            } catch (Throwable error) {
                startTaskActive = false;
                transitionStoppedLocked(
                        "Worker control executor is unavailable"
                );
                submissionFailure = error;
            }
        }
        publish();
        if (submissionFailure != null) {
            rethrowError(submissionFailure);
            throw new IllegalStateException(
                    "Worker control executor is unavailable",
                    submissionFailure
            );
        }
    }

    @Override
    public void stop() {
        TextMessageWorkerRuntime runtime;
        boolean stopped = false;
        synchronized (lock) {
            if (closed || state == State.STOPPED || stopRequested) {
                return;
            }
            stopRequested = true;
            runtime = activeRuntime;
            if (runtime == null && !startTaskActive) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
        if (runtime != null) {
            runtime.requestStop();
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    state,
                    preparedWorker == null
                            ? null
                            : preparedWorker.workerId(),
                    preparedWorker == null
                            ? null
                            : preparedWorker.endpointUri(),
                    diagnosticMessage
            );
        }
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException(
                        "WorkerRunController is closed"
                );
            }
            listeners.add(listener);
        }
        notifyListener(listener, snapshot());
    }

    @Override
    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void close() {
        TextMessageWorkerRuntime runtime;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            stopRequested = true;
            runtime = activeRuntime;
            activeRuntime = null;
            startTaskActive = false;
            preparedWorker = null;
            state = State.STOPPED;
            diagnosticMessage = null;
        }

        closeQuietly(runtime);
        closeQuietly(preparation);
        Snapshot closingSnapshot = snapshot();
        List<Listener> closingListeners = new ArrayList<>(listeners);
        listeners.clear();
        publish(closingListeners, closingSnapshot);
    }

    private void runStartTask() {
        boolean stoppedBeforePreparation;
        synchronized (lock) {
            if (!startTaskActive) {
                return;
            }
            stoppedBeforePreparation = closed || stopRequested;
            if (stoppedBeforePreparation) {
                finishStartTaskLocked(null);
            }
        }
        if (stoppedBeforePreparation) {
            publish();
            return;
        }

        PreparedWorker prepared;
        try {
            prepared = Objects.requireNonNull(
                    preparation.prepare(),
                    "preparation returned null"
            );
        } catch (Throwable error) {
            finishFailedStart(error);
            rethrowError(error);
            return;
        }

        boolean stoppedAfterPreparation;
        synchronized (lock) {
            stoppedAfterPreparation = !startTaskActive
                    || closed
                    || stopRequested;
            if (stoppedAfterPreparation) {
                finishStartTaskLocked(null);
            }
        }
        if (stoppedAfterPreparation) {
            publish();
            return;
        }

        TextMessageClient client;
        try {
            client = Objects.requireNonNull(
                    networkClientFactory.create(prepared.endpointUri()),
                    "networkClientFactory returned null"
            );
        } catch (Throwable error) {
            finishFailedStart(error);
            rethrowError(error);
            return;
        }

        TextMessageWorkerRuntime runtime;
        try {
            runtime = new TextMessageWorkerRuntime(
                    client,
                    prepared.workerId(),
                    commandExecutor,
                    executionResources.handlerExecutor(),
                    this::runtimeTerminated
            );
        } catch (Throwable error) {
            closeQuietly(client);
            finishFailedStart(error);
            rethrowError(error);
            return;
        }
        boolean installed;
        synchronized (lock) {
            installed = startTaskActive && !closed && !stopRequested;
            if (installed) {
                activeRuntime = runtime;
                preparedWorker = prepared;
                startTaskActive = false;
            } else {
                finishStartTaskLocked(null);
            }
        }
        if (!installed) {
            closeQuietly(runtime);
            publish();
            return;
        }

        try {
            runtime.start();
        } catch (Throwable error) {
            runtimeTerminated(runtime, error);
            rethrowError(error);
            return;
        }
        publish();
    }

    private void finishFailedStart(Throwable error) {
        synchronized (lock) {
            if (!startTaskActive) {
                return;
            }
            finishStartTaskLocked(
                    stopRequested || closed
                            ? null
                            : "Worker start failed: "
                                    + safeFailureType(error)
            );
        }
        publish();
    }

    private void finishStartTaskLocked(String message) {
        startTaskActive = false;
        if (!closed && state == State.RUNNING && activeRuntime == null) {
            transitionStoppedLocked(message);
        }
    }

    private void runtimeTerminated(
            TextMessageWorkerRuntime runtime,
            Throwable failure
    ) {
        boolean current;
        synchronized (lock) {
            current = isCurrentRuntimeLocked(runtime);
            if (current) {
                activeRuntime = null;
                transitionStoppedLocked(
                        stopRequested
                                ? null
                                : failure == null
                                        ? "Endpoint terminated"
                                        : "Worker runtime failed: "
                                                + safeFailureType(failure)
                );
            }
        }
        if (current) {
            closeQuietly(runtime);
            publish();
        }
    }

    private boolean isCurrentRuntimeLocked(
            TextMessageWorkerRuntime runtime
    ) {
        return !closed
                && state == State.RUNNING
                && activeRuntime == runtime;
    }

    private void transitionStoppedLocked(String message) {
        state = State.STOPPED;
        stopRequested = false;
        preparedWorker = null;
        diagnosticMessage = message;
    }

    private void publish() {
        publish(listeners, snapshot());
    }

    private static void publish(
            Iterable<Listener> currentListeners,
            Snapshot snapshot
    ) {
        for (Listener listener : currentListeners) {
            notifyListener(listener, snapshot);
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

    private static String safeFailureType(Throwable error) {
        String name = error == null
                ? null
                : error.getClass().getSimpleName();
        return name == null || name.isEmpty() ? "Throwable" : name;
    }

    private static void rethrowError(Throwable error) {
        if (error instanceof Error) {
            throw (Error) error;
        }
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
