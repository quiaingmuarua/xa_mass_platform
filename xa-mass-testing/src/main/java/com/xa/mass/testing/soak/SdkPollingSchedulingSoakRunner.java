package com.xa.mass.testing.soak;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.trace.operator.TraceStatsRequest;
import com.xa.mass.trace.operator.TraceStatsResponse;
import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceValidateRequest;
import com.xa.mass.trace.operator.TraceValidateResponse;
import com.xa.mass.trace.operator.TraceOperatorService;
import com.xa.mass.trace.sink.JsonlExecutionEventSink;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Long-running SDK polling worker soak for engine scheduling pressure.
 *
 * <p>This runner is intentionally not a default Maven test. It is a manual or
 * scheduled validation lane that proves the runtime mainline through SDK
 * polling workers, runtime counters, result sequential reads, and canonical
 * trace JSONL validation.</p>
 */
public final class SdkPollingSchedulingSoakRunner {

    private static final String PROJECT_CODE = "soakProject";
    private static final String USER_ID = "sdk-polling-soak";
    private static final String ADAPTER_ID = "polling";
    private static final DateTimeFormatter RUN_ID_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private SdkPollingSchedulingSoakRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        SoakConfig config = null;
        try {
            config = SoakConfig.fromSystemProperties();
            SoakReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK polling scheduling soak report written to: " + report.reportPath());
        } catch (Throwable t) {
            exitCode = 1;
            throw t;
        } finally {
            if (config == null || config.forceExit()) {
                System.exit(exitCode);
            }
        }
    }

    private static final class ScenarioRunner {
        private final SoakConfig config;
        private final SoakMetrics metrics = new SoakMetrics();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        private final List<String> eventCodes;
        private final String runId;

        private ScenarioRunner(SoakConfig config) {
            this.config = config;
            this.eventCodes = buildEventCodes(config.eventCodeCount());
            this.runId = "polling-scheduling-soak-" + RUN_ID_TS.format(Instant.now());
        }

        private SoakReport run() throws Exception {
            Path traceDir = TestingPaths.reportDir("soak-traces").resolve(runId);
            JsonlExecutionEventSink traceSink = null;
            if (config.traceEnabled()) {
                Files.createDirectories(traceDir);
                traceSink = new JsonlExecutionEventSink(
                        traceDir.toString(),
                        config.traceQueueCapacity(),
                        config.traceRotateAfterLines()
                );
            }
            EmbeddedRuntime runtime = buildRuntime(traceSink);
            MassSdkApplication app = runtime.app();
            List<SimulatedPollingWorker> workers = new ArrayList<>(config.workerCount());
            List<SoakTaskPlan> taskPlans = new ArrayList<>();
            long startedNanos = System.nanoTime();
            long submittedTasks;
            FinalTaskStats taskStats;
            FinalWorkStats workStats;
            ResultReadStats resultReadStats;
            Map<String, Object> deliveryDiagnostics;
            WorkerLifecycleStats workerLifecycle;
            SoakInvariantReport invariantReport;

            try {
                app.start();
                bootstrapCatalog(app);
                registerWorkers(app);
                workers = startWorkers(app, 0, config.initialWorkerCount(), "polling-soak-start");
                submittedTasks = submitTasksForDuration(app, taskPlans, workers);
                waitForTerminalTasks(app, taskPlans);
                stopRequested.set(true);
                closeWorkers(workers);
                taskStats = collectFinalTaskStats(app, taskPlans);
                workStats = collectFinalWorkStats(runtime.taskWorkRuntime(), taskPlans);
                resultReadStats = verifyResultReads(app, taskPlans);
                deliveryDiagnostics = collectDeliveryDiagnostics(app);
                workerLifecycle = workerLifecycleStats();
            } finally {
                stopRequested.set(true);
                closeWorkers(workers);
                try {
                    app.stop();
                } catch (RuntimeException ignored) {
                    // Best effort shutdown for CLI-style runner cleanup.
                }
                if (traceSink != null) {
                    traceSink.close();
                }
            }

            long wallNanos = System.nanoTime() - startedNanos;
            TraceProof traceProof = verifyTrace(traceSink, traceDir, taskPlans, workerLifecycle);
            SoakTraceProof traceProofSummary = traceProof.toSummary();
            invariantReport = verifyInvariants(
                    submittedTasks,
                    taskStats,
                    workStats,
                    resultReadStats,
                    traceProof,
                    workerLifecycle
            );

            Map<String, Object> reportBody = buildReport(
                    submittedTasks,
                    taskStats,
                    workStats,
                    resultReadStats,
                    deliveryDiagnostics,
                    traceProofSummary,
                    workerLifecycle,
                    invariantReport,
                    wallNanos
            );
            Path reportPath = SoakReportWriter.write(runId, reportBody);
            assertSoakPassed(invariantReport);

            return new SoakReport(
                    runId,
                    submittedTasks,
                    taskStats.terminalTasks(),
                    workStats.totalWorkItems(),
                    resultReadStats.totalResults(),
                    workStats.activeLeasesAtEnd(),
                    traceProof.droppedCount(),
                    reportPath
            );
        }

        private EmbeddedRuntime buildRuntime(JsonlExecutionEventSink traceSink) {
            InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
            TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
            MassSdkApplication app = MassSdk.builder()
                    .transport(transport -> transport
                            .webSocketAdapter(webSocket -> webSocket
                                    .server(0, "/soak")
                                    .enabled(false)
                                    .serverEnabled(false))
                            .socketAdapter(socket -> socket
                                    .server(0)
                                    .enabled(false)
                                    .serverEnabled(false))
                            .inputQueue(new InMemoryMessageQueue<>("soak-input", String.class))
                            .outputQueue(new InMemoryMessageQueue<>("soak-output",
                                    com.xa.mass.transport.model.TransportOutboundMessage.class))
                            .queueMode())
                    .engine(engine -> {
                        engine.enabled(true)
                                .taskStorage(taskStorage)
                                .taskDetailStore(taskStorage)
                                .taskWorkRuntime(taskWorkRuntime);
                        if (traceSink != null) {
                            engine.executionEventSink(traceSink);
                        }
                    })
                    .build();
            return new EmbeddedRuntime(app, taskWorkRuntime);
        }

        private void bootstrapCatalog(MassSdkApplication app) {
            for (String eventCode : eventCodes) {
                if (app.getEvent(eventCode) == null) {
                    app.registerEventDefinition(EventDefinition.builder()
                            .code(eventCode)
                            .name("Polling Soak " + eventCode)
                            .description("Polling soak event " + eventCode)
                            .payloadTypes(List.of(PayloadType.JSON))
                            .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                            .projectCodes(List.of(PROJECT_CODE))
                            .build());
                }
            }
            if (app.getProject(PROJECT_CODE) == null) {
                app.registerProject(ProjectDefinition.builder()
                        .code(PROJECT_CODE)
                        .name("Polling Soak Project")
                        .description("SDK polling scheduling soak project.")
                        .eventCodes(eventCodes)
                        .build());
            }
        }

        private void registerWorkers(MassSdkApplication app) {
            for (int i = 0; i < config.workerCount(); i++) {
                int groupIndex = i % config.groupCount();
                String groupId = groupId(groupIndex);
                String workerId = workerId(i);
                app.registerWorker(WorkerRegistration.builder()
                        .workerId(workerId)
                        .workerGroupId(groupId)
                        .eventBindings(eventBindingsForGroup(groupIndex))
                        .transportHint(WorkerTransportHints.POLLING)
                        .adapterId(ADAPTER_ID)
                        .maxConcurrentWork(Math.max(1, config.pollBatchSize()))
                        .attributes(Map.of(
                                "soakRunId", runId,
                                "workerGroupId", groupId
                        ))
                        .build());
            }
        }

        private List<WorkerEventBinding> eventBindingsForGroup(int groupIndex) {
            List<WorkerEventBinding> bindings = new ArrayList<>();
            for (int eventIndex = 0; eventIndex < eventCodes.size(); eventIndex++) {
                if (eventIndex % config.groupCount() == groupIndex) {
                    bindings.add(WorkerEventBinding.builder()
                            .eventCode(eventCodes.get(eventIndex))
                            .projectCodes(List.of(PROJECT_CODE))
                            .build());
                }
            }
            if (bindings.isEmpty()) {
                bindings.add(WorkerEventBinding.builder()
                        .eventCode(eventCodes.get(groupIndex % eventCodes.size()))
                        .projectCodes(List.of(PROJECT_CODE))
                        .build());
            }
            return bindings;
        }

        private List<SimulatedPollingWorker> startWorkers(MassSdkApplication app,
                                                          int fromInclusive,
                                                          int toExclusive,
                                                          String connectReason) {
            List<SimulatedPollingWorker> workers = new ArrayList<>(Math.max(0, toExclusive - fromInclusive));
            for (int i = fromInclusive; i < toExclusive; i++) {
                String workerId = workerId(i);
                SimulatedPollingWorker worker = new SimulatedPollingWorker(
                        workerId,
                        app.pullWorker(workerId),
                        config,
                        metrics,
                        stopRequested,
                        failures
                );
                worker.start(connectReason);
                workers.add(worker);
            }
            return workers;
        }

        private long submitTasksForDuration(MassSdkApplication app,
                                            List<SoakTaskPlan> taskPlans,
                                            List<SimulatedPollingWorker> workers) throws Exception {
            long startedNanos = System.nanoTime();
            long endNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.durationSeconds());
            long lateWorkerStartNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(
                    config.lateWorkerStartAfterMillis());
            long intervalNanos = TimeUnit.SECONDS.toNanos(1) / config.submitRatePerSecond();
            long nextSubmitNanos = System.nanoTime();
            int taskIndex = 0;
            boolean lateWorkersStarted = config.initialWorkerCount() >= config.workerCount();
            while (System.nanoTime() < endNanos) {
                if (!lateWorkersStarted && System.nanoTime() >= lateWorkerStartNanos) {
                    workers.addAll(startWorkers(
                            app,
                            config.initialWorkerCount(),
                            config.workerCount(),
                            "polling-soak-late-start"
                    ));
                    lateWorkersStarted = true;
                }
                long now = System.nanoTime();
                if (now < nextSubmitNanos) {
                    TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(50), nextSubmitNanos - now));
                    continue;
                }
                String eventCode = eventCodes.get(taskIndex % eventCodes.size());
                taskPlans.add(createTask(app, taskIndex, eventCode));
                taskIndex++;
                nextSubmitNanos += intervalNanos;
                if (nextSubmitNanos < System.nanoTime() - intervalNanos) {
                    nextSubmitNanos = System.nanoTime();
                }
            }
            if (!lateWorkersStarted) {
                workers.addAll(startWorkers(
                        app,
                        config.initialWorkerCount(),
                        config.workerCount(),
                        "polling-soak-late-start"
                ));
            }
            return taskIndex;
        }

        private SoakTaskPlan createTask(MassSdkApplication app, int taskIndex, String eventCode) {
            TaskExecutionOptions options = new TaskExecutionOptions();
            options.setWorkloadClass("BULK");
            options.setBatchSize(Math.max(1, Math.min(config.pollBatchSize(), config.messagesPerTask())));
            options.setDefaultMaxRetryCount(0);
            options.setMaxRuntimeSeconds(Math.max(config.drainTimeoutSeconds(), config.durationSeconds()));
            TaskShellSnapshot task = app.createTaskShell(MassTaskShellCreateRequest.builder()
                    .userId(USER_ID)
                    .project(PROJECT_CODE)
                    .sourceRef(runId + "-task-" + taskIndex)
                    .executionSpec(options)
                    .sharedConfig(Map.of(
                            "source", "SdkPollingSchedulingSoakRunner",
                            "runId", runId,
                            "taskIndex", taskIndex,
                            "eventCode", eventCode
                    ))
                    .build());
            app.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                    .eventCode(eventCode)
                    .items(buildItems(taskIndex, eventCode))
                    .build());
            require(app.sealTask(task.getTaskId()), "task seal should succeed for " + task.getTaskId());
            require(app.approveTask(task.getTaskId()), "task approval should succeed for " + task.getTaskId());
            return buildTaskPlan(task.getTaskId(), taskIndex, eventCode);
        }

        private SoakTaskPlan buildTaskPlan(String taskId, int taskIndex, String eventCode) {
            int expectedSuccess = 0;
            int expectedFailed = 0;
            for (int messageIndex = 0; messageIndex < config.messagesPerTask(); messageIndex++) {
                long globalSeq = globalSeq(taskIndex, messageIndex, config.messagesPerTask());
                if (isExpectedSuccess(globalSeq, config.failureEveryNth())) {
                    expectedSuccess++;
                } else {
                    expectedFailed++;
                }
            }
            String expectedTerminalReason;
            if (expectedFailed == 0) {
                expectedTerminalReason = "ALL_MESSAGES_SUCCEEDED";
            } else if (expectedSuccess == 0) {
                expectedTerminalReason = "ALL_MESSAGES_FAILED";
            } else {
                expectedTerminalReason = "MIXED_MESSAGE_RESULTS";
            }
            return new SoakTaskPlan(
                    taskId,
                    taskIndex,
                    eventCode,
                    expectedSuccess,
                    expectedFailed,
                    expectedTerminalReason
            );
        }

        private List<Object> buildItems(int taskIndex, String eventCode) {
            List<Object> items = new ArrayList<>(config.messagesPerTask());
            for (int messageIndex = 0; messageIndex < config.messagesPerTask(); messageIndex++) {
                long globalSeq = globalSeq(taskIndex, messageIndex, config.messagesPerTask());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("runId", runId);
                item.put("taskIndex", taskIndex);
                item.put("messageIndex", messageIndex);
                item.put("globalSeq", globalSeq);
                item.put("eventCode", eventCode);
                item.put("target", "soak-target-" + globalSeq);
                items.add(item);
            }
            return items;
        }

        private void waitForTerminalTasks(MassSdkApplication app, List<SoakTaskPlan> taskPlans) throws Exception {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.drainTimeoutSeconds());
            Set<String> pending = new LinkedHashSet<>();
            for (SoakTaskPlan plan : taskPlans) {
                pending.add(plan.taskId());
            }
            while (!pending.isEmpty()) {
                require(System.nanoTime() < deadlineNanos,
                        "timed out before all soak tasks reached TERMINAL; pending=" + pending.size());
                pending.removeIf(taskId -> {
                    TaskStateSnapshot task = app.getTaskState(taskId);
                    return task != null && "TERMINAL".equals(task.getStatus());
                });
                if (!pending.isEmpty()) {
                    Thread.sleep(100L);
                }
            }
        }

        private FinalTaskStats collectFinalTaskStats(MassSdkApplication app, List<SoakTaskPlan> taskPlans) {
            Map<String, Long> terminalReasons = new LinkedHashMap<>();
            Map<String, Long> expectedTerminalReasons = new LinkedHashMap<>();
            long terminalTasks = 0;
            long expectedSuccess = 0;
            long expectedFailed = 0;
            for (SoakTaskPlan plan : taskPlans) {
                String taskId = plan.taskId();
                TaskStateSnapshot task = app.getTaskState(taskId);
                require(task != null, "task should exist: " + taskId);
                require("TERMINAL".equals(task.getStatus()), "task should be terminal: " + taskId);
                terminalTasks++;
                String actualTerminalReason = task.getTerminalReason() == null ? "<null>" : task.getTerminalReason();
                require(plan.expectedTerminalReason().equals(actualTerminalReason),
                        "unexpected terminal reason for task=" + taskId
                                + " expected=" + plan.expectedTerminalReason()
                                + " actual=" + actualTerminalReason);
                terminalReasons.merge(actualTerminalReason, 1L, Long::sum);
                expectedTerminalReasons.merge(plan.expectedTerminalReason(), 1L, Long::sum);
                expectedSuccess += plan.expectedSuccess();
                expectedFailed += plan.expectedFailed();
            }
            return new FinalTaskStats(
                    terminalTasks,
                    terminalReasons,
                    expectedTerminalReasons,
                    expectedSuccess,
                    expectedFailed
            );
        }

        private FinalWorkStats collectFinalWorkStats(TaskWorkRuntime runtime, List<SoakTaskPlan> taskPlans) {
            long total = 0;
            long success = 0;
            long failed = 0;
            long expired = 0;
            long activeLeases = 0;
            for (SoakTaskPlan plan : taskPlans) {
                String taskId = plan.taskId();
                TaskWorkStats stats = runtime.stats(taskId);
                require(stats.totalCount() == config.messagesPerTask(),
                        "unexpected runtime work count for task=" + taskId + " total=" + stats.totalCount());
                require(stats.finalCount() == stats.totalCount(),
                        "runtime work should be final for task=" + taskId);
                require(stats.successCount() == plan.expectedSuccess(),
                        "unexpected runtime success count for task=" + taskId
                                + " expected=" + plan.expectedSuccess()
                                + " actual=" + stats.successCount());
                require(stats.failedCount() == plan.expectedFailed(),
                        "unexpected runtime failed count for task=" + taskId
                                + " expected=" + plan.expectedFailed()
                                + " actual=" + stats.failedCount());
                total += stats.totalCount();
                success += stats.successCount();
                failed += stats.failedCount();
                expired += stats.expiredCount();
                activeLeases += runtime.activeLeases(taskId).size();
            }
            return new FinalWorkStats(total, success, failed, expired, activeLeases);
        }

        private ResultReadStats verifyResultReads(MassSdkApplication app, List<SoakTaskPlan> taskPlans) {
            long totalResults = 0;
            long totalPages = 0;
            long maxLastSeq = 0;
            for (SoakTaskPlan plan : taskPlans) {
                ResultSequentialReadVerifier.ResultSequentialReadSummary summary =
                        ResultSequentialReadVerifier.verify(
                                plan.taskId(),
                                config.messagesPerTask(),
                                config.resultWindowLimit(),
                                app::readTaskResults
                        );
                totalResults += summary.itemCount();
                totalPages += summary.pages();
                maxLastSeq = Math.max(maxLastSeq, summary.lastSeq());
            }
            return new ResultReadStats(totalResults, totalPages, maxLastSeq);
        }

        private Map<String, Object> collectDeliveryDiagnostics(MassSdkApplication app) {
            RuntimeDiagnosticsOperations diagnostics = app.runtimeDiagnostics();
            return diagnostics == null ? Map.of("available", false) : diagnostics.getQueueDetail();
        }

        private TraceProof verifyTrace(JsonlExecutionEventSink traceSink,
                                       Path traceDir,
                                       List<SoakTaskPlan> taskPlans,
                                       WorkerLifecycleStats workerLifecycle) throws Exception {
            if (!config.traceEnabled()) {
                return new TraceProof(false, null, null, null, 0L, List.of());
            }
            long dropped = traceSink == null ? 0L : traceSink.getDroppedCount();
            TraceOperatorService traceOperator = new TraceOperatorService();
            TraceValidateResponse validate = traceOperator.validate(new TraceValidateRequest(traceDir.toString()));
            TraceStatsResponse stats = traceOperator.stats(new TraceStatsRequest(traceDir.toString(), null, null, null, 100));
            List<TraceAnalyzeResponse> analyses = new ArrayList<>();
            for (SoakTraceAnalysisPlanner.TraceAnalysisPlan plan : SoakTraceAnalysisPlanner.plan(
                    taskPlans.stream()
                            .map(taskPlan -> new SoakTraceAnalysisPlanner.SoakTaskPlanRef(
                                    taskPlan.taskId(),
                                    taskPlan.expectedTerminalReason()))
                            .toList(),
                    config.requireLateWorkerWork(),
                    workerLifecycle.lateWorkerProofTaskId(),
                    workerLifecycle.lateWorkerProofWorkerId())) {
                analyses.add(traceOperator.analyze(new TraceAnalyzeRequest(
                        traceDir.toString(),
                        plan.scenarioId(),
                        plan.sourceId()
                )));
            }
            return new TraceProof(true, traceDir.toString(), validate, stats, dropped, List.copyOf(analyses));
        }

        private Map<String, Object> buildReport(long submittedTasks,
                                                FinalTaskStats taskStats,
                                                FinalWorkStats workStats,
                                                ResultReadStats resultReadStats,
                                                Map<String, Object> deliveryDiagnostics,
                                                SoakTraceProof traceProof,
                                                WorkerLifecycleStats workerLifecycle,
                                                SoakInvariantReport invariantReport,
                                                long wallNanos) {
            SoakProofBundle proof = new SoakProofBundle(
                    invariantReport,
                    resultReadStats.toMap(),
                    metrics.snapshot().toMap(),
                    workerLifecycle.toMap(),
                    deliveryDiagnostics,
                    traceProof,
                    List.copyOf(failures)
            );
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("runId", runId);
            report.put("config", config.toMap());
            report.put("duration", Map.of(
                    "wallClockMillis", nanosToMillis(wallNanos),
                    "startedAt", RUN_ID_TS.format(Instant.now().minusNanos(wallNanos)),
                    "finishedAt", RUN_ID_TS.format(Instant.now())
            ));
            report.put("tasksSubmitted", submittedTasks);
            report.put("tasksTerminal", taskStats.terminalTasks());
            report.put("terminalReasons", taskStats.terminalReasons());
            report.put("expectedTerminalReasons", taskStats.expectedTerminalReasons());
            report.put("workItemsSubmitted", submittedTasks * config.messagesPerTask());
            report.put("resultsVisible", resultReadStats.totalResults());
            report.put("success", workStats.successWorkItems());
            report.put("failed", workStats.failedWorkItems());
            report.put("expired", workStats.expiredWorkItems());
            report.put("expectedWork", Map.of(
                    "success", taskStats.expectedSuccessWorkItems(),
                    "failed", taskStats.expectedFailedWorkItems()
            ));
            report.put("runtimeWork", workStats.toMap());
            report.put("activeLeasesAtEnd", workStats.activeLeasesAtEnd());
            report.put("proof", proof.toMap());
            return report;
        }

        private SoakInvariantReport verifyInvariants(long submittedTasks,
                                                     FinalTaskStats taskStats,
                                                     FinalWorkStats workStats,
                                                     ResultReadStats resultReadStats,
                                                     TraceProof traceProof,
                                                     WorkerLifecycleStats workerLifecycle) {
            long expectedWorkItems = submittedTasks * config.messagesPerTask();
            return SoakRuntimeInvariantChecker.verify(new SoakRuntimeInvariantChecker.Snapshot(
                    submittedTasks,
                    taskStats.terminalTasks(),
                    expectedWorkItems,
                    workStats.totalWorkItems(),
                    resultReadStats.totalResults(),
                    taskStats.expectedSuccessWorkItems(),
                    workStats.successWorkItems(),
                    taskStats.expectedFailedWorkItems(),
                    workStats.failedWorkItems(),
                    workStats.activeLeasesAtEnd(),
                    traceProof.enabled(),
                    traceProof.validation() != null && traceProof.validation().valid(),
                    traceProof.droppedCount(),
                    traceProof.analysesOk(),
                    config.requireLateWorkerWork(),
                    workerLifecycle.lateWorkerReceivedItems(),
                    workerLifecycle.lateWorkerResultSubmissions(),
                    failures.size()
            ));
        }

        private void assertSoakPassed(SoakInvariantReport invariantReport) {
            require(invariantReport.ok(), "soak runtime invariants failed: " + invariantReport.failureMessage());
        }

        private WorkerLifecycleStats workerLifecycleStats() {
            SoakMetricsSnapshot snapshot = metrics.snapshot();
            long lateWorkerReceivedItems = 0;
            long lateWorkerResultSubmissions = 0;
            String lateWorkerProofTaskId = null;
            String lateWorkerProofWorkerId = null;
            for (int i = config.initialWorkerCount(); i < config.workerCount(); i++) {
                String workerId = workerId(i);
                WorkerMetricsSnapshot worker = snapshot.byWorker().get(workerId);
                if (worker == null) {
                    continue;
                }
                lateWorkerReceivedItems += worker.receivedItems();
                lateWorkerResultSubmissions += worker.resultSubmissions();
                if (lateWorkerProofTaskId == null && present(worker.firstTaskId())) {
                    lateWorkerProofTaskId = worker.firstTaskId();
                    lateWorkerProofWorkerId = workerId;
                }
            }
            return new WorkerLifecycleStats(
                    config.initialWorkerCount(),
                    config.workerCount() - config.initialWorkerCount(),
                    config.lateWorkerStartAfterMillis(),
                    lateWorkerReceivedItems,
                    lateWorkerResultSubmissions,
                    lateWorkerProofTaskId,
                    lateWorkerProofWorkerId
            );
        }

        private void closeWorkers(List<SimulatedPollingWorker> workers) {
            for (SimulatedPollingWorker worker : workers) {
                try {
                    worker.close();
                } catch (Exception e) {
                    failures.add("failed to close worker " + worker.workerId() + ": " + e.getMessage());
                }
            }
        }
    }

    private static final class SimulatedPollingWorker implements AutoCloseable {
        private final String workerId;
        private final PullWorkerSession session;
        private final SoakConfig config;
        private final SoakMetrics metrics;
        private final AtomicBoolean stopRequested;
        private final List<String> failures;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ExecutorService processingExecutor;
        private Thread pollThread;

        private SimulatedPollingWorker(String workerId,
                                       PullWorkerSession session,
                                       SoakConfig config,
                                       SoakMetrics metrics,
                                       AtomicBoolean stopRequested,
                                       List<String> failures) {
            this.workerId = workerId;
            this.session = session;
            this.config = config;
            this.metrics = metrics;
            this.stopRequested = stopRequested;
            this.failures = failures;
            this.processingExecutor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("soak-process-" + workerId + "-", 0).factory()
            );
        }

        private String workerId() {
            return workerId;
        }

        private void start(String connectReason) {
            session.connect(connectReason);
            pollThread = Thread.ofVirtual()
                    .name("soak-poll-" + workerId)
                    .start(this::runLoop);
        }

        private void runLoop() {
            while (running.get()) {
                try {
                    List<TaskDispatchItem> items = session.poll(config.pollBatchSize());
                    metrics.recordPoll(workerId, items == null ? 0 : items.size());
                    if (items == null || items.isEmpty()) {
                        if (stopRequested.get()) {
                            return;
                        }
                        if (config.emptyPollBackoffMillis() > 0) {
                            Thread.sleep(config.emptyPollBackoffMillis());
                        }
                        continue;
                    }
                    for (TaskDispatchItem item : items) {
                        metrics.recordReceivedItem(workerId, item.getTaskId());
                        processingExecutor.submit(() -> process(item));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    failures.add("worker " + workerId + " poll failed: " + e.getMessage());
                }
            }
        }

        private void process(TaskDispatchItem item) {
            long started = System.nanoTime();
            metrics.beginProcessing();
            try {
                int delay = config.processingDelayMillis();
                if (config.processingJitterMillis() > 0) {
                    delay += ThreadLocalRandom.current().nextInt(config.processingJitterMillis() + 1);
                }
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                long globalSeq = globalSeq(item);
                boolean success = isExpectedSuccess(globalSeq, config.failureEveryNth());
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("workerId", workerId);
                output.put("messageId", item.getMessageId());
                output.put("globalSeq", globalSeq);
                output.put("success", success);
                boolean accepted = session.submitResult(
                        item,
                        success,
                        success ? "polling-soak-success" : "polling-soak-failure",
                        success ? null : "SOAK_SYNTHETIC_FAILURE",
                        output
                );
                if (!accepted) {
                    failures.add("result rejected worker=" + workerId
                            + " taskId=" + item.getTaskId() + " messageId=" + item.getMessageId());
                }
                metrics.recordResult(workerId, success, System.nanoTime() - started);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                failures.add("worker " + workerId + " process failed messageId="
                        + item.getMessageId() + ": " + e.getMessage());
            } finally {
                metrics.endProcessing();
            }
        }

        private long globalSeq(TaskDispatchItem item) {
            Object value = item.getInput().get("globalSeq");
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Math.abs((long) item.getMessageId().hashCode());
        }

        @Override
        public void close() throws Exception {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            if (pollThread != null) {
                pollThread.interrupt();
                pollThread.join(TimeUnit.SECONDS.toMillis(5));
            }
            processingExecutor.shutdown();
            if (!processingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
            }
            session.disconnect("polling-soak-stop");
        }
    }

    private static List<String> buildEventCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add("soak.dispatch." + i);
        }
        return List.copyOf(codes);
    }

    private static String workerId(int index) {
        return "soak-worker-" + index;
    }

    private static String groupId(int index) {
        return "soak-group-" + index;
    }

    private static long globalSeq(int taskIndex, int messageIndex, int messagesPerTask) {
        return (long) taskIndex * messagesPerTask + messageIndex + 1L;
    }

    private static boolean isExpectedSuccess(long globalSeq, int failureEveryNth) {
        return failureEveryNth <= 0 || globalSeq % failureEveryNth != 0;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record EmbeddedRuntime(MassSdkApplication app, TaskWorkRuntime taskWorkRuntime) {
    }

    private record SoakTaskPlan(String taskId,
                                int taskIndex,
                                String eventCode,
                                int expectedSuccess,
                                int expectedFailed,
                                String expectedTerminalReason) {
    }

    private record FinalTaskStats(long terminalTasks,
                                  Map<String, Long> terminalReasons,
                                  Map<String, Long> expectedTerminalReasons,
                                  long expectedSuccessWorkItems,
                                  long expectedFailedWorkItems) {
    }

    private record FinalWorkStats(long totalWorkItems,
                                  long successWorkItems,
                                  long failedWorkItems,
                                  long expiredWorkItems,
                                  long activeLeasesAtEnd) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "totalWorkItems", totalWorkItems,
                    "successWorkItems", successWorkItems,
                    "failedWorkItems", failedWorkItems,
                    "expiredWorkItems", expiredWorkItems,
                    "activeLeasesAtEnd", activeLeasesAtEnd
            );
        }
    }

    private record ResultReadStats(long totalResults, long totalPages, long maxLastSeq) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "totalResults", totalResults,
                    "totalPages", totalPages,
                    "maxLastSeq", maxLastSeq
            );
        }
    }

    private record TraceProof(boolean enabled,
                              String path,
                              TraceValidateResponse validation,
                              TraceStatsResponse stats,
                              long droppedCount,
                              List<TraceAnalyzeResponse> analyses) {
        private SoakTraceProof toSummary() {
            return new SoakTraceProof(enabled, path, validation, stats, droppedCount, analyses);
        }

        private boolean analysesOk() {
            return analyses == null || analyses.stream().allMatch(TraceAnalyzeResponse::ok);
        }
    }

    private record WorkerLifecycleStats(int initialWorkerCount,
                                        int lateWorkerCount,
                                        int lateWorkerStartAfterMillis,
                                        long lateWorkerReceivedItems,
                                        long lateWorkerResultSubmissions,
                                        String lateWorkerProofTaskId,
                                        String lateWorkerProofWorkerId) {
        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("initialWorkerCount", initialWorkerCount);
            values.put("lateWorkerCount", lateWorkerCount);
            values.put("lateWorkerStartAfterMillis", lateWorkerStartAfterMillis);
            values.put("lateWorkerReceivedItems", lateWorkerReceivedItems);
            values.put("lateWorkerResultSubmissions", lateWorkerResultSubmissions);
            values.put("lateWorkerProofTaskId", lateWorkerProofTaskId);
            values.put("lateWorkerProofWorkerId", lateWorkerProofWorkerId);
            return values;
        }
    }

    private static final class SoakMetrics {
        private final LongAdder pollCycles = new LongAdder();
        private final LongAdder emptyPollCycles = new LongAdder();
        private final LongAdder receivedItems = new LongAdder();
        private final LongAdder resultSubmissions = new LongAdder();
        private final LongAdder failedResults = new LongAdder();
        private final LongAdder totalProcessingNanos = new LongAdder();
        private final AtomicInteger activeProcessing = new AtomicInteger();
        private final LongAccumulator maxReceivedBatch = new LongAccumulator(Long::max, 0);
        private final LongAccumulator maxConcurrentProcessing = new LongAccumulator(Long::max, 0);
        private final ConcurrentHashMap<String, WorkerMetrics> byWorker = new ConcurrentHashMap<>();

        private void recordPoll(String workerId, int batchSize) {
            pollCycles.increment();
            byWorker(workerId).recordPoll(batchSize);
            if (batchSize <= 0) {
                emptyPollCycles.increment();
                return;
            }
            receivedItems.add(batchSize);
            maxReceivedBatch.accumulate(batchSize);
        }

        private void recordReceivedItem(String workerId, String taskId) {
            byWorker(workerId).recordReceivedItem(taskId);
        }

        private void beginProcessing() {
            int active = activeProcessing.incrementAndGet();
            maxConcurrentProcessing.accumulate(active);
        }

        private void endProcessing() {
            activeProcessing.decrementAndGet();
        }

        private void recordResult(String workerId, boolean success, long processingNanos) {
            resultSubmissions.increment();
            byWorker(workerId).recordResult(success, processingNanos);
            if (!success) {
                failedResults.increment();
            }
            totalProcessingNanos.add(processingNanos);
        }

        private WorkerMetrics byWorker(String workerId) {
            return byWorker.computeIfAbsent(workerId, ignored -> new WorkerMetrics());
        }

        private SoakMetricsSnapshot snapshot() {
            Map<String, WorkerMetricsSnapshot> workerSnapshots = new LinkedHashMap<>();
            byWorker.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> workerSnapshots.put(entry.getKey(), entry.getValue().snapshot()));
            return new SoakMetricsSnapshot(
                    pollCycles.sum(),
                    emptyPollCycles.sum(),
                    receivedItems.sum(),
                    resultSubmissions.sum(),
                    failedResults.sum(),
                    maxReceivedBatch.get(),
                    maxConcurrentProcessing.get(),
                    nanosToMillis(totalProcessingNanos.sum()),
                    Map.copyOf(workerSnapshots)
            );
        }
    }

    private static final class WorkerMetrics {
        private final LongAdder pollCycles = new LongAdder();
        private final LongAdder emptyPollCycles = new LongAdder();
        private final LongAdder receivedItems = new LongAdder();
        private final LongAdder resultSubmissions = new LongAdder();
        private final LongAdder failedResults = new LongAdder();
        private final LongAdder totalProcessingNanos = new LongAdder();
        private final LongAccumulator maxReceivedBatch = new LongAccumulator(Long::max, 0);
        private final AtomicReference<String> firstTaskId = new AtomicReference<>();

        private void recordPoll(int batchSize) {
            pollCycles.increment();
            if (batchSize <= 0) {
                emptyPollCycles.increment();
                return;
            }
            receivedItems.add(batchSize);
            maxReceivedBatch.accumulate(batchSize);
        }

        private void recordResult(boolean success, long processingNanos) {
            resultSubmissions.increment();
            if (!success) {
                failedResults.increment();
            }
            totalProcessingNanos.add(processingNanos);
        }

        private void recordReceivedItem(String taskId) {
            if (taskId != null && !taskId.isBlank()) {
                firstTaskId.compareAndSet(null, taskId);
            }
        }

        private WorkerMetricsSnapshot snapshot() {
            return new WorkerMetricsSnapshot(
                    pollCycles.sum(),
                    emptyPollCycles.sum(),
                    receivedItems.sum(),
                    resultSubmissions.sum(),
                    failedResults.sum(),
                    maxReceivedBatch.get(),
                    nanosToMillis(totalProcessingNanos.sum()),
                    firstTaskId.get()
            );
        }
    }

    private record SoakMetricsSnapshot(long pollCycles,
                                       long emptyPollCycles,
                                       long receivedItems,
                                       long resultSubmissions,
                                       long failedResults,
                                       long maxReceivedBatch,
                                       long maxConcurrentProcessing,
                                       double totalProcessingMillis,
                                       Map<String, WorkerMetricsSnapshot> byWorker) {
        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("pollCycles", pollCycles);
            values.put("emptyPollCycles", emptyPollCycles);
            values.put("receivedItems", receivedItems);
            values.put("resultSubmissions", resultSubmissions);
            values.put("failedResults", failedResults);
            values.put("maxReceivedBatch", maxReceivedBatch);
            values.put("maxConcurrentProcessing", maxConcurrentProcessing);
            values.put("totalProcessingMillis", totalProcessingMillis);
            values.put("byWorker", byWorker);
            return values;
        }
    }

    private record WorkerMetricsSnapshot(long pollCycles,
                                         long emptyPollCycles,
                                         long receivedItems,
                                         long resultSubmissions,
                                         long failedResults,
                                         long maxReceivedBatch,
                                         double totalProcessingMillis,
                                         String firstTaskId) {
    }

    private record SoakReport(String runId,
                              long tasksSubmitted,
                              long tasksTerminal,
                              long workItems,
                              long visibleResults,
                              long activeLeasesAtEnd,
                              long traceDropped,
                              Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkPollingSchedulingSoak runId=%s tasks=%d terminal=%d workItems=%d visibleResults=%d "
                            + "activeLeases=%d traceDropped=%d report=%s",
                    runId,
                    tasksSubmitted,
                    tasksTerminal,
                    workItems,
                    visibleResults,
                    activeLeasesAtEnd,
                    traceDropped,
                    reportPath
            );
        }
    }
}
