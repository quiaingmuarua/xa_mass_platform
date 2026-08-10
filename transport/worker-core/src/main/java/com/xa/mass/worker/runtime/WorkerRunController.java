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

    private enum Phase {
        STOPPED,
        STARTING,
        ACTIVE,
        STOPPING,
        CLOSED
    }

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

    private Phase phase = Phase.STOPPED;
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
            if (phase == Phase.CLOSED) {
                throw new IllegalStateException(
                        "WorkerRunController is closed"
                );
            }
            if (phase != Phase.STOPPED) {
                return;
            }
            phase = Phase.STARTING;
            preparedWorker = null;
            activeRuntime = null;
            diagnosticMessage = null;
            try {
                executionResources.controlExecutor().execute(
                        this::runStartTask
                );
            } catch (Throwable error) {
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
        synchronized (lock) {
            if (phase == Phase.STOPPED
                    || phase == Phase.STOPPING
                    || phase == Phase.CLOSED) {
                return;
            }
            phase = Phase.STOPPING;
            runtime = activeRuntime;
        }
        if (runtime != null) {
            runtime.requestStop();
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    lifecycleStateLocked(),
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
            if (phase == Phase.CLOSED) {
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
            if (phase == Phase.CLOSED) {
                return;
            }
            phase = Phase.CLOSED;
            runtime = activeRuntime;
            activeRuntime = null;
            preparedWorker = null;
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
        TextMessageClient client = null;
        TextMessageWorkerRuntime runtime = null;
        boolean installed = false;
        try {
            if (abortStartIfRequested()) {
                return;
            }

            PreparedWorker prepared = Objects.requireNonNull(
                    preparation.prepare(),
                    "preparation returned null"
            );
            if (abortStartIfRequested()) {
                return;
            }

            client = Objects.requireNonNull(
                    networkClientFactory.create(prepared.endpointUri()),
                    "networkClientFactory returned null"
            );
            runtime = new TextMessageWorkerRuntime(
                    client,
                    prepared.workerId(),
                    commandExecutor,
                    executionResources.handlerExecutor(),
                    this::runtimeTerminated
            );
            client = null;

            installed = installRuntime(prepared, runtime);
            if (!installed) {
                return;
            }

            runtime.start();
        } catch (Throwable error) {
            if (installed) {
                runtimeTerminated(runtime, error);
            } else {
                failStart(error);
            }
            rethrowError(error);
            return;
        } finally {
            closeQuietly(client);
            if (!installed) {
                closeQuietly(runtime);
            }
        }

        publish();
    }

    private boolean installRuntime(
            PreparedWorker prepared,
            TextMessageWorkerRuntime runtime
    ) {
        boolean stopped = false;
        synchronized (lock) {
            if (phase == Phase.STARTING) {
                activeRuntime = runtime;
                preparedWorker = prepared;
                phase = Phase.ACTIVE;
                return true;
            } else if (phase == Phase.STOPPING) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
        return false;
    }

    private void failStart(Throwable error) {
        boolean stopped = false;
        synchronized (lock) {
            if (phase == Phase.STARTING) {
                transitionStoppedLocked(
                        "Worker start failed: "
                                + safeFailureType(error)
                );
                stopped = true;
            } else if (phase == Phase.STOPPING) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
    }

    private boolean abortStartIfRequested() {
        boolean stopped = false;
        synchronized (lock) {
            if (phase == Phase.STARTING) {
                return false;
            }
            if (phase == Phase.STOPPING) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
        return true;
    }

    private void runtimeTerminated(
            TextMessageWorkerRuntime runtime,
            Throwable failure
    ) {
        boolean current;
        synchronized (lock) {
            current = activeRuntime == runtime
                    && (phase == Phase.ACTIVE
                    || phase == Phase.STOPPING);
            if (current) {
                boolean requestedStop = phase == Phase.STOPPING;
                activeRuntime = null;
                transitionStoppedLocked(
                        requestedStop
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

    private void transitionStoppedLocked(String message) {
        phase = Phase.STOPPED;
        preparedWorker = null;
        diagnosticMessage = message;
    }

    private State lifecycleStateLocked() {
        return phase == Phase.STOPPED || phase == Phase.CLOSED
                ? State.STOPPED
                : State.RUNNING;
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
