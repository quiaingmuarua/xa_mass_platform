package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class DispatchLaneCoordinator {

    private static final System.Logger LOGGER = System.getLogger(
            DispatchLaneCoordinator.class.getName()
    );

    private final TaskScoreBandCore taskScores;
    private final TaskResourceCatalog taskCatalog;
    private final TaskInitializationCheck initialization;

    DispatchLaneCoordinator(
            TaskScoreBandCore taskScores,
            TaskResourceCatalog taskCatalog,
            TaskInitializationCheck initialization
    ) {
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.taskCatalog = Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
        this.initialization = Objects.requireNonNull(
                initialization,
                "initialization"
        );
    }

    void run(
            CountDownLatch stopSignal,
            ExecutorService executor,
            long initializationIntervalNanos,
            List<DispatchLaneDefinition> lanes
    ) {
        Objects.requireNonNull(stopSignal, "stopSignal");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(lanes, "lanes");
        if (initializationIntervalNanos < 1) {
            throw new IllegalArgumentException(
                    "Initialization lane interval must be positive"
            );
        }
        BlockingQueue<LaneCompletion> completions =
                new LinkedBlockingQueue<>();
        Map<DispatchLaneId, LaneRuntime> runtimes = new EnumMap<>(
                DispatchLaneId.class
        );
        runtimes.put(
                DispatchLaneId.TASK_INITIALIZATION,
                LaneRuntime.initialization(initializationIntervalNanos)
        );
        lanes.forEach(lane -> {
            if (lane.id() == DispatchLaneId.TASK_INITIALIZATION
                    || runtimes.put(
                            lane.id(),
                            LaneRuntime.normal(lane)
                    ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate or invalid Dispatch lane: " + lane.id()
                );
            }
        });
        boolean stopping = false;
        try {
            while (stopSignal.getCount() > 0) {
                drainCompletions(runtimes, completions);
                dispatchEligible(
                        runtimes,
                        completions,
                        executor,
                        stopSignal
                );
                if (stopSignal.getCount() == 0) {
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
            if (stopSignal.getCount() == 0) {
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
        }
    }

    private void dispatchEligible(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor,
            CountDownLatch stopSignal
    ) {
        long now = System.nanoTime();
        Set<DispatchLaneId> eligible = EnumSet.noneOf(DispatchLaneId.class);
        runtimes.forEach((laneId, runtime) -> {
            if (runtime.idleAndEligible(now)) {
                eligible.add(laneId);
            }
        });
        if (eligible.isEmpty() || stopSignal.getCount() == 0) {
            return;
        }

        Map<String, Long> observedScores;
        Map<String, Long> initialScores;
        List<DueTaskObservation> normalTasks;
        try {
            observedScores = Objects.requireNonNull(
                    taskScores.acquireSchedulingTasks(
                            AssignmentDispatchConfig.TASK_BATCH_LIMIT
                    ),
                    "Task score owner returned null scores"
            );
            initialScores = Objects.requireNonNull(
                    taskScores.filterInitialTaskScores(observedScores),
                    "Task score owner returned null INITIAL scores"
            );
            normalTasks = requiresNormalTasks(eligible)
                    ? normalTasks(observedScores, initialScores.keySet())
                    : List.of();
        } catch (RuntimeException failure) {
            eligible.forEach(laneId -> deferLane(runtimes.get(laneId)));
            logFailure("taskSource", null, 0, failure);
            return;
        }
        if (stopSignal.getCount() == 0) {
            return;
        }

        for (DispatchLaneId laneId : eligible) {
            LaneRuntime runtime = Objects.requireNonNull(
                    runtimes.get(laneId),
                    "eligible lane runtime"
            );
            if (laneId == DispatchLaneId.TASK_INITIALIZATION) {
                if (initialScores.isEmpty()) {
                    deferLane(runtime);
                } else {
                    submit(
                            runtime,
                            initialScores.size(),
                            () -> initialization.check(initialScores),
                            completions,
                            executor
                    );
                }
            } else if (normalTasks.isEmpty()) {
                deferLane(runtime);
            } else {
                submit(
                        runtime,
                        normalTasks.size(),
                        () -> Objects.requireNonNull(
                                runtime.policy,
                                "normal lane policy"
                        ).handle(normalTasks),
                        completions,
                        executor
                );
            }
        }
    }

    private List<DueTaskObservation> normalTasks(
            Map<String, Long> observedScores,
            Set<String> initialTaskIds
    ) {
        List<String> normalTaskIds = observedScores.keySet().stream()
                .filter(taskId -> !initialTaskIds.contains(taskId))
                .toList();
        if (normalTaskIds.isEmpty()) {
            return List.of();
        }
        Map<String, TaskDescriptor> descriptors = Objects.requireNonNull(
                taskCatalog.loadTaskAllocationDescriptors(normalTaskIds),
                "Task catalog returned null descriptors"
        );
        List<DueTaskObservation> tasks = new ArrayList<>();
        for (String taskId : normalTaskIds) {
            TaskDescriptor descriptor = descriptors.get(taskId);
            if (descriptor == null || !taskId.equals(descriptor.taskId())) {
                continue;
            }
            tasks.add(new DueTaskObservation(
                    taskId,
                    new TaskSchedulingReference(
                            taskId,
                            observedScores.get(taskId)
                    ),
                    descriptor
            ));
        }
        return List.copyOf(tasks);
    }

    private static boolean requiresNormalTasks(
            Set<DispatchLaneId> eligible
    ) {
        return eligible.stream().anyMatch(
                lane -> lane != DispatchLaneId.TASK_INITIALIZATION
        );
    }

    private static void submit(
            LaneRuntime runtime,
            int batchSize,
            Runnable batch,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor
    ) {
        runtime.inflight = true;
        try {
            executor.submit(() -> executeBatch(
                    runtime.id,
                    batchSize,
                    batch,
                    completions
            ));
        } catch (RejectedExecutionException failure) {
            runtime.inflight = false;
            throw new IllegalStateException(
                    "Dispatch Convergence executor rejected lane="
                            + runtime.id,
                    failure
            );
        }
    }

    private static void executeBatch(
            DispatchLaneId laneId,
            int batchSize,
            Runnable batch,
            BlockingQueue<LaneCompletion> completions
    ) {
        Throwable failure = null;
        try {
            batch.run();
        } catch (RuntimeException runtimeFailure) {
            failure = runtimeFailure;
        } catch (Error fatalFailure) {
            failure = fatalFailure;
            throw fatalFailure;
        } finally {
            completions.offer(new LaneCompletion(
                    laneId,
                    batchSize,
                    failure
            ));
        }
    }

    private static void drainCompletions(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions
    ) {
        LaneCompletion completion;
        while ((completion = completions.poll()) != null) {
            applyCompletion(runtimes, completion);
        }
    }

    private static void applyCompletion(
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
                runtime.id,
                completion.batchSize(),
                (RuntimeException) completion.failure()
        );
    }

    private static LaneCompletion waitForWork(
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

    private static void deferLane(LaneRuntime runtime) {
        runtime.nextEligibleNanos = Math.addExact(
                System.nanoTime(),
                runtime.intervalNanos
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

    private static final class LaneRuntime {
        private final DispatchLaneId id;
        private final long intervalNanos;
        private final DispatchBatchPolicy policy;
        private boolean inflight;
        private long nextEligibleNanos;

        private LaneRuntime(
                DispatchLaneId id,
                long intervalNanos,
                DispatchBatchPolicy policy
        ) {
            this.id = id;
            this.intervalNanos = intervalNanos;
            this.policy = policy;
        }

        private static LaneRuntime initialization(long intervalNanos) {
            return new LaneRuntime(
                    DispatchLaneId.TASK_INITIALIZATION,
                    intervalNanos,
                    null
            );
        }

        private static LaneRuntime normal(DispatchLaneDefinition lane) {
            return new LaneRuntime(
                    lane.id(),
                    lane.intervalNanos(),
                    lane.policy()
            );
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
