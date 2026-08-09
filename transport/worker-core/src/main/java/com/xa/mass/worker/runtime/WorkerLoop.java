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
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Guards one Worker run from preparation through endpoint termination.
 *
 * <p>The host owns every execution resource. WorkerLoop owns only local state,
 * cancellable task handles, and current-object callback filtering.
 */
public final class WorkerLoop implements WorkerLifecycle {

    @FunctionalInterface
    public interface NetworkClientFactory {

        TextMessageClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final Object notificationLock = new Object();
    private final WorkerPreparation preparation;
    private final WorkerCommandExecutor commandExecutor;
    private final NetworkClientFactory networkClientFactory;
    private final WorkerRetryPolicy retryPolicy;
    private final WorkerExecutionResources executionResources;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private State state = State.STOPPED;
    private boolean closed;
    private boolean stopRequested;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerRuntime activeRuntime;
    private PreparationAttempt activePreparation;
    private PreparationRetry preparationRetry;
    private String diagnosticMessage;

    private boolean notificationDraining;
    private boolean notificationPending;
    private boolean clearListenersAfterDrain;

    public WorkerLoop(
            WorkerPreparation preparation,
            WorkerCommandExecutor commandExecutor,
            NetworkClientFactory networkClientFactory,
            WorkerRetryPolicy retryPolicy,
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
        this.retryPolicy = Objects.requireNonNull(
                retryPolicy,
                "retryPolicy"
        );
        this.executionResources = Objects.requireNonNull(
                executionResources,
                "executionResources"
        );
    }

    @Override
    public void start() {
        IllegalStateException submissionFailure = null;
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
            activePreparation = null;
            preparationRetry = null;
            diagnosticMessage = null;

            PreparationAttempt attempt = new PreparationAttempt(1);
            activePreparation = attempt;
            try {
                executionResources.controlExecutor().execute(attempt.task);
            } catch (RejectedExecutionException error) {
                activePreparation = null;
                transitionStoppedLocked(
                        "Worker control executor is unavailable"
                );
                submissionFailure = new IllegalStateException(
                        "Worker control executor is unavailable",
                        error
                );
            }
        }
        publish();
        if (submissionFailure != null) {
            throw submissionFailure;
        }
    }

