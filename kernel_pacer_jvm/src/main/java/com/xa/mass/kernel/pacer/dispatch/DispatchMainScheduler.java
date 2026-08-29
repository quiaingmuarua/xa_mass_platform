package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

final class DispatchMainScheduler {

    private static final System.Logger LOGGER = System.getLogger(
            DispatchMainScheduler.class.getName()
    );

    private final TaskScoreBandCore taskScores;
    private final TaskResourceCatalog taskCatalog;
    private final TaskInitializationCheck initialization;
    private final TaskWorkerAllocationPolicy allocation;
    private final TaskDispatchPolicy dispatch;
    private final WorkerServiceabilityDispatchPolicy serviceability;

    DispatchMainScheduler(
            TaskScoreBandCore taskScores,
            TaskResourceCatalog taskCatalog,
            TaskInitializationCheck initialization,
            TaskWorkerAllocationPolicy allocation,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability
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
        this.allocation = Objects.requireNonNull(allocation, "allocation");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.serviceability = serviceability;
    }

    void run(
            CountDownLatch stopSignal,
            ExecutorService executor,
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        Objects.requireNonNull(stopSignal, "stopSignal");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(assignmentConfig, "assignmentConfig");
        Objects.requireNonNull(serviceabilityConfig, "serviceabilityConfig");
        if (serviceabilityConfig.enabled() && serviceability == null) {
            throw new IllegalStateException(
                    "enabled serviceability requires its dispatch policy"
            );
        }

        BlockingQueue<ProducerCompletion> completions =
                new LinkedBlockingQueue<>();
        Map<DispatchProducerId, ProducerRuntime> runtimes = runtimes(
                assignmentConfig,
                serviceabilityConfig
        );
        boolean stopping = false;
        try {
            while (stopSignal.getCount() > 0) {
                drainCompletions(runtimes, completions);
                dispatchEligible(
                        runtimes,
                        completions,
                        executor,
                        stopSignal,
                        assignmentConfig,
                        serviceabilityConfig
                );
                if (stopSignal.getCount() == 0) {
                    stopping = true;
                    break;
                }
                ProducerCompletion completion = waitForWork(
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
                        "Dispatch Main Scheduler was interrupted",
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

    private static Map<DispatchProducerId, ProducerRuntime> runtimes(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        Map<DispatchProducerId, ProducerRuntime> result = new EnumMap<>(
                DispatchProducerId.class
        );
        result.put(
                DispatchProducerId.TASK_INITIALIZATION,
                ProducerRuntime.fromMillis(
                        DispatchProducerId.TASK_INITIALIZATION,
                        assignmentConfig.taskInitializationIntervalMillis()
                )
        );
        result.put(
                DispatchProducerId.WORKER_ALLOCATION,
                ProducerRuntime.fromMillis(
                        DispatchProducerId.WORKER_ALLOCATION,
                        assignmentConfig.workerAllocationIntervalMillis()
                )
        );
        result.put(
                DispatchProducerId.TASK_DISPATCH,
                ProducerRuntime.fromMillis(
                        DispatchProducerId.TASK_DISPATCH,
                        assignmentConfig.taskDispatchIntervalMillis()
                )
        );
        if (serviceabilityConfig.enabled()) {
            result.put(
                    DispatchProducerId.WORKER_SERVICEABILITY,
                    ProducerRuntime.fromMillis(
                            DispatchProducerId.WORKER_SERVICEABILITY,
                            serviceabilityConfig.intervalMillis()
                    )
            );
        }
        return result;
    }

    private void dispatchEligible(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            BlockingQueue<ProducerCompletion> completions,
            ExecutorService executor,
            CountDownLatch stopSignal,
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        Set<DispatchProducerId> eligible = eligibleProducers(runtimes);
        if (eligible.isEmpty() || stopSignal.getCount() == 0) {
            return;
        }

        Map<String, Long> observedScores;
        Map<String, Long> initialScores;
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
        } catch (RuntimeException failure) {
            deferProducers(runtimes, eligible);
            logFailure("taskSource", null, 0, failure);
            return;
        }
        if (stopSignal.getCount() == 0) {
            return;
        }

        scheduleInitialization(
                runtimes,
                eligible,
                initialScores,
                completions,
                executor
        );

        Set<DispatchProducerId> normalEligible = EnumSet.copyOf(eligible);
        normalEligible.remove(DispatchProducerId.TASK_INITIALIZATION);
        if (normalEligible.isEmpty() || stopSignal.getCount() == 0) {
            return;
        }

        List<DueTaskObservation> normalTasks;
        try {
            normalTasks = normalTasks(
                    observedScores,
                    initialScores.keySet()
            );
        } catch (RuntimeException failure) {
            deferProducers(runtimes, normalEligible);
            logFailure("taskProjection", null, 0, failure);
            return;
        }
        if (stopSignal.getCount() == 0) {
            return;
        }

        scheduleNormalProducers(
                runtimes,
                normalEligible,
                normalTasks,
                completions,
                executor,
                assignmentConfig,
                serviceabilityConfig
        );
    }

    private static Set<DispatchProducerId> eligibleProducers(
            Map<DispatchProducerId, ProducerRuntime> runtimes
    ) {
        long now = System.nanoTime();
        Set<DispatchProducerId> eligible = EnumSet.noneOf(
                DispatchProducerId.class
        );
        runtimes.forEach((producerId, runtime) -> {
            if (runtime.idleAndEligible(now)) {
                eligible.add(producerId);
            }
        });
        return eligible;
    }

    private void scheduleInitialization(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            Set<DispatchProducerId> eligible,
            Map<String, Long> initialScores,
            BlockingQueue<ProducerCompletion> completions,
            ExecutorService executor
    ) {
        if (!eligible.contains(DispatchProducerId.TASK_INITIALIZATION)) {
            return;
        }
        ProducerRuntime runtime = runtimes.get(
                DispatchProducerId.TASK_INITIALIZATION
        );
        if (initialScores.isEmpty()) {
            deferProducer(runtime);
            return;
        }
        Map<String, Long> immutableScores = Collections.unmodifiableMap(
                new LinkedHashMap<>(initialScores)
        );
        submit(
                runtime,
                immutableScores.size(),
                () -> initialization.check(immutableScores),
                completions,
                executor
        );
    }

    private void scheduleNormalProducers(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            Set<DispatchProducerId> eligible,
            List<DueTaskObservation> normalTasks,
            BlockingQueue<ProducerCompletion> completions,
            ExecutorService executor,
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        List<DueTaskObservation> allocationTasks = normalTasks.stream()
                .filter(task -> task.descriptor().workerAllocationMechanism()
                        == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE)
                .toList();
        LinkedHashSet<String> groupIds = new LinkedHashSet<>();
        normalTasks.forEach(task -> groupIds.add(
                task.descriptor().workerGroupId()
        ));
        List<String> workerGroupIds = List.copyOf(groupIds);

        if (eligible.contains(DispatchProducerId.WORKER_ALLOCATION)) {
            scheduleProducer(
                    runtimes.get(DispatchProducerId.WORKER_ALLOCATION),
                    allocationTasks,
                    () -> allocation.allocateCandidateWorkers(
                            allocationTasks,
                            assignmentConfig.workerAllocation()
                    ),
                    completions,
                    executor
            );
        }
        if (eligible.contains(DispatchProducerId.TASK_DISPATCH)) {
            scheduleProducer(
                    runtimes.get(DispatchProducerId.TASK_DISPATCH),
                    normalTasks,
                    () -> dispatch.dispatchTasks(
                            normalTasks,
                            assignmentConfig.taskDispatch()
                    ),
                    completions,
                    executor
            );
        }
        if (eligible.contains(DispatchProducerId.WORKER_SERVICEABILITY)) {
            scheduleProducer(
                    runtimes.get(DispatchProducerId.WORKER_SERVICEABILITY),
                    workerGroupIds,
                    () -> Objects.requireNonNull(
                            serviceability,
                            "serviceability"
                    ).dispatchProbes(
                            workerGroupIds,
                            serviceabilityConfig.dispatch(),
                            serviceabilityConfig.hotEligibilityFloorMillis()
                    ),
                    completions,
                    executor
            );
        }
    }

    private static void scheduleProducer(
            ProducerRuntime runtime,
            List<?> input,
            Runnable producer,
            BlockingQueue<ProducerCompletion> completions,
            ExecutorService executor
    ) {
        if (input.isEmpty()) {
            deferProducer(runtime);
            return;
        }
        submit(
                runtime,
                input.size(),
                producer,
                completions,
                executor
        );
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

    private static void submit(
            ProducerRuntime runtime,
            int batchSize,
            Runnable producer,
            BlockingQueue<ProducerCompletion> completions,
            ExecutorService executor
    ) {
        runtime.inflight = true;
        try {
            executor.submit(() -> executeProducer(
                    runtime.id,
                    batchSize,
                    producer,
                    completions
            ));
        } catch (RejectedExecutionException failure) {
            runtime.inflight = false;
            throw new IllegalStateException(
                    "Dispatch Convergence executor rejected producer="
                            + runtime.id,
                    failure
            );
        }
    }

    private static void executeProducer(
            DispatchProducerId producerId,
            int batchSize,
            Runnable producer,
            BlockingQueue<ProducerCompletion> completions
    ) {
        Throwable failure = null;
        try {
            producer.run();
        } catch (RuntimeException runtimeFailure) {
            failure = runtimeFailure;
        } catch (Error fatalFailure) {
            failure = fatalFailure;
            throw fatalFailure;
        } finally {
            completions.offer(new ProducerCompletion(
                    producerId,
                    batchSize,
                    failure
            ));
        }
    }

    private static void drainCompletions(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            BlockingQueue<ProducerCompletion> completions
    ) {
        ProducerCompletion completion;
        while ((completion = completions.poll()) != null) {
            applyCompletion(runtimes, completion);
        }
    }

    private static void applyCompletion(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            ProducerCompletion completion
    ) {
        ProducerRuntime runtime = runtimes.get(completion.id());
        if (runtime == null || !runtime.inflight) {
            throw new IllegalStateException(
                    "Unexpected Dispatch producer completion: "
                            + completion.id()
            );
        }
        runtime.inflight = false;
        deferProducer(runtime);
        if (completion.failure() == null) {
            return;
        }
        if (completion.failure() instanceof Error fatal) {
            throw fatal;
        }
        logFailure(
                "producer",
                runtime.id,
                completion.batchSize(),
                (RuntimeException) completion.failure()
        );
    }

    private static ProducerCompletion waitForWork(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            BlockingQueue<ProducerCompletion> completions
    ) throws InterruptedException {
        long now = System.nanoTime();
        long waitNanos = Long.MAX_VALUE;
        boolean inflight = false;
        for (ProducerRuntime runtime : runtimes.values()) {
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

    private static void deferProducers(
            Map<DispatchProducerId, ProducerRuntime> runtimes,
            Set<DispatchProducerId> producers
    ) {
        producers.forEach(producerId -> deferProducer(
                Objects.requireNonNull(
                        runtimes.get(producerId),
                        "producer runtime"
                )
        ));
    }

    private static void deferProducer(ProducerRuntime runtime) {
        runtime.nextEligibleNanos = Math.addExact(
                System.nanoTime(),
                runtime.intervalNanos
        );
    }

    private static void logFailure(
            String operation,
            DispatchProducerId producer,
            int batchSize,
            RuntimeException failure
    ) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "operation=dispatchMainScheduler.{0} producer={1} "
                        + "batchSize={2} failureType={3}",
                operation,
                producer,
                batchSize,
                failure.getClass().getName()
        );
    }

    private static final class ProducerRuntime {
        private final DispatchProducerId id;
        private final long intervalNanos;
        private boolean inflight;
        private long nextEligibleNanos;

        private ProducerRuntime(
                DispatchProducerId id,
                long intervalNanos
        ) {
            this.id = Objects.requireNonNull(id, "id");
            if (intervalNanos < 1) {
                throw new IllegalArgumentException(
                        "Dispatch producer interval must be positive"
                );
            }
            this.intervalNanos = intervalNanos;
        }

        private static ProducerRuntime fromMillis(
                DispatchProducerId id,
                long intervalMillis
        ) {
            if (intervalMillis < 1) {
                throw new IllegalArgumentException(
                        "Dispatch producer interval must be positive"
                );
            }
            return new ProducerRuntime(
                    id,
                    TimeUnit.MILLISECONDS.toNanos(intervalMillis)
            );
        }

        private boolean idleAndEligible(long nowNanos) {
            return !inflight && nowNanos >= nextEligibleNanos;
        }
    }

    private record ProducerCompletion(
            DispatchProducerId id,
            int batchSize,
            Throwable failure
    ) {
    }
}
