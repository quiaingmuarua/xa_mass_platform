package com.xa.mass.testing.perf;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.worker.WorkerGroupRecord;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.watchdog.RuntimeReadyDispatchPump;
import com.xa.mass.runtime.api.TaskWorkRuntimeStats;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.redis.RedisTaskResultRuntime;
import com.xa.mass.runtime.redis.RedisTaskWorkRuntime;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runnable load model focused on the engine hot path:
 * callback -> progress recompute -> resource release -> redispatch.
 *
 * <p>Run with JVM properties to scale the scenario:
 *
 * <pre>{@code
 * -Dmass.load.messages=4096
 * -Dmass.load.workers=16
 * -Dmass.load.batchSize=8
 * -Dmass.load.callbackThreads=32
 * -Dmass.load.retryFailureEveryNth=7
 * -Dmass.load.duplicateResultEveryNth=11
 * -Dmass.load.duplicateWakeupsOnApprove=4
 * }</pre>
 */
public final class TaskFlowLoadModelRunner {

    private static final String PROJECT_CODE = "demoApp";
    private static final String WORKER_GROUP_ID = "load-workers";

    private TaskFlowLoadModelRunner() {
    }

    public static void main(String[] args) throws Exception {
        LoadConfig config = LoadConfig.fromSystemProperties();
        LoadReport report = new ScenarioRunner(config).run();
        System.out.println(report.toConsoleSummary());
        System.out.println("Task flow load report written to: " + report.reportPath());
    }

    private static final class ScenarioRunner {
        private final LoadConfig config;

        private ScenarioRunner(LoadConfig config) {
            this.config = config;
        }

