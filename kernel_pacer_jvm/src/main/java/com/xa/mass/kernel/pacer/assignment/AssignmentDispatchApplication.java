package com.xa.mass.kernel.pacer;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class AssignmentDispatchApplication {

    private static final System.Logger LOGGER = System.getLogger(
            AssignmentDispatchApplication.class.getName()
    );
    private static final long START_ROLLBACK_TIMEOUT_MILLIS = 5_000;
    private final Object lifecycleLock = new Object();
    private final TaskWorkerAllocationPacer allocation;
    private final TaskRunningActivationPacer activation;
    private final TaskDispatchPacer dispatch;
    private Thread allocationThread;
    private Thread activationThread;
    private Thread dispatchThread;
    private CountDownLatch stopSignal;
    private State state = State.STOPPED;

    public AssignmentDispatchApplication(
            TaskWorkerAllocationPacer allocation,
            TaskRunningActivationPacer activation,
            TaskDispatchPacer dispatch
    ) {
        this.allocation = java.util.Objects.requireNonNull(
                allocation,
                "allocation"
        );
        this.activation = java.util.Objects.requireNonNull(
                activation,
                "activation"
        );
        this.dispatch = java.util.Objects.requireNonNull(dispatch, "dispatch");
    }

    public void start(AssignmentDispatchApplicationConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        synchronized (lifecycleLock) {
            if (state != State.STOPPED
                    || allocationThread != null
                    || activationThread != null
                    || dispatchThread != null) {
                throw new IllegalStateException(
                        "Assignment Dispatch is already started"
                );
            }
            CountDownLatch signal = new CountDownLatch(1);
            allocationThread = loopThread(
                    "assignment-dispatch-worker-allocation",
                    signal,
                    config.workerAllocationIntervalMillis(),
                    () -> allocation.allocateCandidateWorkers(
                            config.workerAllocation()
                    )
            );
            activationThread = loopThread(
                    "assignment-dispatch-running-activation",
                    signal,
                    config.runningActivationIntervalMillis(),
                    () -> activation.activateRunningVisibleTasks(
                            config.runningActivation()
                    )
            );
            dispatchThread = loopThread(
                    "assignment-dispatch-task-dispatch",
                    signal,
                    config.taskDispatchIntervalMillis(),
                    () -> dispatch.dispatchTasks(config.taskDispatch())
            );
            stopSignal = signal;
            state = State.STARTING;
            List<Thread> started = new ArrayList<>(3);
            try {
                allocationThread.start();
                started.add(allocationThread);
                activationThread.start();
                started.add(activationThread);
                dispatchThread.start();
                started.add(dispatchThread);
                state = State.RUNNING;
            } catch (RuntimeException failure) {
                signal.countDown();
                started.forEach(Thread::interrupt);
                joinStarted(started, START_ROLLBACK_TIMEOUT_MILLIS, failure);
                allocationThread = null;
                activationThread = null;
                dispatchThread = null;
                stopSignal = null;
                state = State.FAILED;
                throw failure;
            }
        }
    }

    public void stop(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive"
            );
        }
        List<Thread> threads;
        CountDownLatch signal;
        synchronized (lifecycleLock) {
            if (stopSignal == null) {
                return;
            }
            state = State.STOPPING;
            signal = stopSignal;
            threads = List.of(
                    allocationThread,
                    activationThread,
                    dispatchThread
            );
            signal.countDown();
        }
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Thread thread : threads) {
            long remaining = Math.max(1, deadline - System.nanoTime());
            try {
                thread.join(Math.max(
                        1,
                        TimeUnit.NANOSECONDS.toMillis(remaining)
                ));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Assignment Dispatch shutdown was interrupted",
                        error
                );
            }
        }
        if (threads.stream().anyMatch(Thread::isAlive)) {
            throw new IllegalStateException(
                    "Assignment Dispatch did not stop within its budget"
            );
        }
        synchronized (lifecycleLock) {
            allocationThread = null;
            activationThread = null;
            dispatchThread = null;
            stopSignal = null;
            state = State.STOPPED;
        }
    }

    public boolean isRunning() {
        synchronized (lifecycleLock) {
            refreshDeadThread();
            return state == State.RUNNING;
        }
    }

    public String state() {
        synchronized (lifecycleLock) {
            refreshDeadThread();
            return state.name();
        }
    }

    private Thread loopThread(
            String name,
            CountDownLatch signal,
            long intervalMillis,
            Runnable round
    ) {
        Thread thread = new Thread(
                () -> runLoop(name, signal, intervalMillis, round),
                name
        );
        thread.setDaemon(false);
        return thread;
    }

    private void runLoop(
            String operation,
            CountDownLatch signal,
            long intervalMillis,
            Runnable round
    ) {
        try {
            while (signal.getCount() > 0) {
                try {
                    round.run();
                } catch (RuntimeException error) {
                    LOGGER.log(
                            System.Logger.Level.ERROR,
                            "operation=" + operation + ".round failed",
                            error
                    );
                }
                if (signal.await(intervalMillis, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lifecycleLock) {
                if (state == State.RUNNING) {
                    state = State.FAILED;
                    signal.countDown();
                }
            }
        }
    }

    private void refreshDeadThread() {
        if (state == State.RUNNING
                && (allocationThread == null || !allocationThread.isAlive()
                || activationThread == null || !activationThread.isAlive()
                || dispatchThread == null || !dispatchThread.isAlive())) {
            state = State.FAILED;
            if (stopSignal != null) {
                stopSignal.countDown();
            }
        }
    }

    private static void joinStarted(
            List<Thread> threads,
            long timeoutMillis,
            RuntimeException startFailure
    ) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Thread thread : threads) {
            try {
                thread.join(Math.max(
                        1,
                        TimeUnit.NANOSECONDS.toMillis(Math.max(
                                1,
                                deadline - System.nanoTime()
                        ))
                ));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                startFailure.addSuppressed(error);
                return;
            }
        }
        threads.stream().filter(Thread::isAlive).forEach(thread ->
                startFailure.addSuppressed(new IllegalStateException(
                        "Assignment Dispatch start rollback left thread "
                                + thread.getName() + " alive"
                ))
        );
    }

    private enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }
}
