package com.xa.mass.testing.chaos;

import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.testing.chaos.support.ChaosTraceArtifacts;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import com.xa.mass.testing.chaos.support.TraceEventAssertions;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.sdk.worker.PulledTaskDispatch;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaos probe: all messages fail with no retries, verifying the
 * {@code ALL_MESSAGES_FAILED} terminal convergence path.
 *
 * <p>Scenario:
 * <ol>
 *   <li>Create a sealed task with {@code MESSAGE_COUNT} messages, {@code maxRetryCount=0}.</li>
 *   <li>Start one polling worker that always submits failure.</li>
 *   <li>Wait for the task to reach {@code TERMINAL}.</li>
 *   <li>Assert runtime counters and trace prove every work item failed, and the
 *       task {@code terminalReason=ALL_MESSAGES_FAILED}.</li>
 * </ol>
 */
public final class SdkPollingAllMessagesFailedChaosRunner {

    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String WORKER_ID = "sdk-all-failed-chaos-worker-0";
    private static final int MESSAGE_COUNT = 3;

    private SdkPollingAllMessagesFailedChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK polling all-messages-failed chaos report written to: " + report.reportPath());
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
                    "sdk-polling-all-messages-failed-chaos");
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createPolling(
                    new ChaosRuntimeHarness.PollingRuntimeConfig(
                            "sdk-all-failed-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    ),
                    traceArtifacts
            );
            AlwaysFailWorkerDriver worker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime.start();
                runtime.registerPollingWorker(WORKER_ID, "sdk-all-failed-chaos", PROJECT_CODE, ROUTING_CODE);

                worker = new AlwaysFailWorkerDriver(WORKER_ID, runtime.pullWorker(WORKER_ID), config);
                AlwaysFailWorkerDriver activeWorker = worker;

                worker.start();
                runtime.waitForWorkerOnline(
                        WORKER_ID,
                        config.timeoutSeconds(),
                        "all-failed chaos worker should be online before task creation"
                );

                TaskShellSnapshot task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.multiMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-polling-all-messages-failed",
                        ROUTING_CODE,
                        MESSAGE_COUNT,
                        1,
                        0,
                        config.timeoutSeconds()
                ));

                ChaosSupport.waitForCondition(
                        () -> activeWorker.failedResults() >= MESSAGE_COUNT,
                        config.timeoutSeconds(),
                        "all-failed chaos worker should submit " + MESSAGE_COUNT + " failure results"
                );

                TaskOutcomeSnapshot outcome = runtime.waitForTerminalTask(
                        task.getTaskId(),
                        MESSAGE_COUNT,
                        config.timeoutSeconds(),
                        "all-messages-failed task must converge to TERMINAL"
                );
                TaskWorkStats finalStats = runtime.waitForRuntimeStats(
                        task.getTaskId(),
                        MESSAGE_COUNT,
                        0,
                        MESSAGE_COUNT,
                        0,
                        config.timeoutSeconds(),
                        "runtime should finalize all work items as failed"
                );
                ChaosSupport.require(finalStats.readyCount() == 0, "runtime ready queue should be drained");
                ChaosSupport.require(finalStats.inflightCount() == 0, "runtime leases should be drained");
                ChaosSupport.require(finalStats.delayedCount() == 0, "runtime delayed queue should be drained");
                ChaosSupport.require(runtime.activeLeases(task.getTaskId()).isEmpty(),
                        "runtime active leases should be empty after terminal failure");

                ChaosSupport.require("TERMINAL".equals(outcome.status()),
                        "task should converge to TERMINAL");
                ChaosSupport.require("ALL_MESSAGES_FAILED".equals(outcome.terminalReason()),
                        "task terminalReason should be ALL_MESSAGES_FAILED, got " + outcome.terminalReason());

                TraceEventAssertions.of(traceArtifacts.captureSink())
                        .forTask(task.getTaskId())
                        .requireMinTotalEvents(5)
                        .requireEventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                        .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                        .requireTerminalReason("ALL_MESSAGES_FAILED")
                        .requireEventType(ExecutionEventType.TASK_WORK_STATUS_TRANSITION)
                        .requireMessageStatusTransitions("FAILED", MESSAGE_COUNT)
                        .requireEventType(ExecutionEventType.CALLBACK_ACCEPTED);
                traceArtifacts.close();
                List<TraceAnalyzeResponse> analyses = ChaosTraceAnalysisPlanner.analyze(
                        traceArtifacts.outputDir(),
                        ChaosTraceAnalysisPlanner.ChaosProofProfile.ALL_FAILED_TERMINAL_CONVERGENCE,
                        task.getTaskId(),
                        traceArtifacts.droppedCount()
                );
                ChaosTraceAnalysisPlanner.requireAllOk(analyses);

                Path reportPath = ChaosReportWriter.write("sdk-polling-all-messages-failed-chaos",
                        WorkerFaultReportMetadata.merge(
                                WorkerFaultScenarioIndex.Scenario.POLLING_ALL_FAILED_TERMINAL_CONVERGENCE,
                                Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of("transport", "polling", "adapterId", "polling"),
                        "wallClock", Map.of("totalMillis",
                                ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                        "trace", Map.of(
                                "summary", TraceEventAssertions.of(traceArtifacts.captureSink()).summaryMap(task.getTaskId()),
                                "jsonlPath", traceArtifacts.outputDir().toString(),
                                "droppedCount", traceArtifacts.droppedCount(),
                                "analyses", analyses.stream()
                                        .map(SdkPollingAllMessagesFailedChaosRunner::analysisMap)
                                        .toList()
                        ),
                        "task", outcome.toMap(),
                        "workers", Map.of("worker", worker.snapshot().toMap())
                )));

                return new ChaosReport(
                        task.getTaskId(),
                        MESSAGE_COUNT,
                        worker.failedResults(),
                        outcome.terminalReason(),
                        ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos),
                        reportPath
                );
            } finally {
                closeQuietly(traceArtifacts);
                closeQuietly(worker);
                runtime.close();
            }
        }
    }

    private static Map<String, Object> analysisMap(TraceAnalyzeResponse response) {
        return Map.of(
                "scenarioId", response.scenarioId(),
                "source", response.source(),
                "ok", response.ok(),
                "eventCount", response.eventCount(),
                "issues", response.issues().stream()
                        .map(issue -> Map.<String, Object>of(
                                "code", issue.code(),
                                "message", issue.message()
                        ))
                        .toList()
        );
    }

    private static final class AlwaysFailWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final PullWorkerSession session;
        private final ChaosConfig config;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private final AtomicBoolean closeRequested = new AtomicBoolean(false);
        private final AtomicInteger pollCycles = new AtomicInteger();
        private final AtomicInteger emptyPollCycles = new AtomicInteger();
        private final AtomicInteger receivedDispatches = new AtomicInteger();
        private final AtomicInteger failedResults = new AtomicInteger();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Thread pollThread;

        private AlwaysFailWorkerDriver(String workerId, PullWorkerSession session, ChaosConfig config) {
            this.workerId = workerId;
            this.session = session;
            this.config = config;
        }

        private void start() {
            session.connect("sdk-all-failed-chaos-start");
            connected.set(true);
            running.set(true);
            pollThread = new Thread(this::runLoop, "SdkAllFailedChaosWorker-" + workerId);
            pollThread.setDaemon(true);
            pollThread.start();
        }

        private int failedResults() {
            return failedResults.get();
        }

        private WorkerRuntimeSnapshot snapshot() {
            return new WorkerRuntimeSnapshot(workerId, pollCycles.get(),
                    emptyPollCycles.get(), receivedDispatches.get(), failedResults.get());
        }

        private void runLoop() {
            try {
                while (running.get()) {
                    List<PulledTaskDispatch> items = session.poll(1, 0L);
                    pollCycles.incrementAndGet();
                    if (items == null || items.isEmpty()) {
                        emptyPollCycles.incrementAndGet();
                        Thread.sleep(20L);
                        continue;
                    }
                    for (PulledTaskDispatch item : items) {
                        receivedDispatches.incrementAndGet();
                        ChaosSupport.maybeSleep(config.processingDelayMillis());
                        boolean accepted = session.submitResult(
                                item,
                                false,
                                "chaos-always-fail",
                                Map.of("workerId", workerId, "forcedFailure", true)
                        );
                        ChaosSupport.require(accepted,
                                "failure result submission should be accepted for worker " + workerId);
                        failedResults.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
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
                    session.disconnect("sdk-all-failed-chaos-stop");
                }
            }
        }
    }

    private record WorkerRuntimeSnapshot(String workerId,
                                          int pollCycles,
                                          int emptyPollCycles,
                                          int receivedDispatches,
                                          int failedResults) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workerId", workerId);
            map.put("pollCycles", pollCycles);
            map.put("emptyPollCycles", emptyPollCycles);
            map.put("receivedDispatches", receivedDispatches);
            map.put("failedResults", failedResults);
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
                    ChaosSupport.longProperty("mass.sdk.chaos.taskMessageLeaseSeconds", 10L),
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
                                int messageCount,
                                int failedResults,
                                String terminalReason,
                                double wallClockMillis,
                                Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkPollingAllMessagesFailed task=%s messages=%d failedResults=%d "
                            + "terminalReason=%s wall=%.3fms report=%s",
                    taskId, messageCount, failedResults, terminalReason, wallClockMillis, reportPath
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
}