    @Override
    public void stop() {
        TextMessageWorkerRuntime runtime = null;
        FutureTask<Void> preparationTask = null;
        ScheduledFuture<?> retryFuture = null;
        synchronized (lock) {
            if (closed || state == State.STOPPED || stopRequested) {
                return;
            }
            stopRequested = true;

            PreparationRetry retry = preparationRetry;
            if (retry != null) {
                preparationRetry = null;
                retryFuture = retry.future;
            }

            runtime = activeRuntime;
            if (runtime == null) {
                PreparationAttempt attempt = activePreparation;
                if (attempt == null) {
                    transitionStoppedLocked(null);
                } else if (!attempt.started) {
                    activePreparation = null;
                    preparationTask = attempt.task;
                    transitionStoppedLocked(null);
                }
            }
        }
        cancel(retryFuture, false);
        cancel(preparationTask, false);
        publish();
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
                throw new IllegalStateException("WorkerLoop is closed");
            }
            listeners.add(listener);
        }
        publish();
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
        FutureTask<Void> preparationTask;
        ScheduledFuture<?> retryFuture;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            stopRequested = true;
            runtime = activeRuntime;
            activeRuntime = null;
            PreparationAttempt attempt = activePreparation;
            preparationTask = attempt == null ? null : attempt.task;
            activePreparation = null;
            PreparationRetry retry = preparationRetry;
            retryFuture = retry == null ? null : retry.future;
            preparationRetry = null;
            preparedWorker = null;
            state = State.STOPPED;
            diagnosticMessage = null;
        }
        cancel(retryFuture, true);
        cancel(preparationTask, true);
        closeQuietly(runtime);
        closeQuietly(preparation);
        publishFinal();
    }

    private void runPreparation(PreparationAttempt attempt) {
        synchronized (lock) {
            if (!isCurrentPreparationLocked(attempt)
                    || stopRequested) {
                finishAbortedPreparationLocked(attempt);
                return;
            }
            attempt.started = true;
        }

        PreparedWorker prepared;
        try {
            prepared = preparation.prepare();
        } catch (Exception error) {
            handlePreparationFailure(attempt, error);
            return;
        }

        boolean aborted;
        boolean stopped;
        synchronized (lock) {
            aborted = !isCurrentPreparationLocked(attempt) || stopRequested;
            stopped = aborted && finishAbortedPreparationLocked(attempt);
        }
        if (aborted) {
            if (stopped) {
                publish();
            }
            return;
        }
        installRuntime(attempt, prepared);
    }

    private void installRuntime(
            PreparationAttempt attempt,
            PreparedWorker prepared
    ) {
        TextMessageClient client;
        try {
            client = networkClientFactory.create(prepared.endpointUri());
            if (client == null) {
                throw new IllegalStateException(
                        "networkClientFactory returned null"
                );
            }
        } catch (Exception error) {
            finishPreparationWithoutRetry(attempt, safeMessage(error));
            return;
        }

        TextMessageWorkerRuntime runtime = new TextMessageWorkerRuntime(
                client,
                prepared.workerId(),
                commandExecutor,
                executionResources.handlerExecutor(),
                runtimeListener()
        );
        boolean installed;
        synchronized (lock) {
            installed = isCurrentPreparationLocked(attempt)
                    && !stopRequested;
            if (installed) {
                activePreparation = null;
                activeRuntime = runtime;
                preparedWorker = prepared;
            }
        }
        if (!installed) {
            closeQuietly(runtime);
            boolean stopped = false;
            synchronized (lock) {
                if (activePreparation == attempt) {
                    activePreparation = null;
                    if (!closed
                            && state == State.RUNNING
                            && stopRequested) {
                        transitionStoppedLocked(null);
                        stopped = true;
                    }
                }
            }
            if (stopped) {
                publish();
            }
            return;
        }

        try {
            runtime.start();
        } catch (RuntimeException error) {
            runtimeStartFailed(runtime, error);
            return;
        }
        runtimeStarted(runtime);
    }

    private void runtimeStarted(TextMessageWorkerRuntime runtime) {
        boolean current;
        boolean requestStop = false;
        synchronized (lock) {
            current = isCurrentRuntimeLocked(runtime);
            if (current) {
                requestStop = stopRequested;
                if (!requestStop) {
                    diagnosticMessage = null;
                }
            }
        }
        if (!current) {
            return;
        }
        publish();
        if (requestStop) {
            runtime.requestStop();
        }
    }

    private TextMessageWorkerRuntime.Listener runtimeListener() {
        return this::runtimeTerminated;
    }

    private void runtimeStartFailed(
            TextMessageWorkerRuntime runtime,
            RuntimeException error
    ) {
        runtimeTerminated(runtime, error);
    }

    private void runtimeTerminated(
            TextMessageWorkerRuntime runtime,
            Throwable failure
    ) {
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(runtime)) {
                return;
            }
        }

        closeQuietly(runtime);
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(runtime)) {
                return;
            }
            String message = stopRequested
                    ? null
                    : failure == null
                            ? "Endpoint terminated"
                            : "Worker runtime failed: "
                                    + safeFailureType(failure);
            activeRuntime = null;
            transitionStoppedLocked(message);
        }
        publish();
    }

    private void handlePreparationFailure(
            PreparationAttempt attempt,
            Exception error
    ) {
        String message = "Worker preparation failed: " + safeMessage(error);
        boolean publish = false;
        synchronized (lock) {
            if (activePreparation != attempt) {
                return;
            }
            activePreparation = null;
            if (closed || state == State.STOPPED) {
                return;
            }
            if (stopRequested) {
                transitionStoppedLocked(null);
                publish = true;
            } else if (!isRetryablePreparationFailure(error)
                    || attempt.number >= retryPolicy.maxPrepareAttempts()) {
                transitionStoppedLocked(message);
                publish = true;
            } else {
                diagnosticMessage = message;
                PreparationRetry retry = new PreparationRetry(
                        attempt.number + 1
                );
                preparationRetry = retry;
                try {
                    retry.future = executionResources.retryScheduler().schedule(
                            retry,
                            retryPolicy.prepareRetryInterval().toMillis(),
                            TimeUnit.MILLISECONDS
                    );
                } catch (RejectedExecutionException ignored) {
                    preparationRetry = null;
                    transitionStoppedLocked(
                            "Worker retry scheduler is unavailable"
                    );
                }
                publish = true;
            }
        }
        if (publish) {
            publish();
        }
    }

    private void runPreparationRetry(PreparationRetry retry) {
        boolean publish = false;
        synchronized (lock) {
            if (preparationRetry != retry
                    || closed
                    || state == State.STOPPED) {
                return;
            }
            preparationRetry = null;
            if (stopRequested) {
                transitionStoppedLocked(null);
                publish = true;
            } else {
                PreparationAttempt attempt = new PreparationAttempt(
                        retry.attemptNumber
                );
                activePreparation = attempt;
                try {
                    executionResources.controlExecutor().execute(attempt.task);
                } catch (RejectedExecutionException ignored) {
                    activePreparation = null;
                    transitionStoppedLocked(
                            "Worker control executor is unavailable"
                    );
                    publish = true;
                }
            }
        }
        if (publish) {
            publish();
        }
    }

    private void finishPreparationWithoutRetry(
            PreparationAttempt attempt,
            String message
    ) {
        boolean finish = false;
        synchronized (lock) {
            if (activePreparation != attempt) {
                return;
            }
            activePreparation = null;
            if (!closed && state == State.RUNNING) {
                transitionStoppedLocked(stopRequested ? null : message);
                finish = true;
            }
        }
        if (finish) {
            publish();
        }
    }

    private boolean finishAbortedPreparationLocked(
            PreparationAttempt attempt
    ) {
        if (activePreparation != attempt) {
            return false;
        }
        activePreparation = null;
        if (!closed && state == State.RUNNING && stopRequested) {
            transitionStoppedLocked(null);
            return true;
        }
        return false;
    }

    private boolean isCurrentPreparationLocked(
            PreparationAttempt attempt
    ) {
        return !closed
                && state == State.RUNNING
                && activePreparation == attempt;
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
        requestNotification(false);
    }

    private void publishFinal() {
        requestNotification(true);
    }

    private void requestNotification(boolean clearAfterDrain) {
        boolean drain = false;
        synchronized (notificationLock) {
            notificationPending = true;
            if (clearAfterDrain) {
                clearListenersAfterDrain = true;
            }
            if (!notificationDraining) {
                notificationDraining = true;
                drain = true;
            }
        }
        if (drain) {
            drainNotifications();
        }
    }

    private void drainNotifications() {
        while (true) {
            synchronized (notificationLock) {
                if (!notificationPending) {
                    notificationDraining = false;
                    if (clearListenersAfterDrain) {
                        listeners.clear();
                    }
                    return;
                }
                notificationPending = false;
            }
            Snapshot current = snapshot();
            for (Listener listener : listeners) {
                notifyListener(listener, current);
            }
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

    private static void cancel(
            ScheduledFuture<?> future,
            boolean interrupt
    ) {
        if (future != null) {
            future.cancel(interrupt);
        }
    }

    private static void cancel(
            FutureTask<?> future,
            boolean interrupt
    ) {
        if (future != null) {
            future.cancel(interrupt);
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

    private static String safeFailureType(Throwable error) {
        String name = error == null
                ? null
                : error.getClass().getSimpleName();
        return name == null || name.isEmpty() ? "RuntimeException" : name;
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

    private final class PreparationAttempt {

        private final int number;
        private final FutureTask<Void> task;
        private boolean started;

        private PreparationAttempt(int number) {
            this.number = number;
            task = new FutureTask<>(() -> {
                runPreparation(this);
                return null;
            });
        }
    }

    private final class PreparationRetry implements Runnable {

        private final int attemptNumber;
        private ScheduledFuture<?> future;

        private PreparationRetry(int attemptNumber) {
            this.attemptNumber = attemptNumber;
        }

        @Override
        public void run() {
            runPreparationRetry(this);
        }
    }
}
