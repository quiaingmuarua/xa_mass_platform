package com.xa.mass.testing.chaos;

import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.testing.chaos.support.ChaosProofAssertions;
import com.xa.mass.testing.chaos.support.ChaosTraceArtifacts;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import com.xa.mass.testing.chaos.support.TraceEventAssertions;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SdkPollingLeaseExpiryRedispatchChaosRunner {

    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String CHAOS_WORKER_ID = "sdk-polling-chaos-worker-0";
    private static final String STEADY_WORKER_ID = "sdk-polling-chaos-worker-1";

    private SdkPollingLeaseExpiryRedispatchChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK polling lease-expiry chaos report written to: " + report.reportPath());
        } catch (Throwable t) {
            exitCode = 1;
            throw t;
        } finally {
            if (ChaosSupport.booleanProperty("mass.sdk.chaos.forceExit", true)) {
                System.exit(exitCode);
            }
        }
    }

    private static final class ScenarioRunner {
        private final ChaosConfig config;

        private ScenarioRunner(ChaosConfig config) {
            this.config = config;
        }

        private ChaosReport run() throws Exception {
            ChaosTraceArtifacts traceArtifacts = ChaosTraceArtifacts.create(
                    "sdk-polling-lease-expiry-redispatch-chaos");
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createPolling(
                    new ChaosRuntimeHarness.PollingRuntimeConfig(
                            "sdk-polling-lease-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    ),
                    traceArtifacts
            );
            PollingWorkerDriver chaosWorker = null;
            PollingWorkerDriver steadyWorker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime.start();
                runtime.registerPollingWorker(CHAOS_WORKER_ID, "sdk-polling-chaos", PROJECT_CODE, ROUTING_CODE);
                runtime.registerPollingWorker(STEADY_WORKER_ID, "sdk-polling-chaos", PROJECT_CODE, ROUTING_CODE);

                chaosWorker = new PollingWorkerDriver(
                        CHAOS_WORKER_ID,
                        runtime.pullWorker(CHAOS_WORKER_ID),
                        config,
                        WorkerMode.STALL_WITHOUT_RESULT
                );
                PollingWorkerDriver activeChaosWorker = chaosWorker;
                steadyWorker = new PollingWorkerDriver(
                        STEADY_WORKER_ID,
                        runtime.pullWorker(STEADY_WORKER_ID),
                        config,
                        WorkerMode.NORMAL
                );

                chaosWorker.start();
                runtime.waitForWorkerOnline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "chaos polling worker should be online before scenario starts"
                );

                TaskShellSnapshot task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.singleMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-polling-chaos-lease-expiry-redispatch",
                        ROUTING_CODE,
                        1,
                        config.timeoutSeconds(),
                        Map.of("source", "SdkPollingLeaseExpiryRedispatchChaosRunner")
                ));

                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.stalledDispatches() >= 1,
                        config.timeoutSeconds(),
                        "chaos polling worker should claim one dispatch and stall without a result"
                );
                String messageId = activeChaosWorker.stalledMessageId();
                ChaosSupport.require(messageId != null && !messageId.isBlank(),
                        "chaos polling worker should retain the stalled runtime message id");
                runtime.waitForActiveAttemptOnWorker(
                        task.getTaskId(),
                        messageId,
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "first active attempt should stay bound to the polling chaos worker before lease expiry"
                );

                chaosWorker.disconnect();
                runtime.waitForWorkerOffline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "runtime should observe the chaos polling worker offline after disconnect"
                );

                steadyWorker.start();
                runtime.waitForWorkerOnline(
                        STEADY_WORKER_ID,
                        config.timeoutSeconds(),
                        "steady polling worker should come online before redispatch"
                );

                runtime.waitForAttemptCount(
                        task.getTaskId(),
                        messageId,
                        2,
                        config.timeoutSeconds(),
                        "second attempt should appear after watchdog expiry and polling redispatch"
                );

                ChaosProofAssertions.TerminalRuntimeProof proof = ChaosProofAssertions.requireSuccessfulTerminalRuntime(
                        runtime,
                        task.getTaskId(),
                        messageId,
                        1,
                        config.timeoutSeconds(),
                        "polling lease-expiry redispatch"
                );
                TaskOutcomeSnapshot outcome = proof.outcome();
                var finalReceipt = proof.finalReceipt();
                ChaosProofAssertions.requireLeaseExpirySuccessTrace(traceArtifacts.captureSink(), task.getTaskId());
                traceArtifacts.close();
                List<TraceAnalyzeResponse> analyses = ChaosTraceAnalysisPlanner.analyze(
                        traceArtifacts.outputDir(),
                        ChaosTraceAnalysisPlanner.ChaosProofProfile.LEASE_EXPIRY_REDISPATCH,
                        task.getTaskId()
                );
                ChaosTraceAnalysisPlanner.requireAllOk(analyses);

                Path reportPath = ChaosReportWriter.write("sdk-polling-lease-expiry-redispatch-chaos",
                        WorkerFaultReportMetadata.merge(
                                WorkerFaultScenarioIndex.Scenario.POLLING_LEASE_EXPIRY_REDISPATCH,
                                Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of(
                                "transport", "polling",
                                "adapterId", "polling"
                        ),
                        "wallClock", Map.of("totalMillis", ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                        "leaseWindow", Map.of(
                                "taskMessageLeaseSeconds", config.taskMessageLeaseSeconds(),
                                "finalReceiptRetryCount", finalReceipt.retryCount()
                        ),
                        "trace", Map.of(
                                "summary", TraceEventAssertions.of(traceArtifacts.captureSink()).summaryMap(task.getTaskId()),
                                "jsonlPath", traceArtifacts.outputDir().toString(),
                                "droppedCount", traceArtifacts.droppedCount(),
                                "analyses", analyses.stream()
                                        .map(SdkPollingLeaseExpiryRedispatchChaosRunner::analysisMap)
                                        .toList()
                        ),
                        "task", outcome.toMap(),
                        "workers", Map.of(
                                "chaosWorker", chaosWorker.snapshot().toMap(),
                                "steadyWorker", steadyWorker.snapshot().toMap()
                        )
                )));

                return new ChaosReport(
                        task.getTaskId(),
                        messageId,
                        chaosWorker.stalledDispatches(),
                        steadyWorker.successfulResults(),
                        finalReceipt.retryCount() + 1,
                        finalReceipt.retryCount(),
                        outcome.terminalReason(),
                        ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos),
                        reportPath
                );
            } finally {
                closeQuietly(traceArtifacts);
                closeQuietly(chaosWorker);
                closeQuietly(steadyWorker);
                runtime.close();
            }
        }
    }

    private enum WorkerMode {
        NORMAL,
        STALL_WITHOUT_RESULT
    }

    private static final class PollingWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final PullWorkerSession session;
        private final ChaosConfig config;
        private final WorkerMode mode;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private final AtomicBoolean closeRequested = new AtomicBoolean(false);
        private final AtomicBoolean stallBudgetConsumed = new AtomicBoolean(false);
        private final AtomicInteger pollCycles = new AtomicInteger();
        private final AtomicInteger emptyPollCycles = new AtomicInteger();
        private final AtomicInteger receivedDispatches = new AtomicInteger();
        private final AtomicInteger stalledDispatches = new AtomicInteger();
        private final AtomicInteger successfulResults = new AtomicInteger();
        private final AtomicReference<TaskDispatchItem> stalledDispatch = new AtomicReference<>();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Thread pollThread;

        private PollingWorkerDriver(String workerId,
                                    PullWorkerSession session,
                                    ChaosConfig config,
                                    WorkerMode mode) {
            this.workerId = workerId;
            this.session = session;
            this.config = config;
            this.mode = mode;
        }

        private void start() {
            session.connect("sdk-polling-chaos-start");
            connected.set(true);
            running.set(true);
            pollThread = new Thread(this::runLoop, "SdkPollingChaosWorker-" + workerId);
            pollThread.setDaemon(true);
            pollThread.start();
        }

        private void disconnect() {
            if (connected.compareAndSet(true, false)) {
                session.disconnect("sdk-polling-chaos-disconnect");
            }
            running.set(false);
        }

        private int stalledDispatches() {
            return stalledDispatches.get();
        }

        private int successfulResults() {
            return successfulResults.get();
        }

        private String stalledMessageId() {
            TaskDispatchItem item = stalledDispatch.get();
            return item != null ? item.getMessageId() : null;
        }

        private WorkerRuntimeSnapshot snapshot() {
            return new WorkerRuntimeSnapshot(
                    workerId,
                    mode.name(),
                    pollCycles.get(),
                    emptyPollCycles.get(),
                    receivedDispatches.get(),
                    stalledDispatches.get(),
                    successfulResults.get(),
                    stalledDispatch.get() != null
            );
        }

        private void runLoop() {
            try {
                while (running.get()) {
                    List<TaskDispatchItem> items = session.poll(1, 0L);
                    pollCycles.incrementAndGet();
                    if (items == null || items.isEmpty()) {
                        emptyPollCycles.incrementAndGet();
                        Thread.sleep(20L);
                        continue;
                    }
                    for (TaskDispatchItem item : items) {
                        receivedDispatches.incrementAndGet();
                        if (mode == WorkerMode.STALL_WITHOUT_RESULT && stallBudgetConsumed.compareAndSet(false, true)) {
                            stalledDispatch.set(item);
                            stalledDispatches.incrementAndGet();
                            running.set(false);
                            return;
                        }
                        processNormally(item);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        }

        private void processNormally(TaskDispatchItem item) {
            ChaosSupport.maybeSleep(config.processingDelayMillis());
            boolean accepted = session.submitResult(
                    item,
                    true,
                    "ok",
                    Map.of(
                            "workerId", workerId,
                            "mode", mode.name(),
                            "receivedDispatches", receivedDispatches.get()
                    )
            );
            ChaosSupport.require(accepted, "polling result submission should be accepted for worker " + workerId);
            successfulResults.incrementAndGet();
        }

        @Override
        public void close() throws Exception {
            if (closeRequested.compareAndSet(false, true)) {
                running.set(false);
                if (pollThread != null) {
                    pollThread.interrupt();
                }
                stopped.await(5, TimeUnit.SECONDS);
                if (connected.compareAndSet(true, false)) {
                    session.disconnect("sdk-polling-chaos-stop");
                }
            }
        }
    }

    private record WorkerRuntimeSnapshot(String workerId,
                                         String mode,
                                         int pollCycles,
                                         int emptyPollCycles,
                                         int receivedDispatches,
                                         int stalledDispatches,
                                         int successfulResults,
                                         boolean retainedStalledDispatch) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workerId", workerId);
            map.put("mode", mode);
            map.put("pollCycles", pollCycles);
            map.put("emptyPollCycles", emptyPollCycles);
            map.put("receivedDispatches", receivedDispatches);
            map.put("stalledDispatches", stalledDispatches);
            map.put("successfulResults", successfulResults);
            map.put("retainedStalledDispatch", retainedStalledDispatch);
            return Map.copyOf(map);
        }
    }

    private record ChaosConfig(int processingDelayMillis,
                               long assignmentRetryDelayMillis,
                               long leaseWatchdogIntervalSeconds,
                               long taskMessageLeaseSeconds,
                               int timeoutSeconds) {
        private static ChaosConfig fromSystemProperties() {
            ChaosConfig config = new ChaosConfig(
                    ChaosSupport.intProperty("mass.sdk.chaos.processingDelayMillis", 25),
                    ChaosSupport.longProperty("mass.sdk.chaos.assignmentRetryDelayMillis", 100L),
                    ChaosSupport.longProperty("mass.sdk.chaos.leaseWatchdogIntervalSeconds", 1L),
                    ChaosSupport.longProperty("mass.sdk.chaos.taskMessageLeaseSeconds", 2L),
                    ChaosSupport.intProperty("mass.sdk.chaos.timeoutSeconds", 25)
            );
            ChaosSupport.require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            ChaosSupport.require(config.assignmentRetryDelayMillis > 0, "assignmentRetryDelayMillis must be positive");
            ChaosSupport.require(config.leaseWatchdogIntervalSeconds > 0, "leaseWatchdogIntervalSeconds must be positive");
            ChaosSupport.require(config.taskMessageLeaseSeconds > 0, "taskMessageLeaseSeconds must be positive");
            ChaosSupport.require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("processingDelayMillis", processingDelayMillis);
            map.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            map.put("leaseWatchdogIntervalSeconds", leaseWatchdogIntervalSeconds);
            map.put("taskMessageLeaseSeconds", taskMessageLeaseSeconds);
            map.put("timeoutSeconds", timeoutSeconds);
            return Map.copyOf(map);
        }
    }

    private record ChaosReport(String taskId,
                               String messageId,
                               int chaosStalledDispatches,
                               int steadySuccessfulResults,
                               int finalAttemptCount,
                               int finalRetryCount,
                               String terminalReason,
                               double wallClockMillis,
                               Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkPollingLeaseExpiryChaos task=%s message=%s stalledDispatches=%d steadyResults=%d "
                            + "attempts=%d retryCount=%d terminalReason=%s wall=%.3fms report=%s",
                    taskId,
                    messageId,
                    chaosStalledDispatches,
                    steadySuccessfulResults,
                    finalAttemptCount,
                    finalRetryCount,
                    terminalReason,
                    wallClockMillis,
                    reportPath
            );
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort shutdown only.
        }
    }

    private static Map<String, Object> analysisMap(TraceAnalyzeResponse response) {
        return Map.of(
                "scenarioId", response.scenarioId(),
                "sourceId", response.taskId(),
                "ok", response.ok(),
                "eventCount", response.eventCount(),
                "eventTypeCounts", response.eventTypeCounts(),
                "issues", response.issues().stream()
                        .map(issue -> Map.of("code", issue.code(), "message", issue.message()))
                        .toList()
        );
    }
}
