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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class DispatchMainScheduler {

    private static final int TASK_BATCH_LIMIT = 100;
    private static final System.Logger LOGGER = System.getLogger(
            DispatchMainScheduler.class.getName()
    );

    private final TaskScoreBandCore taskScores;
    private final TaskResourceCatalog taskCatalog;
    private final TaskInitializationPolicy initialization;
    private final TaskWorkerAllocationPolicy allocation;
    private final TaskDispatchPolicy dispatch;
    private final WorkerServiceabilityDispatchPolicy serviceability;
    private final AssignmentDispatchConfig assignmentConfig;
    private final WorkerServiceabilityDispatchConfig serviceabilityConfig;

    DispatchMainScheduler(
            TaskScoreBandCore taskScores,
            TaskResourceCatalog taskCatalog,
            TaskInitializationPolicy initialization,
            TaskWorkerAllocationPolicy allocation,
            TaskDispatchPolicy dispatch,
            WorkerServiceabilityDispatchPolicy serviceability,
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchConfig serviceabilityConfig
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
        this.assignmentConfig = Objects.requireNonNull(
                assignmentConfig,
                "assignmentConfig"
        );
        if (serviceabilityConfig != null && serviceability == null) {
            throw new IllegalArgumentException(
                    "serviceability config requires its dispatch policy"
            );
        }
        if (serviceabilityConfig == null && serviceability != null) {
            throw new IllegalArgumentException(
                    "serviceability policy requires its config"
            );
        }
        this.serviceabilityConfig = serviceabilityConfig;
    }

    void run() {
        ThreadFactory batchThreads = Thread.ofVirtual()
                .name("dispatch-convergence-batch-", 0)
                .factory();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                batchThreads
        );
        try {
            new SchedulerRun(executor).execute();
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ObservedTask> loadNormalTasks(
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
        List<ObservedTask> tasks = new ArrayList<>();
        for (String taskId : normalTaskIds) {
            TaskDescriptor descriptor = descriptors.get(taskId);
            if (descriptor == null || !taskId.equals(descriptor.taskId())) {
                continue;
            }
            tasks.add(new ObservedTask(
                    descriptor,
                    observedScores.get(taskId)
            ));
        }
        return List.copyOf(tasks);
    }

    private final class SchedulerRun {

        private final ExecutorService executor;
        private final BlockingQueue<ProducerCompletion> completions =
                new LinkedBlockingQueue<>();
        private final Map<DispatchProducerId, ProducerRuntime> runtimes;

        private SchedulerRun(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.runtimes = createRuntimes(
                    assignmentConfig,
                    serviceabilityConfig
            );
        }

        private void execute() {
            try {
                while (isRunning()) {
                    drainCompletions();
                    dispatchEligible();
                    if (!isRunning()) {
                        break;
                    }
                    ProducerCompletion completion = waitForWork();
                    if (completion != null) {
                        applyCompletion(completion);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void dispatchEligible() {
            Set<DispatchProducerId> eligible = eligibleProducers();
            if (eligible.isEmpty()) {
                return;
            }

            Map<String, Long> observedScores;
            Map<String, Long> initialScores;
            try {
                observedScores = Objects.requireNonNull(
                        taskScores.acquireSchedulingTasks(
                                TASK_BATCH_LIMIT
                        ),
                        "Task score owner returned null scores"
                );
                initialScores = Objects.requireNonNull(
                        taskScores.filterInitialTaskScores(observedScores),
                        "Task score owner returned null INITIAL scores"
                );
            } catch (RuntimeException failure) {
                deferProducers(eligible);
                logFailure("taskSource", null, 0, failure);
                return;
            }
            scheduleInitialization(eligible, initialScores);

            Set<DispatchProducerId> normalEligible = EnumSet.copyOf(eligible);
            normalEligible.remove(DispatchProducerId.TASK_INITIALIZATION);
            if (normalEligible.isEmpty()) {
                return;
            }

            List<ObservedTask> normalTasks;
            try {
                normalTasks = loadNormalTasks(
                        observedScores,
                        initialScores.keySet()
                );
            } catch (RuntimeException failure) {
                deferProducers(normalEligible);
                logFailure("taskProjection", null, 0, failure);
                return;
            }
            scheduleNormalProducers(normalEligible, normalTasks);
        }

        private void scheduleInitialization(
                Set<DispatchProducerId> eligible,
                Map<String, Long> initialScores
        ) {
            if (!eligible.contains(DispatchProducerId.TASK_INITIALIZATION)) {
                return;
            }
            Map<String, Long> immutableScores =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(initialScores)
                    );
            startProducer(
                    DispatchProducerId.TASK_INITIALIZATION,
                    immutableScores.size(),
                    () -> initialization.initialize(immutableScores)
            );
        }

        private void scheduleNormalProducers(
                Set<DispatchProducerId> eligible,
                List<ObservedTask> normalTasks
        ) {
            List<ObservedTask> allocationTasks = normalTasks.stream()
                    .filter(task -> task.descriptor()
                            .workerAllocationMechanism()
                            == WorkerAllocationMechanism
                            .PRECOMPUTED_TASK_RULE)
                    .toList();
            LinkedHashSet<String> groupIds = new LinkedHashSet<>();
            normalTasks.forEach(task -> groupIds.add(
                    task.descriptor().workerGroupId()
            ));
            List<String> workerGroupIds = List.copyOf(groupIds);

            if (eligible.contains(DispatchProducerId.WORKER_ALLOCATION)) {
                startProducer(
                        DispatchProducerId.WORKER_ALLOCATION,
                        allocationTasks.size(),
                        () -> allocation.allocateCandidateWorkers(
                                allocationTasks
                        )
                );
            }
            if (eligible.contains(DispatchProducerId.TASK_DISPATCH)) {
                startProducer(
                        DispatchProducerId.TASK_DISPATCH,
                        normalTasks.size(),
                        () -> dispatch.dispatchTasks(normalTasks)
                );
            }
            if (eligible.contains(DispatchProducerId.WORKER_SERVICEABILITY)) {
                startProducer(
                        DispatchProducerId.WORKER_SERVICEABILITY,
                        workerGroupIds.size(),
                        () -> Objects.requireNonNull(
                                serviceability,
                                "serviceability"
                        ).dispatchProbes(
                                workerGroupIds,
                                Objects.requireNonNull(
                                        serviceabilityConfig,
                                        "serviceabilityConfig"
                                )
                        )
                );
            }
        }

        private void startProducer(
                DispatchProducerId producerId,
                int batchSize,
                Runnable producer
        ) {
            ProducerRuntime runtime = Objects.requireNonNull(
                    runtimes.get(producerId),
                    "producer runtime"
            );
            if (!isRunning()) {
                return;
            }
            if (batchSize == 0) {
                deferProducer(runtime);
                return;
            }
            runtime.inflight = true;
            try {
                executor.submit(() -> {
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
                                runtime.id,
                                batchSize,
                                failure
                        ));
                    }
                });
            } catch (RejectedExecutionException failure) {
                runtime.inflight = false;
                throw new IllegalStateException(
                        "Dispatch Convergence executor rejected producer="
                                + runtime.id,
                        failure
                );
            }
        }

        private Set<DispatchProducerId> eligibleProducers() {
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

        private void drainCompletions() {
            ProducerCompletion completion;
            while ((completion = completions.poll()) != null) {
                applyCompletion(completion);
            }
        }

        private void applyCompletion(ProducerCompletion completion) {
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

        private ProducerCompletion waitForWork()
                throws InterruptedException {
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

        private void deferProducers(Set<DispatchProducerId> producers) {
            producers.forEach(producerId -> deferProducer(
                    Objects.requireNonNull(
                            runtimes.get(producerId),
                            "producer runtime"
                    )
            ));
        }

        private boolean isRunning() {
            return !Thread.currentThread().isInterrupted();
        }
    }

    private static Map<DispatchProducerId, ProducerRuntime> createRuntimes(
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchConfig serviceabilityConfig
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
        if (serviceabilityConfig != null) {
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
