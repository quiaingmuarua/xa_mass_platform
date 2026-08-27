package com.xa.mass.kernel.pacer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class DispatchConvergenceApplication {

    private static final System.Logger LOGGER = System.getLogger(
            DispatchConvergenceApplication.class.getName()
    );

    private final Object lifecycleLock = new Object();
    private final TaskSchedulingBatchSource source;
    private final TaskRunningActivationPolicy activation;
    private final TaskWorkerAllocationPolicy allocation;
    private final TaskDispatchPolicy dispatch;
    private final WorkerServiceabilityDispatchPolicy serviceability;
    private Thread coordinator;
    private ExecutorService batchExecutor;
    private CountDownLatch stopSignal;
    private State state = State.STOPPED;

    DispatchConvergenceApplication(
            TaskSchedulingBatchSource source,
            TaskRunningActivationPolicy activation,
            TaskWorkerAllocationPolicy allocation,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.allocation = Objects.requireNonNull(allocation, "allocation");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.serviceability = serviceability;
    }

    void start(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityAssemblyConfig serviceabilityConfig
    ) {
        Objects.requireNonNull(assignmentConfig, "assignmentConfig");
        Objects.requireNonNull(serviceabilityConfig, "serviceabilityConfig");
        synchronized (lifecycleLock) {
            if (coordinator != null || state != State.STOPPED) {
                throw new IllegalStateException(
                        "Dispatch Convergence application is already started"
                );
            }
            CountDownLatch signal = new CountDownLatch(1);
            BlockingQueue<LaneCompletion> completions =
                    new LinkedBlockingQueue<>();
            ThreadFactory batchThreads = Thread.ofVirtual()
                    .name("dispatch-convergence-batch-", 0)
                    .factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(
                    batchThreads
            );
            List<LaneDefinition> lanes = lanes(
                    assignmentConfig,
                    serviceabilityConfig
            );
            Thread started = new Thread(
                    () -> runLoop(signal, completions, executor, lanes),
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

    private List<LaneDefinition> lanes(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityAssemblyConfig serviceabilityConfig
    ) {
        List<LaneDefinition> definitions = new ArrayList<>();
        definitions.add(LaneDefinition.fromMillis(
                DispatchLaneId.RUNNING_ACTIVATION,
                assignmentConfig.runningActivationIntervalMillis(),
                batch -> activation.activateRunningVisibleTasks(
                        batch,
                        assignmentConfig.runningActivation()
                )
        ));
        definitions.add(LaneDefinition.fromMillis(
                DispatchLaneId.WORKER_ALLOCATION,
                assignmentConfig.workerAllocationIntervalMillis(),
                batch -> allocation.allocateCandidateWorkers(
                        batch,
                        assignmentConfig.workerAllocation()
                )
        ));
        definitions.add(LaneDefinition.fromMillis(
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
            definitions.add(LaneDefinition.fromMillis(
                    DispatchLaneId.WORKER_SERVICEABILITY,
                    serviceabilityConfig.dispatch().intervalMillis(),
                    batch -> serviceability.dispatchProbes(
                            batch,
                            serviceabilityConfig.dispatch().dispatch(),
                            serviceabilityConfig.hotEligibilityFloorMillis()
                    )
            ));
        }
        return List.copyOf(definitions);
    }

    private void runLoop(
            CountDownLatch signal,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor,
            List<LaneDefinition> lanes
    ) {
        Map<DispatchLaneId, LaneRuntime> runtimes = new EnumMap<>(
                DispatchLaneId.class
        );
        lanes.forEach(lane -> runtimes.put(
                lane.id(),
                new LaneRuntime(lane)
        ));
        boolean stopping = false;
        try {
            while (signal.getCount() > 0) {
                drainCompletions(runtimes, completions);
                dispatchRunningBatch(
                        runtimes,
                        completions,
                        executor,
                        signal
                );
                dispatchAdmissionBatch(
                        runtimes,
                        completions,
                        executor,
                        signal
                );
                if (signal.getCount() == 0) {
                    stopping = true;
                    break;
                }
                LaneCompletion completion = waitForWork(
                        runtimes,
                        completions
                );
                if (completion != null) {
                    applyCompletion(runtimes, completion);
                }
            }
            stopping = true;
        } catch (InterruptedException interrupted) {
            if (signal.getCount() == 0) {
                stopping = true;
            } else {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Dispatch Convergence coordinator was interrupted",
                        interrupted
                );
            }
        } finally {
            if (stopping) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
            synchronized (lifecycleLock) {
                if (coordinator == Thread.currentThread()
                        && state != State.STOPPING) {
                    state = State.FAILED;
                }
            }
        }
    }

    private void dispatchRunningBatch(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor,
            CountDownLatch signal
    ) {
        long now = System.nanoTime();
        List<LaneRuntime> eligible = runtimes.values().stream()
                .filter(runtime -> runtime.lane.id()
                        != DispatchLaneId.RUNNING_ACTIVATION)
                .filter(runtime -> runtime.idleAndEligible(now))
                .toList();
        if (eligible.isEmpty() || signal.getCount() == 0) {
            return;
        }
        List<DueTaskObservation> batch;
        try {
            batch = source.acquireRunningTasks(
                    AssignmentDispatchConfig.TASK_BATCH_LIMIT
            );
        } catch (RuntimeException failure) {
            eligible.forEach(this::deferLane);
            logFailure("runningSource", null, 0, failure);
            return;
        }
        if (batch.isEmpty()) {
            eligible.forEach(this::deferLane);
            return;
        }
        eligible.forEach(runtime -> submit(
                runtime,
                batch,
                completions,
                executor
        ));
    }

    private void dispatchAdmissionBatch(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor,
            CountDownLatch signal
    ) {
        LaneRuntime runtime = runtimes.get(
                DispatchLaneId.RUNNING_ACTIVATION
        );
        if (runtime == null
                || !runtime.idleAndEligible(System.nanoTime())
                || signal.getCount() == 0) {
            return;
        }
        List<DueTaskObservation> batch;
        try {
            batch = source.acquireAdmissionTasks(
                    AssignmentDispatchConfig.TASK_BATCH_LIMIT
            );
        } catch (RuntimeException failure) {
            deferLane(runtime);
            logFailure("admissionSource", runtime.lane.id(), 0, failure);
            return;
        }
        if (batch.isEmpty()) {
            deferLane(runtime);
            return;
        }
        submit(runtime, batch, completions, executor);
    }

    private void submit(
            LaneRuntime runtime,
            List<DueTaskObservation> batch,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor
    ) {
        runtime.inflight = true;
        try {
            executor.submit(() -> executeBatch(
                    runtime.lane,
                    batch,
                    completions
            ));
        } catch (RejectedExecutionException failure) {
            runtime.inflight = false;
            throw new IllegalStateException(
                    "Dispatch Convergence executor rejected lane="
                            + runtime.lane.id(),
                    failure
            );
        }
    }

    private static void executeBatch(
            LaneDefinition lane,
            List<DueTaskObservation> batch,
            BlockingQueue<LaneCompletion> completions
    ) {
        Throwable failure = null;
        try {
            lane.policy().handle(batch);
        } catch (RuntimeException runtimeFailure) {
            failure = runtimeFailure;
        } catch (Error fatalFailure) {
            failure = fatalFailure;
            throw fatalFailure;
        } finally {
            completions.offer(new LaneCompletion(
                    lane.id(),
                    batch.size(),
                    failure
            ));
        }
    }

    private void drainCompletions(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions
    ) {
        LaneCompletion completion;
        while ((completion = completions.poll()) != null) {
            applyCompletion(runtimes, completion);
        }
    }

    private void applyCompletion(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            LaneCompletion completion
    ) {
        LaneRuntime runtime = runtimes.get(completion.id());
        if (runtime == null || !runtime.inflight) {
            throw new IllegalStateException(
                    "Unexpected Dispatch lane completion: "
                            + completion.id()
            );
        }
        runtime.inflight = false;
        deferLane(runtime);
        if (completion.failure() == null) {
            return;
        }
        if (completion.failure() instanceof Error fatal) {
            throw fatal;
        }
        logFailure(
                "policy",
                runtime.lane.id(),
                completion.batchSize(),
                (RuntimeException) completion.failure()
        );
    }

    private LaneCompletion waitForWork(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions
    ) throws InterruptedException {
        long now = System.nanoTime();
        long waitNanos = Long.MAX_VALUE;
        boolean inflight = false;
        for (LaneRuntime runtime : runtimes.values()) {
            if (runtime.inflight) {
                inflight = true;
                continue;
            }
            waitNanos = Math.min(
                    waitNanos,
                    Math.max(0, runtime.nextEligibleNanos - now)
            );
        }
        if (waitNanos == 0) {
            return null;
        }
        if (waitNanos == Long.MAX_VALUE) {
            return inflight ? completions.take() : null;
        }
        return completions.poll(waitNanos, TimeUnit.NANOSECONDS);
    }

    private void deferLane(LaneRuntime runtime) {
        runtime.nextEligibleNanos = Math.addExact(
                System.nanoTime(),
                runtime.lane.intervalNanos()
        );
    }

    private static void logFailure(
            String operation,
            DispatchLaneId lane,
            int batchSize,
            RuntimeException failure
    ) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "operation=dispatchConvergence.{0} lane={1} batchSize={2} "
                        + "failureType={3}",
                operation,
                lane,
                batchSize,
                failure.getClass().getName()
        );
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

    private enum DispatchLaneId {
        RUNNING_ACTIVATION,
        WORKER_ALLOCATION,
        TASK_DISPATCH,
        WORKER_SERVICEABILITY
    }

    @FunctionalInterface
    private interface DispatchBatchPolicy {
        void handle(List<DueTaskObservation> batch);
    }

    private record LaneDefinition(
            DispatchLaneId id,
            long intervalNanos,
            DispatchBatchPolicy policy
    ) {
        private LaneDefinition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(policy, "policy");
            if (intervalNanos < 1) {
                throw new IllegalArgumentException(
                        "Dispatch lane interval must be positive"
                );
            }
        }

        private static LaneDefinition fromMillis(
                DispatchLaneId id,
                long intervalMillis,
                DispatchBatchPolicy policy
        ) {
            return new LaneDefinition(
                    Objects.requireNonNull(id, "id"),
                    TimeUnit.MILLISECONDS.toNanos(requirePositive(
                            intervalMillis
                    )),
                    Objects.requireNonNull(policy, "policy")
            );
        }

        private static long requirePositive(long value) {
            if (value < 1) {
                throw new IllegalArgumentException(
                        "Dispatch lane interval must be positive"
                );
            }
            return value;
        }
    }

    private static final class LaneRuntime {
        private final LaneDefinition lane;
        private boolean inflight;
        private long nextEligibleNanos;

        private LaneRuntime(LaneDefinition lane) {
            this.lane = lane;
        }

        private boolean idleAndEligible(long nowNanos) {
            return !inflight && nowNanos >= nextEligibleNanos;
        }
    }

    private record LaneCompletion(
            DispatchLaneId id,
            int batchSize,
            Throwable failure
    ) {
    }
}
