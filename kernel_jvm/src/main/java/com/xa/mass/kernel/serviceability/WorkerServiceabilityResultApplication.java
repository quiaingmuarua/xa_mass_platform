package com.xa.mass.kernel.serviceability;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WorkerServiceabilityResultApplication {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerServiceabilityResultApplication.class.getName()
    );

    private final Object lifecycleLock = new Object();
    private final WorkerServiceabilityResultPacer pacer;
    private Thread thread;
    private CountDownLatch stopSignal;
    private State state = State.STOPPED;

    public WorkerServiceabilityResultApplication(
            WorkerServiceabilityResultPacer pacer
    ) {
        this.pacer = java.util.Objects.requireNonNull(pacer, "pacer");
    }

    public void start(
            WorkerServiceabilityResultApplicationConfig config,
            long hotEligibilityFloorMillis
    ) {
        java.util.Objects.requireNonNull(config, "config");
        WorkerServiceabilityAssemblyConfig.requireFloor(
                hotEligibilityFloorMillis
        );
        synchronized (lifecycleLock) {
            if (thread != null || state != State.STOPPED) {
                throw new IllegalStateException(
                        "Worker Serviceability Result is already started"
                );
            }
            CountDownLatch signal = new CountDownLatch(1);
            Thread started = new Thread(
                    () -> runLoop(
                            config,
                            hotEligibilityFloorMillis,
                            signal
                    ),
                    "worker-serviceability-result"
            );
            started.setDaemon(false);
            stopSignal = signal;
            thread = started;
            state = State.RUNNING;
            started.start();
        }
    }

    public void stop(long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive"
            );
        }
        Thread current;
        CountDownLatch signal;
        synchronized (lifecycleLock) {
            current = thread;
            signal = stopSignal;
            if (current == null || signal == null) {
                return;
            }
            state = State.STOPPING;
            signal.countDown();
        }
        try {
            current.join(timeoutMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Worker Serviceability Result shutdown was interrupted",
                    error
            );
        }
        if (current.isAlive()) {
            throw new IllegalStateException(
                    "Worker Serviceability Result did not stop within its budget"
            );
        }
        synchronized (lifecycleLock) {
            if (thread == current) {
                thread = null;
                stopSignal = null;
                state = State.STOPPED;
            }
        }
    }

    public boolean isRunning() {
        synchronized (lifecycleLock) {
            return state == State.RUNNING
                    && thread != null
                    && thread.isAlive();
        }
    }

    public String state() {
        synchronized (lifecycleLock) {
            refreshDeadThread();
            return state.name();
        }
    }

    private void runLoop(
            WorkerServiceabilityResultApplicationConfig config,
            long hotEligibilityFloorMillis,
            CountDownLatch signal
    ) {
        try {
            while (signal.getCount() > 0) {
                try {
                    pacer.routeAdapterEvidence(
                            config.result(),
                            hotEligibilityFloorMillis
                    );
                } catch (RuntimeException error) {
                    LOGGER.log(
                            System.Logger.Level.ERROR,
                            "operation=workerServiceabilityResult"
                                    + ".routeAdapterEvidence failed",
                            error
                    );
                }
                if (signal.await(
                        config.intervalMillis(),
                        TimeUnit.MILLISECONDS
                )) {
                    return;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lifecycleLock) {
                if (thread == Thread.currentThread()
                        && state != State.STOPPING) {
                    state = State.FAILED;
                }
            }
        }
    }

    private void refreshDeadThread() {
        if (state == State.RUNNING
                && thread != null
                && !thread.isAlive()) {
            state = State.FAILED;
        }
    }

    private enum State {
        STOPPED,
        RUNNING,
        STOPPING,
        FAILED
    }
}
