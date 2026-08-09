package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Guards one Worker run from preparation through endpoint termination.
 */
public final class WorkerLoop implements WorkerLifecycle {

    @FunctionalInterface
    public interface NetworkClientFactory {

        TextMessageClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final WorkerPreparation preparation;
    private final WorkerCommandExecutor commandExecutor;
    private final NetworkClientFactory networkClientFactory;
    private final WorkerRetryPolicy retryPolicy;
    private final ScheduledExecutorService supervisor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread supervisorThread;

    private State state = State.STOPPED;
    private boolean closed;
    private boolean stopRequested;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerRuntime activeRuntime;
    private ScheduledFuture<?> preparationRetry;
    private String diagnosticMessage;

    public WorkerLoop(
            WorkerPreparation preparation,
            WorkerCommandExecutor commandExecutor,
            NetworkClientFactory networkClientFactory,
            WorkerRetryPolicy retryPolicy
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
        this.retryPolicy = Objects.requireNonNull(
                retryPolicy,
                "retryPolicy"
        );
        supervisor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "xa-worker-supervisor"
            );
            thread.setDaemon(true);
            supervisorThread = thread;
            return thread;
        });
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("WorkerLoop is closed");
            }
            if (state == State.RUNNING) {
                return;
            }
            state = State.RUNNING;
            stopRequested = false;
            preparedWorker = null;
            activeRuntime = null;
            diagnosticMessage = null;
            try {
                supervisor.execute(this::beginRun);
            } catch (RejectedExecutionException error) {
                state = State.STOPPED;
                diagnosticMessage = "Worker supervisor is unavailable";
                throw new IllegalStateException(
                        "Worker supervisor is unavailable",
                        error
                );
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (closed || state == State.STOPPED || stopRequested) {
                return;
            }
            stopRequested = true;
            executeSupervisor(this::stopOnSupervisor);
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    state,
                    connectionStateLocked(),
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
    public boolean isConnected() {
        synchronized (lock) {
            return state == State.RUNNING
                    && !stopRequested
                    && activeRuntime != null
                    && activeRuntime.isConnected();
        }
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("WorkerLoop is closed");
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
    public void close() {
        TextMessageWorkerRuntime runtime;
        ScheduledFuture<?> retry;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            stopRequested = true;
            runtime = activeRuntime;
            retry = preparationRetry;
            activeRuntime = null;
            preparationRetry = null;
            preparedWorker = null;
            state = State.STOPPED;
            diagnosticMessage = null;
        }
        cancelRetry(retry);
        closeQuietly(runtime);
        closeQuietly(preparation);
        publishFinalAndShutdown();
    }

    private void beginRun() {
        publish();
        if (stopWasRequested()) {
            finishRun(null);
            return;
        }
        attemptPreparation(1);
    }

    private void stopOnSupervisor() {
        TextMessageWorkerRuntime runtime;
        ScheduledFuture<?> retry;
        synchronized (lock) {
            if (closed || state == State.STOPPED) {
                return;
            }
            retry = preparationRetry;
            preparationRetry = null;
            runtime = activeRuntime;
        }
        cancelRetry(retry);
        if (runtime == null) {
            finishRun(null);
            return;
        }
        runtime.requestStop();
    }

    private void attemptPreparation(int attempt) {
        if (!canPrepare()) {
            finishStoppedRunIfRequested();
            return;
        }
        PreparedWorker prepared;
        try {
            prepared = preparation.prepare();
        } catch (Exception error) {
            if (stopWasRequested()) {
                finishRun(null);
            } else {
                handlePreparationFailure(attempt, error);
            }
            return;
        }
        if (!canPrepare()) {
            finishStoppedRunIfRequested();
            return;
        }
        installRuntime(prepared);
    }

    private void installRuntime(PreparedWorker prepared) {
        TextMessageClient client;
        try {
            client = networkClientFactory.create(prepared.endpointUri());
            if (client == null) {
                throw new IllegalStateException(
                        "networkClientFactory returned null"
                );
            }
        } catch (Exception error) {
            finishRun(stopWasRequested() ? null : safeMessage(error));
            return;
        }

        TextMessageWorkerRuntime runtime = new TextMessageWorkerRuntime(
                client,
                prepared.workerId(),
                commandExecutor,
                runtimeListener()
        );
        synchronized (lock) {
            if (!canPrepareLocked()) {
                closeQuietly(runtime);
                if (stopRequested && !closed) {
                    executeSupervisor(() -> finishRun(null));
                }
                return;
            }
            activeRuntime = runtime;
            preparedWorker = prepared;
        }
        try {
            runtime.start();
        } catch (RuntimeException error) {
            runtimeStartFailed(runtime, error);
            return;
        }

        boolean requestStop;
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(runtime)) {
                closeQuietly(runtime);
                return;
            }
            requestStop = stopRequested;
            if (!requestStop) {
                diagnosticMessage = null;
            }
        }
        publish();
        if (requestStop) {
            runtime.requestStop();
        }
    }

    private TextMessageWorkerRuntime.Listener runtimeListener() {
        return new TextMessageWorkerRuntime.Listener() {
            @Override
            public void onStateChanged(
                    TextMessageWorkerRuntime runtime,
                    Throwable failure
            ) {
                executeSupervisor(
                        () -> runtimeStateChanged(runtime, failure)
                );
            }

            @Override
            public void onExit(TextMessageWorkerRuntime runtime) {
                executeSupervisor(() -> runtimeExited(runtime));
            }
        };
    }

    private void runtimeStartFailed(
            TextMessageWorkerRuntime runtime,
            RuntimeException error
    ) {
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(runtime)) {
                closeQuietly(runtime);
                return;
            }
        }
        finishRun(stopWasRequested() ? null : safeMessage(error));
    }

    private void runtimeStateChanged(
            TextMessageWorkerRuntime runtime,
            Throwable failure
    ) {
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(runtime)) {
                return;
            }
            if (failure != null) {
                diagnosticMessage = "Worker runtime reported failure: "
                        + safeMessage(failure);
            } else if (runtime.isConnected()) {
                diagnosticMessage = null;
            }
        }
        publish();
    }

    private void runtimeExited(TextMessageWorkerRuntime runtime) {
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(runtime)) {
                return;
            }
        }
        finishRun(stopWasRequested() ? null : "Endpoint terminated");
    }

    private void handlePreparationFailure(int attempt, Exception error) {
        String message = "Worker preparation failed: " + safeMessage(error);
        if (!isRetryablePreparationFailure(error)
                || attempt >= retryPolicy.maxPrepareAttempts()) {
            finishRun(message);
            return;
        }
        synchronized (lock) {
            if (!canPrepareLocked()) {
                if (stopRequested && !closed) {
                    executeSupervisor(() -> finishRun(null));
                }
                return;
            }
            diagnosticMessage = message;
            try {
                preparationRetry = supervisor.schedule(
                        () -> {
                            synchronized (lock) {
                                preparationRetry = null;
                            }
                            attemptPreparation(attempt + 1);
                        },
                        retryPolicy.prepareRetryInterval().toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException ignored) {
                finishRun("Worker supervisor is unavailable");
                return;
            }
        }
        publish();
    }

    private void finishStoppedRunIfRequested() {
        if (stopWasRequested()) {
            finishRun(null);
        }
    }

    private void finishRun(String message) {
        TextMessageWorkerRuntime runtime;
        ScheduledFuture<?> retry;
        synchronized (lock) {
            if (closed || state == State.STOPPED) {
                return;
            }
            runtime = activeRuntime;
            retry = preparationRetry;
            activeRuntime = null;
            preparationRetry = null;
            preparedWorker = null;
            state = State.STOPPED;
            stopRequested = false;
            diagnosticMessage = message;
        }
        cancelRetry(retry);
        closeQuietly(runtime);
        publish();
    }

    private boolean canPrepare() {
        synchronized (lock) {
            return canPrepareLocked();
        }
    }

    private boolean canPrepareLocked() {
        return !closed
                && state == State.RUNNING
                && !stopRequested
                && activeRuntime == null;
    }

    private boolean stopWasRequested() {
        synchronized (lock) {
            return !closed
                    && state == State.RUNNING
                    && stopRequested;
        }
    }

    private boolean isCurrentRuntimeLocked(
            TextMessageWorkerRuntime runtime
    ) {
        return !closed
                && state == State.RUNNING
                && activeRuntime == runtime;
    }

    private ConnectionState connectionStateLocked() {
        if (activeRuntime == null
                || stopRequested
                || activeRuntime.isExiting()) {
            return ConnectionState.DISCONNECTED;
        }
        return activeRuntime.isConnected()
                ? ConnectionState.CONNECTED
                : ConnectionState.CONNECTING;
    }

    private void executeSupervisor(Runnable runnable) {
        try {
            supervisor.execute(runnable);
        } catch (RejectedExecutionException ignored) {
            // Terminal close owns cancellation of queued lifecycle work.
        }
    }

    private void publish() {
        Snapshot current = snapshot();
        if (Thread.currentThread() == supervisorThread) {
            publishNow(current);
            return;
        }
        try {
            supervisor.execute(() -> publishNow(current));
        } catch (RejectedExecutionException ignored) {
            // Terminal close may reject a stale notification.
        }
    }

    private void publishTo(Listener listener) {
        try {
            supervisor.execute(() -> {
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
        if (Thread.currentThread() == supervisorThread) {
            publishNow(current);
            listeners.clear();
            supervisor.shutdownNow();
            return;
        }
        try {
            supervisor.execute(() -> {
                publishNow(current);
                listeners.clear();
            });
        } catch (RejectedExecutionException ignored) {
            listeners.clear();
        } finally {
            supervisor.shutdown();
        }
    }

    private static void cancelRetry(ScheduledFuture<?> retry) {
        if (retry != null) {
            retry.cancel(false);
        }
    }

    private static boolean isRetryablePreparationFailure(Exception error) {
        if (error instanceof IOException) {
            return true;
        }
        if (error instanceof WorkerException) {
            return ((WorkerException) error).errorCode()
                    == WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE;
        }
        return false;
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
