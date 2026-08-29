package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class DispatchConvergenceApplication {

    private final Object lifecycleLock = new Object();
    private final DispatchMainScheduler mainScheduler;
    private Thread schedulerThread;
    private ExecutorService batchExecutor;
    private CountDownLatch stopSignal;
    private State state = State.STOPPED;

    DispatchConvergenceApplication(
            TaskScoreBandCore taskScores,
            TaskResourceCatalog taskCatalog,
            TaskInitializationCheck initialization,
            TaskWorkerAllocationPolicy allocation,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability
    ) {
        this.mainScheduler = new DispatchMainScheduler(
                Objects.requireNonNull(taskScores, "taskScores"),
                Objects.requireNonNull(taskCatalog, "taskCatalog"),
                Objects.requireNonNull(
                        initialization,
                        "initialization"
                ),
                Objects.requireNonNull(allocation, "allocation"),
                Objects.requireNonNull(dispatch, "dispatch"),
                serviceability
        );
    }

    void start(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        Objects.requireNonNull(assignmentConfig, "assignmentConfig");
        Objects.requireNonNull(serviceabilityConfig, "serviceabilityConfig");
        synchronized (lifecycleLock) {
            if (schedulerThread != null || state != State.STOPPED) {
                throw new IllegalStateException(
                        "Dispatch Convergence application is already started"
                );
            }
            CountDownLatch signal = new CountDownLatch(1);
            ThreadFactory batchThreads = Thread.ofVirtual()
                    .name("dispatch-convergence-batch-", 0)
                    .factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(
                    batchThreads
            );
            Thread started = new Thread(
                    () -> runMainScheduler(
                            signal,
                            executor,
                            assignmentConfig,
                            serviceabilityConfig
                    ),
                    "dispatch-main-scheduler"
            );
            started.setDaemon(false);
            stopSignal = signal;
            batchExecutor = executor;
            schedulerThread = started;
            state = State.RUNNING;
            started.start();
        }
    }

    void stop(long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive"
            );
        }
        Thread current;
        ExecutorService executor;
        CountDownLatch signal;
        synchronized (lifecycleLock) {
            current = schedulerThread;
            executor = batchExecutor;
            signal = stopSignal;
            if (current == null || executor == null || signal == null) {
                return;
            }
            state = State.STOPPING;
            signal.countDown();
            current.interrupt();
        }
        long deadline = System.nanoTime()
                + Duration.ofMillis(timeoutMillis).toNanos();
        join(current, remainingMillis(deadline));
        if (current.isAlive()) {
            executor.shutdownNow();
            failStoppedState();
            throw new IllegalStateException(
                    "Dispatch Main Scheduler did not stop within "
                            + "its budget"
            );
        }
        if (!awaitTermination(executor, remainingMillis(deadline))) {
            executor.shutdownNow();
            failStoppedState();
            throw new IllegalStateException(
                    "Dispatch Convergence batches did not stop within "
                            + "their budget"
            );
        }
        synchronized (lifecycleLock) {
            if (schedulerThread == current) {
                schedulerThread = null;
                batchExecutor = null;
                stopSignal = null;
                state = State.STOPPED;
            }
        }
    }

    boolean isRunning() {
        synchronized (lifecycleLock) {
            refreshDeadScheduler();
            return state == State.RUNNING
                    && schedulerThread != null
                    && schedulerThread.isAlive()
                    && batchExecutor != null
                    && !batchExecutor.isShutdown();
        }
    }

    String state() {
        synchronized (lifecycleLock) {
            refreshDeadScheduler();
            return state.name();
        }
    }

    private void runMainScheduler(
            CountDownLatch signal,
            ExecutorService executor,
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        try {
            mainScheduler.run(
                    signal,
                    executor,
                    assignmentConfig,
                    serviceabilityConfig
            );
        } finally {
            synchronized (lifecycleLock) {
                if (schedulerThread == Thread.currentThread()
                        && state != State.STOPPING) {
                    state = State.FAILED;
                }
            }
        }
    }

    private static void join(Thread thread, long timeoutMillis) {
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Dispatch Convergence shutdown was interrupted",
                    interrupted
            );
        }
    }

    private static boolean awaitTermination(
            ExecutorService executor,
            long timeoutMillis
    ) {
        try {
            return executor.awaitTermination(
                    timeoutMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Dispatch Convergence batch shutdown was interrupted",
                    interrupted
            );
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(
                1,
                Duration.ofNanos(Math.max(
                        1,
                        deadlineNanos - System.nanoTime()
                )).toMillis()
        );
    }

    private void failStoppedState() {
        synchronized (lifecycleLock) {
            state = State.FAILED;
        }
    }

    private void refreshDeadScheduler() {
        if (state == State.RUNNING
                && schedulerThread != null
                && !schedulerThread.isAlive()) {
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