        private LoadReport run() throws Exception {
            InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
            RuntimeBundle runtimes = RuntimeBundle.create(config);
            try {
                EngineConfig engineConfig = buildEngineConfig(taskStorage, runtimes.taskWorkRuntime(), runtimes.taskResultRuntime());
                TaskCommandService taskCommands = engineConfig.getTaskCommandService();
                TaskEventService taskEvents = engineConfig.getTaskEventService();
                TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
                TaskAssignmentRuntimePort assignmentRuntimePort = engineConfig.getTaskAssignmentRuntimePort();
                TaskRuntimeMaintenancePort maintenancePort = engineConfig.getTaskRuntimeMaintenancePort();
                TaskRuntimeRecoveryPort recoveryPort = engineConfig.getTaskRuntimeRecoveryPort();
                WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
                AssignmentRecordService recordService = new AssignmentRecordService();
                CallbackMetrics callbackMetrics = new CallbackMetrics();
                ReleaseMetrics releaseMetrics = new ReleaseMetrics();
                DispatchMetrics dispatchMetrics = new DispatchMetrics();
                ExecutorService callbackExecutor = Executors.newFixedThreadPool(config.callbackThreads(), r -> {
                    Thread thread = new Thread(r, "TaskFlowLoadModel-callback");
                    thread.setDaemon(true);
                    return thread;
                });

                AtomicReference<Task> terminalTask = new AtomicReference<>();
                CountDownLatch terminalLatch = new CountDownLatch(1);
                AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
                AtomicInteger callbackAttemptId = new AtomicInteger();
                AtomicReference<String> taskIdRef = new AtomicReference<>();
                Map<String, AtomicInteger> messageDeliveryAttempts = new ConcurrentHashMap<>();

                TaskDispatchBatchListener dispatchListener = (task, dispatchBindings) -> {
                    dispatchMetrics.recordDispatchCycle(dispatchBindings);
                    for (TaskDispatchBinding binding : dispatchBindings) {
                        callbackExecutor.submit(() -> {
                            int active = callbackMetrics.onCallbackStart();
                            try {
                                String taskId = task.taskId();
                                String messageId = binding.messageId();
                                int logicalSeq = ((Number) binding.payload().get("seq")).intValue();
                                int attemptNo = messageDeliveryAttempts
                                        .computeIfAbsent(messageId, ignored -> new AtomicInteger())
                                        .incrementAndGet();
                                boolean failFirstAttempt = config.retryFailureEveryNth() > 0
                                        && logicalSeq > 0
                                        && logicalSeq % config.retryFailureEveryNth() == 0
                                        && attemptNo == 1;
                                if (failFirstAttempt) {
                                    callbackMetrics.recordSyntheticRetry();
                                }
                                long startNanos = System.nanoTime();
                                boolean accepted = taskResultIngestFacade.ingestTaskResult(
                                        taskId,
                                        messageId,
                                        !failFirstAttempt,
                                        failFirstAttempt ? "synthetic retryable failure" : "ok",
                                        failFirstAttempt ? "SYNTHETIC_RETRY" : null,
                                        Map.of(
                                                "callbackAttempt", callbackAttemptId.incrementAndGet(),
                                                "logicalAttempt", attemptNo,
                                                "seq", logicalSeq
                                        )
                                );
                                callbackMetrics.onCallbackComplete(System.nanoTime() - startNanos, accepted, active);
                                if (!accepted) {
                                    callbackFailure.compareAndSet(null, new IllegalStateException(
                                            "callback rejected for message " + messageId));
                                } else if (shouldSubmitDuplicateResult(config, logicalSeq, failFirstAttempt)) {
                                    long duplicateStartNanos = System.nanoTime();
                                    boolean duplicateAccepted = taskResultIngestFacade.ingestTaskResult(
                                            taskId,
                                            messageId,
                                            true,
                                            "synthetic duplicate callback",
                                            null,
                                            Map.of(
                                                    "callbackAttempt", callbackAttemptId.incrementAndGet(),
                                                    "logicalAttempt", attemptNo,
                                                    "seq", logicalSeq,
                                                    "duplicateResult", true
                                            )
                                    );
                                    callbackMetrics.onDuplicateResultCallback(
                                            System.nanoTime() - duplicateStartNanos,
                                            duplicateAccepted
                                    );
                                }
                            } catch (Throwable t) {
                                callbackFailure.compareAndSet(null, t);
                            } finally {
                                callbackMetrics.onCallbackFinish();
                            }
                        });
                    }
                };

                TaskWorkerMatchingStrategy matchingStrategy = new DeterministicMatchingStrategy(workerManager);
                SimpleTaskDispatchBinder dispatchBinder =
                        new SimpleTaskDispatchBinder(
                                assignmentRuntimePort,
                                workerManager,
                                recordService,
                                dispatchListener
                        );
                TaskWorkerAssignListener workerAssignListener =
                        new TaskWorkerAssignListener(
                                matchingStrategy,
                                workerManager,
                                dispatchBinder,
                                assignmentRuntimePort,
                                taskEvents
                        );
                TaskAssignWorker assignWorker = new TaskAssignWorker(workerAssignListener, config.assignmentRetryDelayMillis());
                RuntimeReadyDispatchPump runtimeReadyDispatchPump =
                        new RuntimeReadyDispatchPump(recoveryPort, assignWorker::submit, 50L, 64);
                MeasuredTaskResourceReleaseListener releaseListener =
                        new MeasuredTaskResourceReleaseListener(
                                maintenancePort,
                                workerManager,
                                releaseMetrics
                        );

                try {
                    registerWorkers(workerManager, config);

                    taskEvents.addTaskReadyListener(assignWorker::submit);
                    taskEvents.addTaskDispatchListener(assignWorker::submit);
                    taskEvents.addTaskWorkAttemptClosedListener(releaseListener::onTaskWorkAttemptClosed);
                    taskEvents.addTaskTerminalListener(releaseListener::onTaskTerminal);
                    taskEvents.addTaskTerminalListener(task -> {
                        if (Objects.equals(taskIdRef.get(), task.getTid())) {
                            terminalTask.compareAndSet(null, task);
                            terminalLatch.countDown();
                        }
                    });

                    assignWorker.start();
                    runtimeReadyDispatchPump.start();

                    Task task = materializeTask(taskCommands, buildRequest(config));
                    taskIdRef.set(task.getTid());
                    long wallStartNanos = System.nanoTime();
                    require(taskCommands.approveTask(task.getTid()), "task should move NEW -> READY");
                    for (int i = 0; i < config.duplicateWakeupsOnApprove(); i++) {
                        assignWorker.submit(task);
                    }

                    if (!terminalLatch.await(config.timeoutSeconds(), TimeUnit.SECONDS)) {
                        Task currentTask = taskStorage.getTask(task.getTid()).orElse(task);
                        TaskWorkStats currentStats = runtimes.taskWorkRuntime().stats(task.getTid());
                        throw new IllegalStateException("load model timed out before task reached TERMINAL"
                                + " status=" + currentTask.getStatus()
                                + " terminalReason=" + currentTask.getTerminalReason()
                                + " dispatchCycles=" + dispatchMetrics.dispatchCycles.sum()
                                + " dispatchItems=" + dispatchMetrics.totalDispatchItems.sum()
                                + " callbacks=" + callbackMetrics.totalInvocations.sum()
                                + " acceptedCallbacks=" + callbackMetrics.acceptedInvocations.sum()
                                + " workStats=" + currentStats);
                    }

                    if (callbackFailure.get() != null) {
                        throw new IllegalStateException("callback execution failed", callbackFailure.get());
                    }

                    assignWorker.stop();
                    callbackExecutor.shutdown();
                    require(callbackExecutor.awaitTermination(10, TimeUnit.SECONDS),
                            "callback executor did not terminate");

                    Task finalTask = terminalTask.get();
                    require(finalTask != null, "task should be captured on terminal transition");

                    long totalWallNanos = System.nanoTime() - wallStartNanos;
                    TaskWorkStats finalWorkStats = runtimes.taskWorkRuntime().stats(task.getTid());
                    TaskWorkRuntimeStats finalRuntimeStats = runtimes.taskWorkRuntime().stats();
                    long finalResultCount = runtimes.taskResultRuntime().countVisibleResults(task.getTid());
                    RuntimeProofMetrics proofMetrics = RuntimeProofMetrics.from(
                            finalWorkStats,
                            finalRuntimeStats,
                            finalResultCount,
                            dispatchMetrics.totalDispatchItems.sum(),
                            dispatchMetrics.firstDispatchLagNanos(wallStartNanos),
                            totalWallNanos
                    );

                    require(finalWorkStats.totalCount() == config.messageCount(),
                            "unexpected final runtime work count");
                    require(finalWorkStats.successCount() == config.messageCount(),
                            "all runtime work should converge to success in the default model");
                    require(finalWorkStats.failedCount() == 0 && finalWorkStats.expiredCount() == 0,
                            "default model should not leave failed or expired runtime work");
                    require(finalWorkStats.pendingCount() == 0 && finalWorkStats.inflightCount() == 0,
                            "default model should not leave pending or in-flight runtime work");
                    require(finalResultCount == config.messageCount(),
                            "stable-final result count should match logical work count");
                    require(proofMetrics.processingCounterDrift() == 0,
                            "runtime processing counters should not drift at terminal");
                    require(proofMetrics.resultCounterDrift() == 0,
                            "runtime result count should not drift from successful work count");
                    if (config.retryFailureEveryNth() == 0) {
                        require(proofMetrics.duplicateDispatchItems() == 0,
                                "duplicate wakeups should not duplicate runtime dispatch claims");
                    }
                    if (config.duplicateResultEveryNth() > 0
                            && config.messageCount() > 1
                            && callbackMetrics.duplicateResultAttempts.sum() > 0) {
                        require(proofMetrics.duplicateResultItems() > 0,
                                "duplicate result proof should exercise runtime duplicate/late classification");
                    }
                    require(finalTask.getTerminalReason() == TaskTerminalReason.ALL_MESSAGES_SUCCEEDED,
                            "task should converge with ALL_MESSAGES_SUCCEEDED");

                    Path reportPath = writeReport(config, finalTask, totalWallNanos, dispatchMetrics, callbackMetrics,
                            releaseMetrics, finalWorkStats, proofMetrics);

                    return new LoadReport(
                            config,
                            finalTask.getTid(),
                            finalTask.getStatus().name(),
                            finalTask.getTerminalReason() != null ? finalTask.getTerminalReason().name() : "",
                            nanosToMillis(totalWallNanos),
                            dispatchMetrics.dispatchCycles.sum(),
                            dispatchMetrics.totalDispatchItems.sum(),
                            callbackMetrics.totalInvocations.sum(),
                            callbackMetrics.syntheticRetries.sum(),
                            callbackMetrics.acceptedInvocations.sum(),
                            callbackMetrics.rejectedInvocations.sum(),
                            nanosToMillis(callbackMetrics.totalCallbackNanos.sum()),
                            callbackMetrics.maxConcurrentCallbacks.get(),
                            releaseMetrics.attemptClosedInvocations.sum(),
                            releaseMetrics.taskTerminalInvocations.sum(),
                            nanosToMillis(releaseMetrics.totalAttemptClosedNanos.sum()),
                            nanosToMillis(releaseMetrics.totalTaskTerminalNanos.sum()),
                            FinalWorkStats.from(finalWorkStats),
                            proofMetrics,
                            reportPath
                    );
                } finally {
                    runtimeReadyDispatchPump.stop();
                    assignWorker.stop();
                    callbackExecutor.shutdownNow();
                }
            } finally {
                runtimes.shutdown();
            }
        }

