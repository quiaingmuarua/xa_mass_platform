package com.xa.mass.kernel.result;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ResultRoutingApplication {

    private static final System.Logger LOGGER = System.getLogger(
            ResultRoutingApplication.class.getName()
    );

    private final Object lifecycleLock = new Object();
    private final ResultRoutingPacer pacer;
    private Thread thread;
    private CountDownLatch stopSignal;
    private State state = State.STOPPED;

    public ResultRoutingApplication(ResultRoutingPacer pacer) {
        this.pacer = java.util.Objects.requireNonNull(pacer, "pacer");
    }

    public void start(ResultRoutingApplicationConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        synchronized (lifecycleLock) {
            if (thread != null || state != State.STOPPED) {
                throw new IllegalStateException(
                        "Result Routing application is already started"
                );
            }
            CountDownLatch signal = new CountDownLatch(1);
            Thread started = new Thread(
                    () -> runLoop(config, signal),
                    "result-routing"
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
                    "Result Routing shutdown was interrupted",
                    error
            );
        }
        if (current.isAlive()) {
            throw new IllegalStateException(
                    "Result Routing did not stop within its budget"
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
            ResultRoutingApplicationConfig config,
            CountDownLatch signal
    ) {
        try {
            while (signal.getCount() > 0) {
                try {
                    pacer.routeWorkerResults(config.routing());
                } catch (RuntimeException error) {
                    LOGGER.log(
                            System.Logger.Level.ERROR,
                            "operation=resultRouting.routeWorkerResults "
                                    + "failed",
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
