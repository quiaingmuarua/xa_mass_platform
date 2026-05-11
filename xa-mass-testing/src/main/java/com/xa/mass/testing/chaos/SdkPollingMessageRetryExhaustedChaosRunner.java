package com.xa.mass.testing.chaos;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.testing.chaos.support.CapturingExecutionEventSink;
import com.xa.mass.testing.chaos.support.CompatibilityAttemptView;
import com.xa.mass.testing.chaos.support.CompatibilityMessageView;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.ProjectionTestViews;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import com.xa.mass.testing.chaos.support.TraceEventAssertions;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.trace.sink.ExecutionEventType;
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

/**
 * Chaos probe: per-message retry budget exhaustion, verifying the
 * message-level {@code RETRY_EXHAUSTED} final-reason and task-level
 * {@code ALL_MESSAGES_FAILED} terminal convergence path.
 *
 * <p>This exercises the only currently-implemented retry-exhaustion code path.
 * {@code RETRY_BUDGET_EXHAUSTED} (task-level policy) has no triggering
 * implementation in {@code AllWorkFinalTaskTerminalPolicy}; that is a separate
 * gap tracked in {@code doc/CURRENT_GAPS.md}.
 *
 * <p>Scenario:
 * <ol>
 *   <li>Create a sealed task with {@code MESSAGE_COUNT} messages,
 *       {@code maxRetryCount = MAX_RETRY_PER_MESSAGE} (each message gets
 *       {@code MAX_RETRY_PER_MESSAGE + 1} total attempts).</li>
 *   <li>Start one polling worker that always submits failure.</li>
 *   <li>Wait for the task to reach {@code TERMINAL}.</li>
 *   <li>Assert: every message is {@code FAILED} with
 *       {@code finalReason=RETRY_EXHAUSTED}, each message has exactly
 *       {@code MAX_RETRY_PER_MESSAGE + 1} attempts, and the task
 *       {@code terminalReason=ALL_MESSAGES_FAILED}.</li>
 * </ol>
 *
 * <p>Coverage this closes:
 * <ul>
 *   <li>Per-message retry-exhaustion end-to-end path (SDK embedded runtime).</li>
 *   <li>{@code TaskMsg.finalReason=RETRY_EXHAUSTED} convergence via the
 *       attempt-count policy in {@code AllWorkFinalTaskTerminalPolicy}.</li>
 *   <li>Trace: {@code TASK_WORK_RETRY_RESET} events are emitted for each retry.</li>
 * </ul>
 */
public final class SdkPollingMessageRetryExhaustedChaosRunner {

    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String WORKER_ID = "sdk-retry-exhausted-chaos-worker-0";
    private static final int MESSAGE_COUNT = 2;
    private static final int MAX_RETRY_PER_MESSAGE = 2;
    private static final int EXPECTED_ATTEMPTS_PER_MESSAGE = MAX_RETRY_PER_MESSAGE + 1;
    private static final int TOTAL_EXPECTED_FAILURES =
            MESSAGE_COUNT * EXPECTED_ATTEMPTS_PER_MESSAGE;

    private SdkPollingMessageRetryExhaustedChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK polling retry-exhausted chaos report written to: " + report.reportPath());
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
            CapturingExecutionEventSink traceSink = new CapturingExecutionEventSink();
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createPolling(
                    new ChaosRuntimeHarness.PollingRuntimeConfig(
                            "sdk-retry-exhausted-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    ),
                    traceSink
            );
            AlwaysFailWorkerDriver worker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime.start();
                runtime.registerPollingWorker(WORKER_ID, "sdk-retry-exhausted-chaos", PROJECT_CODE, ROUTING_CODE);

                worker = new AlwaysFailWorkerDriver(WORKER_ID, runtime.pullWorker(WORKER_ID), config);
                AlwaysFailWorkerDriver activeWorker = worker;

                worker.start();
                runtime.waitForWorkerOnline(
                        WORKER_ID,
                        config.timeoutSeconds(),
                        "retry-exhausted chaos worker should be online before task creation"
                );

                Task task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.multiMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-polling-retry-exhausted",
                        ROUTING_CODE,
                        MESSAGE_COUNT,
                        1,
                        MAX_RETRY_PER_MESSAGE,
                        config.timeoutSeconds()
                ));

                // Worker must submit TOTAL_EXPECTED_FAILURES results:
                // each message gets MAX_RETRY_PER_MESSAGE+1 attempts before exhaustion
                ChaosSupport.waitForCondition(
                        () -> activeWorker.failedResults() >= TOTAL_EXPECTED_FAILURES,
                        config.timeoutSeconds(),
                        "retry-exhausted worker should submit " + TOTAL_EXPECTED_FAILURES
                                + " failure results (" + MESSAGE_COUNT + " messages × "
                                + EXPECTED_ATTEMPTS_PER_MESSAGE + " attempts each)"
                );

                TaskOutcomeSnapshot outcome = runtime.waitForTerminalTask(
                        task.getTid(),
                        MESSAGE_COUNT,
                        config.timeoutSeconds(),
                        "retry-exhausted task must converge to TERMINAL"
                );

                List<CompatibilityMessageView> messages = ProjectionTestViews.snapshot(
                        runtime.taskDetailStore(), task.getTid(), MESSAGE_COUNT).messages();

                ChaosSupport.require(messages.size() == MESSAGE_COUNT,
                        "task should have exactly " + MESSAGE_COUNT + " message projections");

                for (CompatibilityMessageView msg : messages) {
                    ChaosSupport.require("FAILED".equals(msg.status()),
                            "message " + msg.messageId() + " should be FAILED, got " + msg.status());
                    ChaosSupport.require("RETRY_EXHAUSTED".equals(msg.finalReason()),
                            "message " + msg.messageId()
                                    + " finalReason should be RETRY_EXHAUSTED, got " + msg.finalReason());
                    ChaosSupport.require(msg.retryCount() == MAX_RETRY_PER_MESSAGE,
                            "message " + msg.messageId() + " retryCount should be " + MAX_RETRY_PER_MESSAGE
                                    + ", got " + msg.retryCount());

                    List<CompatibilityAttemptView> attempts =
                            ProjectionTestViews.attempts(runtime.taskDetailStore(), task.getTid(), msg.messageId());
                    ChaosSupport.require(attempts.size() == EXPECTED_ATTEMPTS_PER_MESSAGE,
                            "message " + msg.messageId() + " should have exactly "
                                    + EXPECTED_ATTEMPTS_PER_MESSAGE + " attempts, got " + attempts.size());

                    for (CompatibilityAttemptView attempt : attempts) {
                        boolean finalAttempt = attempt.attemptNo() == EXPECTED_ATTEMPTS_PER_MESSAGE;
                        if (finalAttempt) {
                            ChaosSupport.require("FAILED".equals(attempt.status()),
                                    "final attempt " + attempt.attemptId() + " should be FAILED, got " + attempt.status());
                            ChaosSupport.require("BUSINESS_FAILURE".equals(attempt.finalReason()),
                                    "final attempt " + attempt.attemptId()
                                            + " finalReason should be BUSINESS_FAILURE, got " + attempt.finalReason());
                        } else {
                            ChaosSupport.require("REVOKED".equals(attempt.status()),
                                    "retryable attempt " + attempt.attemptId() + " should be REVOKED, got " + attempt.status());
                            ChaosSupport.require("REVOKED_FOR_RETRY".equals(attempt.finalReason()),
                                    "retryable attempt " + attempt.attemptId()
                                            + " finalReason should be REVOKED_FOR_RETRY, got " + attempt.finalReason());
                        }
                    }
                }

                ChaosSupport.require(outcome.status().equals(TaskStatus.TERMINAL.name()),
                        "task should converge to TERMINAL");
                ChaosSupport.require("ALL_MESSAGES_FAILED".equals(outcome.terminalReason()),
                        "task terminalReason should be ALL_MESSAGES_FAILED, got " + outcome.terminalReason());

                // Trace contract assertions
                TraceEventAssertions.of(traceSink)
                        .forTask(task.getTid())
                        .requireMinTotalEvents(5)
                        .requireEventType(ExecutionEventType.TASK_STATUS_TRANSITION)
                        .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                        .requireTerminalReason("ALL_MESSAGES_FAILED")
                        .requireEventType(ExecutionEventType.TASK_WORK_STATUS_TRANSITION)
                        // Each retry reset is traced
                        .requireEventType(ExecutionEventType.TASK_WORK_RETRY_RESET)
                        .requireEventType(ExecutionEventType.CALLBACK_ACCEPTED);

                Path reportPath = ChaosReportWriter.write("sdk-polling-retry-exhausted-chaos", Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of("transport", "polling", "adapterId", "polling"),
                        "wallClock", Map.of("totalMillis",
                                ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                        "scenario", Map.of(
                                "messageCount", MESSAGE_COUNT,
                                "maxRetryPerMessage", MAX_RETRY_PER_MESSAGE,
                                "expectedAttemptsPerMessage", EXPECTED_ATTEMPTS_PER_MESSAGE,
                                "totalExpectedFailures", TOTAL_EXPECTED_FAILURES
                        ),
                        "trace", TraceEventAssertions.of(traceSink).summaryMap(task.getTid()),
                        "task", outcome.toMap(),
                        "workers", Map.of("worker", worker.snapshot().toMap())
                ));

                return new ChaosReport(
                        task.getTid(),
                        MESSAGE_COUNT,
                        MAX_RETRY_PER_MESSAGE,
                        worker.failedResults(),
                        outcome.terminalReason(),
                        ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos),
                        reportPath
                );
            } finally {
                closeQuietly(worker);
                runtime.close();
            }
        }
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
            session.connect("sdk-retry-exhausted-chaos-start");
            connected.set(true);
            running.set(true);
            pollThread = new Thread(this::runLoop, "SdkRetryExhaustedChaosWorker-" + workerId);
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
                    List<TaskDispatchItem> items = session.poll(1, 0L);
                    pollCycles.incrementAndGet();
                    if (items == null || items.isEmpty()) {
                        emptyPollCycles.incrementAndGet();
                        Thread.sleep(20L);
                        continue;
                    }
                    for (TaskDispatchItem item : items) {
                        receivedDispatches.incrementAndGet();
                        ChaosSupport.maybeSleep(config.processingDelayMillis());
                        boolean accepted = session.submitResult(
                                item,
                                false,
                                "chaos-retry-exhausted",
                                Map.of("workerId", workerId, "attemptForced", "fail")
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
                    session.disconnect("sdk-retry-exhausted-chaos-stop");
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
                    ChaosSupport.intProperty("mass.sdk.chaos.timeoutSeconds", 30)
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
                                int maxRetryPerMessage,
                                int totalFailedResults,
                                String terminalReason,
                                double wallClockMillis,
                                Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkPollingMessageRetryExhausted task=%s messages=%d maxRetry=%d "
                            + "totalFailed=%d terminalReason=%s wall=%.3fms report=%s",
                    taskId, messageCount, maxRetryPerMessage, totalFailedResults,
                    terminalReason, wallClockMillis, reportPath
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