        private static TaskCreatePlan buildRequest(LoadConfig config) {
            TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
            shell.setSourceRef("task-flow-load-model");
            shell.setProject(PROJECT_CODE);
            shell.setUserId("load-model");
            shell.setExecutionSpec(taskExecutionSpec(config.workloadClass(), config.batchSize(), config.maxRetryCount()));
            shell.setSharedConfig(Map.of(
                    "source", "TaskFlowLoadModelRunner",
                    TaskSharedConfig.WORKER_GROUP_ID, WORKER_GROUP_ID
            ));
            return new TaskCreatePlan(shell, buildInputs(config.messageCount()), false);
        }

        private static boolean shouldSubmitDuplicateResult(LoadConfig config,
                                                           int logicalSeq,
                                                           boolean failFirstAttempt) {
            return config.duplicateResultEveryNth() > 0
                    && !failFirstAttempt
                    && logicalSeq > 0
                    && logicalSeq % config.duplicateResultEveryNth() == 0;
        }

        private static Task materializeTask(TaskCommandService taskCommands, TaskCreatePlan request) {
            Task task = taskCommands.createTaskShell(request.shell());
            if (!request.inputs().isEmpty()) {
                taskCommands.appendTaskItems(task.getTid(), request.inputs());
            }
            if (!request.keepIntakeOpen()) {
                require(taskCommands.sealTask(task.getTid()), "task should seal after ingest");
            }
            return task;
        }

