package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class DispatchConvergenceApplication {

    private final Object lifecycleLock = new Object();
    private final DispatchLaneCoordinator laneCoordinator;
    private final TaskWorkerAllocationPolicy allocation;
    private final TaskDispatchPolicy dispatch;
    private final WorkerServiceabilityDispatchPolicy serviceability;
    private Thread coordinator;
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
        this.laneCoordinator = new DispatchLaneCoordinator(
                Objects.requireNonNull(taskScores, "taskScores"),
                Objects.requireNonNull(taskCatalog, "taskCatalog"),
                Objects.requireNonNull(
                        initialization,
                        "initialization"
                )
        );
        this.allocation = Objects.requireNonNull(allocation, "allocation");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.serviceability = serviceability;
    }

    void start(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        Objects.requireNonNull(assignmentConfig, "assignmentConfig");
        Objects.requireNonNull(serviceabilityConfig, "serviceabilityConfig");
        synchronized (lifecycleLock) {
            if (coordinator != null || state != State.STOPPED) {
                throw new IllegalStateException(
                        "Dispatch Convergence application is already started"
                );
            }
            List<DispatchLaneDefinition> definitions = lanes(
                    assignmentConfig,
                    serviceabilityConfig
            );
            CountDownLatch signal = new CountDownLatch(1);
            ThreadFactory batchThreads = Thread.ofVirtual()
                    .name("dispatch-convergence-batch-", 0)
                    .factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(
                    batchThreads
            );
            Thread started = new Thread(
                    () -> runCoordinator(
                            signal,
                            executor,
                            TimeUnit.MILLISECONDS.toNanos(
                                    assignmentConfig
                                            .taskInitializationIntervalMillis()
                            ),
                            definitions
                    ),
                    "dispatch-convergence-coordinator"
            );
            started.setDaemon(false);
            stopSignal = signal;
            batchExecutor = executor;
            coordinator = started;
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
            current = coordinator;
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
                    "Dispatch Convergence coordinator did not stop within "
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
            if (coordinator == current) {
                coordinator = null;
                batchExecutor = null;
                stopSignal = null;
                state = State.STOPPED;
            }
        }
    }

    boolean isRunning() {
        synchronized (lifecycleLock) {
            refreshDeadCoordinator();
            return state == State.RUNNING
                    && coordinator != null
                    && coordinator.isAlive()
                    && batchExecutor != null
                    && !batchExecutor.isShutdown();
        }
    }

    String state() {
        synchronized (lifecycleLock) {
            refreshDeadCoordinator();
            return state.name();
        }
    }

    private List<DispatchLaneDefinition> lanes(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        List<DispatchLaneDefinition> definitions = new ArrayList<>();
        definitions.add(DispatchLaneDefinition.fromMillis(
                DispatchLaneId.WORKER_ALLOCATION,
                assignmentConfig.workerAllocationIntervalMillis(),
                batch -> allocation.allocateCandidateWorkers(
                        batch,
                        assignmentConfig.workerAllocation()
                )
        ));
        definitions.add(DispatchLaneDefinition.fromMillis(
                DispatchLaneId.TASK_DISPATCH,
                assignmentConfig.taskDispatchIntervalMillis(),
                batch -> dispatch.dispatchTasks(
                        batch,
                        assignmentConfig.taskDispatch()
                )
        ));
        if (serviceabilityConfig.enabled()) {
            if (serviceability == null) {
                throw new IllegalStateException(
                        "enabled serviceability requires its dispatch policy"
                );
            }
            definitions.add(DispatchLaneDefinition.fromMillis(
                    DispatchLaneId.WORKER_SERVICEABILITY,
                    serviceabilityConfig.lane().intervalMillis(),
                    batch -> serviceability.dispatchProbes(
                            batch,
                            serviceabilityConfig.lane().dispatch(),
                            serviceabilityConfig.hotEligibilityFloorMillis()
                    )
            ));
        }
        return List.copyOf(definitions);
    }

    private void runCoordinator(
            CountDownLatch signal,
            ExecutorService executor,
            long initializationIntervalNanos,
            List<DispatchLaneDefinition> definitions
    ) {
        try {
            laneCoordinator.run(
                    signal,
                    executor,
                    initializationIntervalNanos,
                    definitions
            );
        } finally {
            synchronized (lifecycleLock) {
                if (coordinator == Thread.currentThread()
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

    private void refreshDeadCoordinator() {
        if (state == State.RUNNING
                && coordinator != null
                && !coordinator.isAlive()) {
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
