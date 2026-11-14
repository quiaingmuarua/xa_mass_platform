package com.xa.mass.android.workerdemo;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Process-local user intent and cooldown checks; never keeps or wakes the App. */
final class AndroidWorkerHostControl implements AutoCloseable {

    static final long CHECK_INTERVAL_MILLIS = 60 * 60 * 1000L;
    static final long MAX_JITTER_MILLIS = 5 * 60 * 1000L;

    interface Scheduler {
        void execute(Runnable action);
        void schedule(Runnable action, long delayMillis);
        void cancel(Runnable action);
    }

    private final WorkerLifecycle worker;
    private final Scheduler scheduler;
    private final LongSupplier elapsedMillis;
    private final LongSupplier wallMillis;
    private final LongSupplier jitterMillis;
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final WorkerLifecycle.Listener workerListener;

    // Only the scheduler lane mutates intent and the pending callback.
    private boolean desiredRunning;
    private boolean cooldownRecovery;
    private boolean closed;
    private Runnable pendingCheck;
    private long nextCheckElapsedMillis;
    private volatile Status status = new Status(false, null);

    static AndroidWorkerHostControl create(WorkerLifecycle worker) {
        Handler handler = new Handler(Looper.getMainLooper());
        return new AndroidWorkerHostControl(worker, new Scheduler() {
            @Override public void execute(Runnable action) {
                if (Looper.myLooper() == handler.getLooper()) action.run();
                else if (!handler.post(action)) throw new IllegalStateException("App control lane is unavailable");
            }

            @Override public void schedule(Runnable action, long delayMillis) {
                if (!handler.postDelayed(action, delayMillis)) {
                    throw new IllegalStateException("App control lane is unavailable");
                }
            }

            @Override public void cancel(Runnable action) { handler.removeCallbacks(action); }
        }, SystemClock::elapsedRealtime, System::currentTimeMillis,
                () -> ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1));
    }

    AndroidWorkerHostControl(WorkerLifecycle worker, Scheduler scheduler,
            LongSupplier elapsedMillis, LongSupplier wallMillis, LongSupplier jitterMillis) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.elapsedMillis = Objects.requireNonNull(elapsedMillis, "elapsedMillis");
        this.wallMillis = Objects.requireNonNull(wallMillis, "wallMillis");
        this.jitterMillis = Objects.requireNonNull(jitterMillis, "jitterMillis");
        workerListener = ignored -> this.scheduler.execute(this::observeCurrent);
        worker.addListener(workerListener);
    }

    void start() {
        scheduler.execute(() -> {
            requireOpen();
            cancelCheck();
            desiredRunning = true;
            if (worker.snapshot().stopReason() == WorkerLifecycle.StopReason.COOLDOWN) {
                cooldownRecovery = true;
            }
            try { worker.start(); }
            finally { observeCurrent(); }
        });
    }

    void stop() {
        scheduler.execute(() -> {
            if (closed) return;
            desiredRunning = false;
            cooldownRecovery = false;
            cancelCheck();
            try { worker.stop(); }
            finally { observeCurrent(); }
        });
    }

    /** Returning to a visible Activity does not bypass the low-frequency interval. */
    void onForeground() {
        scheduler.execute(() -> {
            if (closed) return;
            if (pendingCheck != null && elapsedMillis.getAsLong() >= nextCheckElapsedMillis) {
                Runnable due = pendingCheck;
                scheduler.cancel(due);
                due.run();
            } else {
                observeCurrent();
            }
        });
    }

    WorkerLifecycle.Snapshot snapshot() { return worker.snapshot(); }
    Status status() { return status; }

    void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void removeListener(Runnable listener) { listeners.remove(listener); }

    @Override public void close() {
        scheduler.execute(() -> {
            if (closed) return;
            closed = true;
            desiredRunning = false;
            cooldownRecovery = false;
            cancelCheck();
            worker.removeListener(workerListener);
            publish();
            worker.close();
            listeners.clear();
        });
    }

    private void observeCurrent() {
        if (closed) return;
        WorkerLifecycle.Snapshot current = worker.snapshot();
        if (!desiredRunning) {
            cancelCheck();
        } else if (current.state() == WorkerLifecycle.State.RUNNING) {
            // PREPARING is RUNNING without an Endpoint. A successful new run
            // ends this recovery episode; ordinary network retries remain SDK-owned.
            cancelCheck();
            if (current.endpointUri() != null) cooldownRecovery = false;
        } else if (current.stopReason() == WorkerLifecycle.StopReason.COOLDOWN) {
            cooldownRecovery = true;
            scheduleCheck();
        } else if (current.stopReason() == WorkerLifecycle.StopReason.UNAVAILABLE && cooldownRecovery) {
            scheduleCheck();
        } else {
            desiredRunning = false;
            cooldownRecovery = false;
            cancelCheck();
        }
        publish();
    }

    private void scheduleCheck() {
        if (pendingCheck != null) return;
        long jitter = jitterMillis.getAsLong();
        if (jitter < 0 || jitter > MAX_JITTER_MILLIS) {
            throw new IllegalArgumentException("Cooldown jitter is outside its bound");
        }
        long delayMillis = CHECK_INTERVAL_MILLIS + jitter;
        nextCheckElapsedMillis = elapsedMillis.getAsLong() + delayMillis;
        pendingCheck = new Runnable() {
            @Override public void run() {
                if (closed || pendingCheck != this) return;
                pendingCheck = null;
                if (!desiredRunning || !cooldownRecovery) { publish(); return; }
                WorkerLifecycle.Snapshot current = worker.snapshot();
                if (current.state() != WorkerLifecycle.State.STOPPED
                        || (current.stopReason() != WorkerLifecycle.StopReason.COOLDOWN
                            && current.stopReason() != WorkerLifecycle.StopReason.UNAVAILABLE)) {
                    observeCurrent();
                    return;
                }
                try { worker.start(); }
                finally { observeCurrent(); }
            }
        };
        scheduler.schedule(pendingCheck, delayMillis);
    }

    private void cancelCheck() {
        if (pendingCheck != null) {
            scheduler.cancel(pendingCheck);
            pendingCheck = null;
        }
    }

    private void publish() {
        Long nextCheckAtMillis = pendingCheck == null ? null : wallMillis.getAsLong()
                + Math.max(0, nextCheckElapsedMillis - elapsedMillis.getAsLong());
        status = new Status(desiredRunning, nextCheckAtMillis);
        for (Runnable listener : listeners) {
            try { listener.run(); }
            catch (RuntimeException ignored) { /* Presentation cannot change run intent. */ }
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Android Worker Host is closed");
    }

    static final class Status {
        private final boolean desiredRunning;
        private final Long nextCheckAtMillis;

        Status(boolean desiredRunning, Long nextCheckAtMillis) {
            this.desiredRunning = desiredRunning;
            this.nextCheckAtMillis = nextCheckAtMillis;
        }

        boolean desiredRunning() { return desiredRunning; }
        Long nextCheckAtMillis() { return nextCheckAtMillis; }
    }
}