        private record TaskCreatePlan(TaskShellCreateRequestDto shell,
                                      List<Map<String, Object>> inputs,
                                      boolean keepIntakeOpen) {
        }

        private static TaskExecutionSpec taskExecutionSpec(TaskWorkloadClass workloadClass,
                                                           int batchSize,
                                                           int defaultMaxRetryCount) {
            TaskExecutionSpec spec = new TaskExecutionSpec();
            spec.setWorkloadClass(workloadClass);
            spec.setBatchSize(batchSize);
            spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
            return spec;
        }

        private static EngineConfig buildEngineConfig(InMemoryTaskStorage taskStorage,
                                                      TaskWorkRuntime taskWorkRuntime,
                                                      TaskResultRuntime taskResultRuntime) {
            EngineConfig engineConfig = new EngineConfig();
            engineConfig.setTaskStorage(taskStorage);
            engineConfig.setTaskDetailStore(taskStorage);
            engineConfig.setTaskWorkRuntime(taskWorkRuntime);
            engineConfig.setTaskResultRuntime(taskResultRuntime);
            return engineConfig;
        }

        private static List<Map<String, Object>> buildInputs(int messageCount) {
            List<Map<String, Object>> inputs = new ArrayList<>(messageCount);
            for (int i = 0; i < messageCount; i++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("seq", i);
                input.put("target", "target-" + i);
                inputs.add(input);
            }
            return inputs;
        }

        private static void registerWorkers(WorkerManager workerManager, LoadConfig config) {
            workerManager.upsertWorkerGroup(WorkerGroupRecord.builder(WORKER_GROUP_ID)
                    .projectCodes(List.of(PROJECT_CODE))
                    .defaultMaxConcurrentWork(config.batchSize())
                    .build());
            for (int i = 0; i < config.workerCount(); i++) {
                Worker worker = new Worker();
                worker.setWorkerId("load-worker-" + i);
                worker.setAgentVersion("load-model");
                worker.setWorkerGroupId(WORKER_GROUP_ID);
                worker.setSupportedProjects(List.of(PROJECT_CODE));
                worker.setStatus(WorkerStatus.ONLINE);
                worker.setLastHeartbeat(LocalDateTime.now());
                workerManager.addWorker(worker);
            }
        }

