package com.xa.mass.testing.chaos;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.nio.file.Path;
import java.time.LocalDateTime;
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
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createPolling(
                    new ChaosRuntimeHarness.PollingRuntimeConfig(
                            "sdk-polling-lease-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    )
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

                Task task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.singleMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-polling-chaos-lease-expiry-redispatch",
                        ROUTING_CODE,
                        1,
                        config.timeoutSeconds(),
                        Map.of("source", "SdkPollingLeaseExpiryRedispatchChaosRunner")
                ));

                TaskMsg message = runtime.waitForSingleMessage(task.getTid(), config.timeoutSeconds());
                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.stalledDispatches() >= 1,
                        config.timeoutSeconds(),
                        "chaos polling worker should claim one dispatch and stall without a result"
                );
                runtime.waitForActiveAttemptOnWorker(
                        task.getTid(),
                        message.getMessageId(),
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
                        task.getTid(),
                        message.getMessageId(),
                        2,
                        config.timeoutSeconds(),
                        "second attempt should appear after watchdog expiry and polling redispatch"
                );

                TaskOutcomeSnapshot outcome = runtime.waitForTerminalTask(
                        task.getTid(),
                        1,
                        config.timeoutSeconds(),
                        "polling lease-expiry redispatch task must converge"
                );
                TaskMsg finalMessage = runtime.app().getTaskMessage(task.getTid(), message.getMessageId());
                List<TaskMsgAttempt> finalAttempts = runtime.app().getTaskMessageAttempts(task.getTid(), message.getMessageId());

                ChaosSupport.require(finalAttempts.size() == 2, "task should finish with exactly two attempts");
                TaskMsgAttempt expiredAttempt = finalAttempts.get(0);
                TaskMsgAttempt successAttempt = finalAttempts.get(1);
                LocalDateTime initialLeaseExpireTime = expiredAttempt.getLeaseExpireTime();

                ChaosSupport.require(CHAOS_WORKER_ID.equals(expiredAttempt.getWorkerId()),
                        "first attempt should belong to the polling chaos worker");
                ChaosSupport.require(expiredAttempt.getStatus() == TaskMsgAttemptStatus.EXPIRED,
                        "first attempt should close as EXPIRED");
                ChaosSupport.require(expiredAttempt.getFinalReason() == TaskMsgAttemptFinalReason.LEASE_EXPIRED,
                        "first attempt final reason should be LEASE_EXPIRED");
                ChaosSupport.require(STEADY_WORKER_ID.equals(successAttempt.getWorkerId()),
                        "second attempt should belong to the steady polling worker");
                ChaosSupport.require(successAttempt.getStatus() == TaskMsgAttemptStatus.SUCCEEDED,
                        "second attempt should close as SUCCEEDED");
                ChaosSupport.require(finalMessage != null, "final logical message should exist");
                ChaosSupport.require(finalMessage.getStatus() == TaskMsgStatus.SUCCESS,
                        "logical message should converge to SUCCESS after polling redispatch");
                ChaosSupport.require(finalMessage.getFinalReason() == TaskMsgFinalReason.BUSINESS_SUCCESS,
                        "logical message final reason should be BUSINESS_SUCCESS");
                ChaosSupport.require(finalMessage.getRetryCount() == 1,
                        "logical message retryCount should record one expiry-driven retry");
                ChaosSupport.require(STEADY_WORKER_ID.equals(finalMessage.getLatestAttemptWorkerId()),
                        "latest attempt worker should be the steady polling worker");
                ChaosSupport.require(outcome.status().equals(TaskStatus.TERMINAL.name()),
                        "task should converge to TERMINAL");
                ChaosSupport.require("ALL_MESSAGES_SUCCEEDED".equals(outcome.terminalReason()),
                        "task terminal reason should be ALL_MESSAGES_SUCCEEDED");

                Path reportPath = ChaosReportWriter.write("sdk-polling-lease-expiry-redispatch-chaos", Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of(
                                "transport", "polling",
                                "adapterId", "polling"
                        ),
                        "wallClock", Map.of("totalMillis", ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                        "leaseWindow", Map.of(
                                "taskMessageLeaseSeconds", config.taskMessageLeaseSeconds(),
                                "initialLeaseExpireTime", String.valueOf(initialLeaseExpireTime)
                        ),
                        "task", outcome.toMap(),
                        "workers", Map.of(
                                "chaosWorker", chaosWorker.snapshot().toMap(),
                                "steadyWorker", steadyWorker.snapshot().toMap()
                        )
                ));

                return new ChaosReport(
                        task.getTid(),
                        message.getMessageId(),
                        chaosWorker.stalledDispatches(),
                        steadyWorker.successfulResults(),
                        finalAttempts.size(),
                        finalMessage.getRetryCount(),
                        outcome.terminalReason(),
                        ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos),
                        reportPath
                );
            } finally {
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
}
