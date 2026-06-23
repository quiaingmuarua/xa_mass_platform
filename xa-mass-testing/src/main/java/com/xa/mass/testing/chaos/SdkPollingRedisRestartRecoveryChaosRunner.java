package com.xa.mass.testing.chaos;

import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.testing.chaos.support.ChaosProofAssertions;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.ChaosTraceArtifacts;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import com.xa.mass.testing.chaos.support.TraceEventAssertions;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.sdk.worker.WorkerAction;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SdkPollingRedisRestartRecoveryChaosRunner {

    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String CHAOS_WORKER_ID = "sdk-polling-redis-restart-worker-0";
    private static final String STEADY_WORKER_ID = "sdk-polling-redis-restart-worker-1";

    private SdkPollingRedisRestartRecoveryChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            cleanupRedisNamespace(config.redisUri(), config.redisNamespace(), config.cleanupRedisNamespace());
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK polling Redis restart recovery chaos report written to: " + report.reportPath());
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
                    "sdk-polling-redis-restart-recovery-chaos");
            ChaosRuntimeHarness runtime = null;
            PollingWorkerDriver chaosWorker = null;
            PollingWorkerDriver steadyWorker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime = ChaosRuntimeHarness.createPollingRedis(config.toHarnessConfig(), traceArtifacts);
                runtime.start();
                runtime.registerPollingWorker(CHAOS_WORKER_ID, "sdk-polling-redis-chaos", PROJECT_CODE, ROUTING_CODE);

                chaosWorker = new PollingWorkerDriver(
                        CHAOS_WORKER_ID,
                        runtime.pullWorker(CHAOS_WORKER_ID),
                        config,
                        WorkerMode.STALL_WITHOUT_RESULT
                );
                PollingWorkerDriver activeChaosWorker = chaosWorker;
                chaosWorker.start();
                runtime.waitForWorkerOnline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "chaos polling worker should be online before Redis runtime restart scenario starts"
                );

                TaskShellSnapshot task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.singleMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-polling-redis-restart-recovery",
                        ROUTING_CODE,
                        1,
                        config.timeoutSeconds(),
                        Map.of("source", "SdkPollingRedisRestartRecoveryChaosRunner")
                ));

                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.stalledDispatches() >= 1,
                        config.timeoutSeconds(),
                        "chaos polling worker should claim one dispatch before Redis runtime restart"
                );
                String stalledCorrelationRef = activeChaosWorker.stalledCorrelationRef();
                ChaosSupport.require(stalledCorrelationRef != null && !stalledCorrelationRef.isBlank(),
                        "chaos polling worker should retain the stalled dispatch correlation ref");
                String messageId = runtime.waitForSingleActiveLeaseMessageId(
                        task.getTaskId(),
                        config.timeoutSeconds(),
                        "chaos polling worker should create one active runtime lease before Redis runtime restart"
                );
                runtime.waitForActiveAttemptOnWorker(
                        task.getTaskId(),
                        messageId,
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "first active attempt should stay bound before Redis runtime restart"
                );

                chaosWorker.disconnect();
                runtime.waitForWorkerOffline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "runtime should observe the chaos polling worker offline before Redis runtime restart"
                );
                runtime = runtime.restartPollingRedisRuntime();
                runtime.start();
                runtime.registerPollingWorker(STEADY_WORKER_ID, "sdk-polling-redis-chaos", PROJECT_CODE, ROUTING_CODE);

                steadyWorker = new PollingWorkerDriver(
                        STEADY_WORKER_ID,
                        runtime.pullWorker(STEADY_WORKER_ID),
                        config,
                        WorkerMode.NORMAL
                );
                steadyWorker.start();
                runtime.waitForWorkerOnline(
                        STEADY_WORKER_ID,
                        config.timeoutSeconds(),
                        "steady polling worker should be online after Redis runtime restart"
                );

                runtime.waitForAttemptCount(
                        task.getTaskId(),
                        messageId,
                        2,
                        config.timeoutSeconds(),
                        "second attempt should appear after Redis runtime restart and lease expiry"
                );

                ChaosProofAssertions.TerminalRuntimeProof proof = ChaosProofAssertions.requireSuccessfulTerminalRuntime(
                        runtime,
                        task.getTaskId(),
                        messageId,
                        1,
                        config.timeoutSeconds(),
                        "polling Redis runtime restart recovery"
                );
                TaskOutcomeSnapshot outcome = proof.outcome();
                var finalReceipt = proof.finalReceipt();
                ChaosProofAssertions.requireLeaseExpirySuccessTrace(traceArtifacts.captureSink(), task.getTaskId());
                traceArtifacts.close();
                List<TraceAnalyzeResponse> analyses = ChaosTraceAnalysisPlanner.analyze(
                        traceArtifacts.outputDir(),
                        ChaosTraceAnalysisPlanner.ChaosProofProfile.LEASE_EXPIRY_REDISPATCH,
                        task.getTaskId(),
                        traceArtifacts.droppedCount()
                );
                ChaosTraceAnalysisPlanner.requireAllOk(analyses);

                Path reportPath = ChaosReportWriter.write("sdk-polling-redis-restart-recovery-chaos",
                        WorkerFaultReportMetadata.merge(
                                WorkerFaultScenarioIndex.Scenario.POLLING_REDIS_RESTART_RECOVERY,
                                Map.of(
                                        "config", config.toMap(),
                                        "runtime", Map.of(
                                                "transport", "polling",
                                                "adapterId", "polling",
                                                "redisUri", config.redisUri(),
                                                "redisNamespace", config.redisNamespace(),
                                                "restartMode", "runtime-owner-reconnect"
                                        ),
                                        "wallClock", Map.of("totalMillis",
                                                ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                                        "leaseWindow", Map.of(
                                                "taskMessageLeaseSeconds", config.taskMessageLeaseSeconds(),
                                                "finalReceiptRetryCount", finalReceipt.retryCount()
                                        ),
                                        "trace", Map.of(
                                                "summary", TraceEventAssertions.of(traceArtifacts.captureSink()).summaryMap(task.getTaskId()),
                                                "jsonlPath", traceArtifacts.outputDir().toString(),
                                                "droppedCount", traceArtifacts.droppedCount(),
                                                "analyses", analyses.stream()
                                                        .map(SdkPollingRedisRestartRecoveryChaosRunner::analysisMap)
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
                closeQuietly(runtime);
                cleanupRedisNamespace(config.redisUri(), config.redisNamespace(), config.cleanupRedisNamespace());
            }
        }
    }

    private enum WorkerMode {
        NORMAL,
        STALL_WITHOUT_RESULT
    }

    private static final class PollingWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final EmbeddedPullWorkerSession session;
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
        private final AtomicReference<WorkerAction> stalledDispatch = new AtomicReference<>();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Thread pollThread;

        private PollingWorkerDriver(String workerId,
                                    EmbeddedPullWorkerSession session,
                                    ChaosConfig config,
                                    WorkerMode mode) {
            this.workerId = workerId;
            this.session = session;
            this.config = config;
            this.mode = mode;
        }

        private void start() {
            session.connect("sdk-polling-redis-restart-start");
            connected.set(true);
            running.set(true);
            pollThread = new Thread(this::runLoop, "SdkPollingRedisRestartWorker-" + workerId);
            pollThread.setDaemon(true);
            pollThread.start();
        }

        private void disconnect() {
            if (connected.compareAndSet(true, false)) {
                session.disconnect("sdk-polling-redis-restart-disconnect");
            }
            running.set(false);
        }

        private int stalledDispatches() {
            return stalledDispatches.get();
        }

        private int successfulResults() {
            return successfulResults.get();
        }

        private String stalledCorrelationRef() {
            WorkerAction item = stalledDispatch.get();
            return item != null ? item.getReplyRef() : null;
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
                    List<WorkerAction> items = session.poll(1, 0L);
                    pollCycles.incrementAndGet();
                    if (items == null || items.isEmpty()) {
                        emptyPollCycles.incrementAndGet();
                        Thread.sleep(20L);
                        continue;
                    }
                    for (WorkerAction item : items) {
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

        private void processNormally(WorkerAction item) {
            ChaosSupport.maybeSleep(config.processingDelayMillis());
            boolean accepted = session.submitActionReply(
                    item,
                    true,
                    null,
                    Map.of(
                            "workerId", workerId,
                            "mode", mode.name(),
                            "receivedDispatches", receivedDispatches.get()
                    ).toString()
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
                    session.disconnect("sdk-polling-redis-restart-stop");
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
                               int timeoutSeconds,
                               String redisUri,
                               String redisNamespace,
                               int maxQueuedItems,
                               boolean cleanupRedisNamespace) {
        private static ChaosConfig fromSystemProperties() {
            ChaosConfig config = new ChaosConfig(
                    ChaosSupport.intProperty("mass.sdk.chaos.processingDelayMillis", 25),
                    ChaosSupport.longProperty("mass.sdk.chaos.assignmentRetryDelayMillis", 100L),
                    ChaosSupport.longProperty("mass.sdk.chaos.leaseWatchdogIntervalSeconds", 1L),
                    ChaosSupport.longProperty("mass.sdk.chaos.taskMessageLeaseSeconds", 2L),
                    ChaosSupport.intProperty("mass.sdk.chaos.timeoutSeconds", 30),
                    ChaosSupport.stringProperty("mass.sdk.chaos.redisUri", "redis://127.0.0.1:6379/0"),
                    ChaosSupport.stringProperty("mass.sdk.chaos.redisNamespace",
                            "xa:mass:chaos:redis-restart:" + UUID.randomUUID()),
                    ChaosSupport.intProperty("mass.sdk.chaos.maxQueuedItems", 1024),
                    ChaosSupport.booleanProperty("mass.sdk.chaos.cleanupRedisNamespace", true)
            );
            ChaosSupport.require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            ChaosSupport.require(config.assignmentRetryDelayMillis > 0, "assignmentRetryDelayMillis must be positive");
            ChaosSupport.require(config.leaseWatchdogIntervalSeconds > 0, "leaseWatchdogIntervalSeconds must be positive");
            ChaosSupport.require(config.taskMessageLeaseSeconds > 0, "taskMessageLeaseSeconds must be positive");
            ChaosSupport.require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            ChaosSupport.require(!config.redisUri.isBlank(), "redisUri must not be blank");
            ChaosSupport.require(!config.redisNamespace.isBlank(), "redisNamespace must not be blank");
            ChaosSupport.require(config.maxQueuedItems > 0, "maxQueuedItems must be positive");
            return config;
        }

        private ChaosRuntimeHarness.PollingRedisRuntimeConfig toHarnessConfig() {
            return new ChaosRuntimeHarness.PollingRedisRuntimeConfig(
                    "sdk-polling-redis-restart-chaos",
                    4,
                    assignmentRetryDelayMillis,
                    leaseWatchdogIntervalSeconds,
                    taskMessageLeaseSeconds,
                    redisUri,
                    redisNamespace,
                    maxQueuedItems
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("processingDelayMillis", processingDelayMillis);
            map.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            map.put("leaseWatchdogIntervalSeconds", leaseWatchdogIntervalSeconds);
            map.put("taskMessageLeaseSeconds", taskMessageLeaseSeconds);
            map.put("timeoutSeconds", timeoutSeconds);
            map.put("redisUri", redisUri);
            map.put("redisNamespace", redisNamespace);
            map.put("maxQueuedItems", maxQueuedItems);
            map.put("cleanupRedisNamespace", cleanupRedisNamespace);
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
                    "SdkPollingRedisRestartRecoveryChaos task=%s message=%s stalledDispatches=%d steadyResults=%d "
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

    private static void cleanupRedisNamespace(String redisUri, String namespace, boolean enabled) {
        if (!enabled || namespace == null || namespace.isBlank()) {
            return;
        }
        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            List<String> keys = connection.sync().keys(namespace + "*");
            if (!keys.isEmpty()) {
                connection.sync().del(keys.toArray(String[]::new));
            }
        } finally {
            client.shutdown();
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