        private static Path writeReport(LoadConfig config,
                                        Task task,
                                        long totalWallNanos,
                                        DispatchMetrics dispatchMetrics,
                                        CallbackMetrics callbackMetrics,
                                        ReleaseMetrics releaseMetrics,
                                        TaskWorkStats finalWorkStats,
                                        RuntimeProofMetrics proofMetrics) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>(WorkerFaultReportMetadata.topLevel(
                    WorkerFaultScenarioIndex.Scenario.TASK_FLOW_LOAD_MODEL));
            report.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            report.put("config", config.toMap());
            report.put("task", Map.of(
                    "taskId", task.getTid(),
                    "status", task.getStatus().name(),
                    "terminalReason", task.getTerminalReason() != null ? task.getTerminalReason().name() : "",
                    "peakAssignedWorkerCount", task.getPeakAssignedWorkerCount(),
                    "batchSize", task.getExecutionSpec().getBatchSize(),
                    "workloadClass", task.getExecutionSpec().getWorkloadClass() != null
                            ? task.getExecutionSpec().getWorkloadClass().name()
                            : null
            ));
            report.put("wallClock", Map.of(
                    "totalMillis", nanosToMillis(totalWallNanos),
                    "dispatchCycles", dispatchMetrics.dispatchCycles.sum(),
                    "redispatchCycles", Math.max(dispatchMetrics.dispatchCycles.sum() - 1, 0L),
                    "syntheticDuplicateWakeups", config.duplicateWakeupsOnApprove(),
                    "totalDispatchItems", dispatchMetrics.totalDispatchItems.sum(),
                    "runtimeWorkItems", finalWorkStats.totalCount(),
                    "dispatchOverheadItems", Math.max(dispatchMetrics.totalDispatchItems.sum() - finalWorkStats.totalCount(), 0L)
            ));
            Map<String, Object> callbacks = new LinkedHashMap<>();
            callbacks.put("invocations", callbackMetrics.totalInvocations.sum());
            callbacks.put("syntheticRetries", callbackMetrics.syntheticRetries.sum());
            callbacks.put("duplicateResultAttempts", callbackMetrics.duplicateResultAttempts.sum());
            callbacks.put("duplicateResultAccepted", callbackMetrics.duplicateResultAccepted.sum());
            callbacks.put("duplicateResultRejected", callbackMetrics.duplicateResultRejected.sum());
            callbacks.put("duplicateResultCallbackMillis", nanosToMillis(callbackMetrics.duplicateResultCallbackNanos.sum()));
            callbacks.put("acceptedInvocations", callbackMetrics.acceptedInvocations.sum());
            callbacks.put("rejectedInvocations", callbackMetrics.rejectedInvocations.sum());
            callbacks.put("maxConcurrentCallbacks", callbackMetrics.maxConcurrentCallbacks.get());
            callbacks.put("totalCallbackMillis", nanosToMillis(callbackMetrics.totalCallbackNanos.sum()));
            callbacks.put("avgCallbackMillis", formatDecimal(safeDivide(callbackMetrics.totalCallbackNanos.sum(),
                    callbackMetrics.totalInvocations.sum(), 1_000_000.0)));
            report.put("callbacks", callbacks);
            report.put("release", Map.of(
                    "attemptClosedInvocations", releaseMetrics.attemptClosedInvocations.sum(),
                    "taskTerminalInvocations", releaseMetrics.taskTerminalInvocations.sum(),
                    "attemptClosedMillis", nanosToMillis(releaseMetrics.totalAttemptClosedNanos.sum()),
                    "taskTerminalMillis", nanosToMillis(releaseMetrics.totalTaskTerminalNanos.sum())
            ));
            report.put("finalWorkStats", FinalWorkStats.from(finalWorkStats).toMap());
            report.put("runtimeProof", proofMetrics.toMap());

            Path reportDir = TestingPaths.reportDir("perf-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("task-flow-load-model-" + timestamp + ".json");
            Files.writeString(reportPath, toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private static final class DeterministicMatchingStrategy implements TaskWorkerMatchingStrategy {
        private final WorkerManager workerManager;

        private DeterministicMatchingStrategy(WorkerManager workerManager) {
            this.workerManager = workerManager;
        }

        @Override
        public List<WorkerSchedulingCandidate> matchWorkers(Task task, int maxWorkerCount) {
            List<String> workerGroupSelector = TaskSharedConfig.workerGroupSelector(task);
            if (workerGroupSelector.isEmpty()) {
                return List.of();
            }
            List<WorkerSchedulingCandidate> matched = new ArrayList<>();
            for (Worker worker : workerManager.getAllWorkers()) {
                if (matched.size() >= maxWorkerCount) {
                    break;
                }
                if (!worker.isAvailable()
                        || !workerGroupSelector.contains(worker.getWorkerGroupId())
                        || !worker.supportsProject(task.getProject())) {
                    continue;
                }
                WorkerSchedulingCandidate candidate =
                        PerfWorkerMatchingSupport.tryReserveCandidate(workerManager, task, worker);
                if (candidate != null) {
                    matched.add(candidate);
                }
            }
            return matched;
        }
    }

    private static final class MeasuredTaskResourceReleaseListener extends TaskResourceReleaseListener {
        private final ReleaseMetrics metrics;

        private MeasuredTaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                                    WorkerManager workerManager,
                                                    ReleaseMetrics metrics) {
            super(maintenancePort, workerManager);
            this.metrics = metrics;
        }

        @Override
        public void onTaskTerminal(Task task) {
            metrics.taskTerminalInvocations.increment();
            long start = System.nanoTime();
            super.onTaskTerminal(task);
            metrics.totalTaskTerminalNanos.add(System.nanoTime() - start);
        }

        @Override
        public void onTaskWorkAttemptClosed(Task task, com.xa.mass.engine.TaskWorkAttemptClosedEvent event) {
            metrics.attemptClosedInvocations.increment();
            long start = System.nanoTime();
            super.onTaskWorkAttemptClosed(task, event);
            metrics.totalAttemptClosedNanos.add(System.nanoTime() - start);
        }
    }

    private static final class DispatchMetrics {
        private final LongAdder dispatchCycles = new LongAdder();
        private final LongAdder totalDispatchItems = new LongAdder();
        private final AtomicReference<Long> firstDispatchAtNanos = new AtomicReference<>();

        private void recordDispatchCycle(List<TaskDispatchBinding> dispatchBindings) {
            firstDispatchAtNanos.compareAndSet(null, System.nanoTime());
            dispatchCycles.increment();
            totalDispatchItems.add(dispatchBindings.size());
        }

        private long firstDispatchLagNanos(long wallStartNanos) {
            Long firstDispatch = firstDispatchAtNanos.get();
            if (firstDispatch == null || wallStartNanos <= 0L || firstDispatch < wallStartNanos) {
                return -1L;
            }
            return firstDispatch - wallStartNanos;
        }
    }

    private static final class CallbackMetrics {
        private final LongAdder totalInvocations = new LongAdder();
        private final LongAdder syntheticRetries = new LongAdder();
        private final LongAdder duplicateResultAttempts = new LongAdder();
        private final LongAdder duplicateResultAccepted = new LongAdder();
        private final LongAdder duplicateResultRejected = new LongAdder();
        private final LongAdder duplicateResultCallbackNanos = new LongAdder();
        private final LongAdder acceptedInvocations = new LongAdder();
        private final LongAdder rejectedInvocations = new LongAdder();
        private final LongAdder totalCallbackNanos = new LongAdder();
        private final AtomicInteger activeCallbacks = new AtomicInteger();
        private final LongAccumulator maxConcurrentCallbacks = new LongAccumulator(Long::max, 0);

        private int onCallbackStart() {
            totalInvocations.increment();
            int active = activeCallbacks.incrementAndGet();
            maxConcurrentCallbacks.accumulate(active);
            return active;
        }

        private void onCallbackComplete(long elapsedNanos, boolean accepted, int activeCallbacksSnapshot) {
            totalCallbackNanos.add(elapsedNanos);
            if (accepted) {
                acceptedInvocations.increment();
            } else {
                rejectedInvocations.increment();
            }
            maxConcurrentCallbacks.accumulate(activeCallbacksSnapshot);
        }

        private void onCallbackFinish() {
            activeCallbacks.decrementAndGet();
        }

        private void recordSyntheticRetry() {
            syntheticRetries.increment();
        }

        private void onDuplicateResultCallback(long elapsedNanos, boolean accepted) {
            duplicateResultAttempts.increment();
            duplicateResultCallbackNanos.add(elapsedNanos);
            if (accepted) {
                duplicateResultAccepted.increment();
            } else {
                duplicateResultRejected.increment();
            }
        }
    }

    private static final class ReleaseMetrics {
        private final LongAdder attemptClosedInvocations = new LongAdder();
        private final LongAdder taskTerminalInvocations = new LongAdder();
        private final LongAdder totalAttemptClosedNanos = new LongAdder();
        private final LongAdder totalTaskTerminalNanos = new LongAdder();
    }

    private enum RuntimeBackend {
        MEMORY,
        REDIS
    }

    private record RuntimeBundle(RuntimeBackend backend,
                                 TaskWorkRuntime taskWorkRuntime,
                                 TaskResultRuntime taskResultRuntime,
                                 String redisUri,
                                 String redisNamespace,
                                 boolean cleanupRedisNamespace) {
        private static RuntimeBundle create(LoadConfig config) {
            if (config.runtimeBackend() == RuntimeBackend.REDIS) {
                TaskFlowLoadModelRunner.cleanupRedisNamespace(
                        config.redisUri(),
                        config.redisNamespace(),
                        config.redisCleanupNamespace()
                );
                return new RuntimeBundle(
                        RuntimeBackend.REDIS,
                        new RedisTaskWorkRuntime(config.redisUri(), config.redisNamespace(), config.maxQueuedItems()),
                        new RedisTaskResultRuntime(config.redisUri(), config.redisNamespace() + ":result"),
                        config.redisUri(),
                        config.redisNamespace(),
                        config.redisCleanupNamespace()
                );
            }
            return new RuntimeBundle(
                    RuntimeBackend.MEMORY,
                    new InMemoryTaskWorkRuntime(),
                    new InMemoryTaskResultRuntime(),
                    "",
                    "",
                    false
            );
        }

        private void shutdown() {
            taskWorkRuntime.shutdown();
            taskResultRuntime.shutdown();
            if (backend == RuntimeBackend.REDIS) {
                TaskFlowLoadModelRunner.cleanupRedisNamespace(redisUri, redisNamespace, cleanupRedisNamespace);
            }
        }
    }

    private record RuntimeProofMetrics(long finalResultCount,
                                       long duplicateDispatchItems,
                                       long duplicateResultItems,
                                       long staleResultItems,
                                       long expiredLeaseItems,
                                       long processingCounterDrift,
                                       long resultCounterDrift,
                                       double firstDispatchLagMillis,
                                       double claimedMessagesPerSecond) {
        private static RuntimeProofMetrics from(TaskWorkStats stats,
                                                TaskWorkRuntimeStats runtimeStats,
                                                long finalResultCount,
                                                long totalDispatchItems,
                                                long firstDispatchLagNanos,
                                                long totalWallNanos) {
            long processingCounterDrift = Math.abs(stats.pendingCount() - stats.processingCount());
            long resultCounterDrift = Math.abs(stats.successCount() - finalResultCount);
            double seconds = Math.max(totalWallNanos / 1_000_000_000.0, 0.001);
            return new RuntimeProofMetrics(
                    finalResultCount,
                    Math.max(totalDispatchItems - stats.totalCount(), 0L),
                    runtimeStats.duplicateResultItems(),
                    runtimeStats.staleResultItems(),
                    runtimeStats.expiredLeaseItems(),
                    processingCounterDrift,
                    resultCounterDrift,
                    firstDispatchLagNanos < 0L ? -1.0 : nanosToMillis(firstDispatchLagNanos),
                    stats.totalCount() / seconds
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("finalResultCount", finalResultCount);
            values.put("duplicateDispatchItems", duplicateDispatchItems);
            values.put("duplicateResultItems", duplicateResultItems);
            values.put("staleResultItems", staleResultItems);
            values.put("expiredLeaseItems", expiredLeaseItems);
            values.put("processingCounterDrift", processingCounterDrift);
            values.put("resultCounterDrift", resultCounterDrift);
            values.put("firstDispatchLagMillis", firstDispatchLagMillis);
            values.put("claimedMessagesPerSecond", claimedMessagesPerSecond);
            return values;
        }
    }

    private record LoadConfig(int messageCount,
                              int workerCount,
                              int batchSize,
                              int callbackThreads,
                              int maxRetryCount,
                              TaskWorkloadClass workloadClass,
                              int retryFailureEveryNth,
                              int duplicateResultEveryNth,
                              int duplicateWakeupsOnApprove,
                              long timeoutSeconds,
                              long assignmentRetryDelayMillis,
                              RuntimeBackend runtimeBackend,
                              String redisUri,
                              String redisNamespace,
                              int maxQueuedItems,
                              boolean redisCleanupNamespace) {
        private static LoadConfig fromSystemProperties() {
            int retryFailureEveryNth = intProperty("mass.load.retryFailureEveryNth", 0);
            int maxRetryCount = Math.max(intProperty("mass.load.maxRetryCount", retryFailureEveryNth > 0 ? 1 : 0), 0);
            RuntimeBackend runtimeBackend = enumProperty("mass.load.runtimeBackend", RuntimeBackend.MEMORY);
            return new LoadConfig(
                    intProperty("mass.load.messages", 256),
                    intProperty("mass.load.workers", 8),
                    intProperty("mass.load.batchSize", 4),
                    intProperty("mass.load.callbackThreads", 8),
                    maxRetryCount,
                    workloadClassProperty("mass.load.workloadClass", TaskWorkloadClass.BULK),
                    retryFailureEveryNth,
                    intProperty("mass.load.duplicateResultEveryNth", 0),
                    intProperty("mass.load.duplicateWakeupsOnApprove", 0),
                    longProperty("mass.load.timeoutSeconds", 60L),
                    longProperty("mass.load.assignmentRetryDelayMillis", 25L),
                    runtimeBackend,
                    stringProperty("mass.load.redisUri", "redis://localhost:6379"),
                    stringProperty("mass.load.redisNamespace", defaultRedisNamespace()),
                    intProperty("mass.load.maxQueuedItems", 10_000),
                    booleanProperty("mass.load.redisCleanupNamespace", true)
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("messageCount", messageCount);
            config.put("workerCount", workerCount);
            config.put("batchSize", batchSize);
            config.put("callbackThreads", callbackThreads);
            config.put("maxRetryCount", maxRetryCount);
            config.put("workloadClass", workloadClass.name());
            config.put("retryFailureEveryNth", retryFailureEveryNth);
            config.put("duplicateResultEveryNth", duplicateResultEveryNth);
            config.put("duplicateWakeupsOnApprove", duplicateWakeupsOnApprove);
            config.put("timeoutSeconds", timeoutSeconds);
            config.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            config.put("runtimeBackend", runtimeBackend.name().toLowerCase(Locale.ROOT));
            config.put("redisUri", runtimeBackend == RuntimeBackend.REDIS ? redisUri : "");
            config.put("redisNamespace", runtimeBackend == RuntimeBackend.REDIS ? redisNamespace : "");
            config.put("maxQueuedItems", maxQueuedItems);
            config.put("redisCleanupNamespace", redisCleanupNamespace);
            return config;
        }
    }

    private record LoadReport(LoadConfig config,
                              String taskId,
                              String taskStatus,
                              String terminalReason,
                              double totalWallMillis,
                              long dispatchCycles,
                              long totalDispatchItems,
                              long callbackInvocations,
                              long syntheticRetries,
                              long acceptedCallbacks,
                              long rejectedCallbacks,
                              double totalCallbackMillis,
                              long maxConcurrentCallbacks,
                              long attemptClosedInvocations,
                              long taskTerminalInvocations,
                              double attemptClosedMillis,
                              double taskTerminalMillis,
                              FinalWorkStats finalWorkStats,
                              RuntimeProofMetrics runtimeProofMetrics,
                              Path reportPath) {

        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "TaskFlowLoadModel backend=%s taskId=%s status=%s terminalReason=%s wall=%.3fms dispatchCycles=%d dispatchItems=%d callbacks=%d syntheticRetries=%d maxConcurrentCallbacks=%d claimedPerSec=%.3f resultDrift=%d report=%s",
                    config.runtimeBackend().name().toLowerCase(Locale.ROOT),
                    taskId,
                    taskStatus,
                    terminalReason,
                    totalWallMillis,
                    dispatchCycles,
                    totalDispatchItems,
                    callbackInvocations,
                    syntheticRetries,
                    maxConcurrentCallbacks,
                    runtimeProofMetrics.claimedMessagesPerSecond(),
                    runtimeProofMetrics.resultCounterDrift(),
                    reportPath);
        }
    }

    private record FinalWorkStats(long totalWorkItems,
                                  long readyWorkItems,
                                  long inflightWorkItems,
                                  long delayedWorkItems,
                                  long successWorkItems,
                                  long failedWorkItems,
                                  long expiredWorkItems,
                                  long finalWorkItems,
                                  long pendingWorkItems) {
        private static FinalWorkStats from(TaskWorkStats stats) {
            return new FinalWorkStats(
                    stats.totalCount(),
                    stats.readyCount(),
                    stats.inflightCount(),
                    stats.delayedCount(),
                    stats.successCount(),
                    stats.failedCount(),
                    stats.expiredCount(),
                    stats.finalCount(),
                    stats.pendingCount()
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("totalWorkItems", totalWorkItems);
            values.put("readyWorkItems", readyWorkItems);
            values.put("inflightWorkItems", inflightWorkItems);
            values.put("delayedWorkItems", delayedWorkItems);
            values.put("successWorkItems", successWorkItems);
            values.put("failedWorkItems", failedWorkItems);
            values.put("expiredWorkItems", expiredWorkItems);
            values.put("finalWorkItems", finalWorkItems);
            values.put("pendingWorkItems", pendingWorkItems);
            return values;
        }
    }

    private static int intProperty(String key, int defaultValue) {
        return Integer.parseInt(System.getProperty(key, Integer.toString(defaultValue)));
    }

    private static long longProperty(String key, long defaultValue) {
        return Long.parseLong(System.getProperty(key, Long.toString(defaultValue)));
    }

    private static TaskWorkloadClass workloadClassProperty(String key, TaskWorkloadClass defaultValue) {
        return enumProperty(key, defaultValue);
    }

    private static <T extends Enum<T>> T enumProperty(String key, T defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Enum.valueOf(defaultValue.getDeclaringClass(), raw.trim().toUpperCase(Locale.ROOT));
    }

    private static String stringProperty(String key, String defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim();
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue)));
    }

    private static String defaultRedisNamespace() {
        return "xa:mass:perf:" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    private static void cleanupRedisNamespace(String redisUri, String redisNamespace, boolean enabled) {
        if (!enabled || redisUri == null || redisUri.isBlank() || redisNamespace == null || redisNamespace.isBlank()) {
            return;
        }
        if (!redisNamespace.startsWith("xa:mass:perf:")) {
            return;
        }
        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            KeyScanCursor<String> cursor = null;
            do {
                cursor = cursor == null
                        ? connection.sync().scan(ScanArgs.Builder.matches(redisNamespace + "*").limit(500))
                        : connection.sync().scan(cursor, ScanArgs.Builder.matches(redisNamespace + "*").limit(500));
                if (!cursor.getKeys().isEmpty()) {
                    connection.sync().del(cursor.getKeys().toArray(String[]::new));
                }
            } while (!cursor.isFinished());
        } finally {
            client.shutdown();
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double safeDivide(long numerator, long denominator, double scale) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator / scale / denominator;
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + escapeJson(stringValue) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Path path) {
            return toJson(path.toString());
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add("  " + toJson(String.valueOf(entry.getKey())) + ": "
                        + indentJson(toJson(entry.getValue())));
            }
            builder.append(String.join(",\n", entries));
            builder.append('\n').append('}');
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(indentJson(toJson(item)));
            }
            return "[\n  " + String.join(",\n  ", items) + "\n]";
        }
        return toJson(String.valueOf(value));
    }

    private static String indentJson(String json) {
        return json.replace("\n", "\n  ");
    }

    private static String escapeJson(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
