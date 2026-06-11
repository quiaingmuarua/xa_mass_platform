package com.xa.mass.testing.chaos;

import com.google.gson.JsonObject;
import com.xa.mass.sdk.model.TaskShellSnapshot;
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
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable chaos probe for websocket disconnect, runtime lease expiry, and redispatch to a
 * different worker.
 *
 * <p>The proof surface is runtime/aggregate/trace-first: active leases, final receipts,
 * task work counters, terminal state, trace events, and real websocket worker observations.
 * Compatibility projection is only included indirectly in the generated report snapshot.</p>
 */
public final class SdkWebSocketLeaseExpiryRedispatchChaosRunner {

    private static final String ENDPOINT_PATH = "/testing-chaos";
    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String CHAOS_WORKER_ID = "sdk-lease-chaos-worker-0";
    private static final String STEADY_WORKER_ID = "sdk-lease-chaos-worker-1";

    private SdkWebSocketLeaseExpiryRedispatchChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK websocket lease-expiry redispatch chaos report written to: " + report.reportPath());
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
                    "sdk-websocket-lease-expiry-redispatch-chaos");
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createWebSocket(
                    new ChaosRuntimeHarness.WebSocketRuntimeConfig(
                            ENDPOINT_PATH,
                            "sdk-lease-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    ),
                    traceArtifacts
            );
            LeaseExpiryWorkerDriver chaosWorker = null;
            LeaseExpiryWorkerDriver steadyWorker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime.start();
                runtime.registerRealtimeWorker(CHAOS_WORKER_ID, "sdk-lease-chaos", PROJECT_CODE, ROUTING_CODE);
                runtime.registerRealtimeWorker(STEADY_WORKER_ID, "sdk-lease-chaos", PROJECT_CODE, ROUTING_CODE);

                chaosWorker = new LeaseExpiryWorkerDriver(
                        CHAOS_WORKER_ID,
                        runtime.serverUri(CHAOS_WORKER_ID),
                        config,
                        WorkerMode.DISCONNECT_WITHOUT_RESULT
                );
                LeaseExpiryWorkerDriver activeChaosWorker = chaosWorker;
                steadyWorker = new LeaseExpiryWorkerDriver(
                        STEADY_WORKER_ID,
                        runtime.serverUri(STEADY_WORKER_ID),
                        config,
                        WorkerMode.NORMAL
                );

                chaosWorker.start();
                runtime.waitForWorkerOnline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "chaos worker should be online before scenario starts"
                );

                TaskShellSnapshot task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.singleMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-chaos-lease-expiry-redispatch",
                        ROUTING_CODE,
                        1,
                        config.timeoutSeconds(),
                        Map.of("source", "SdkWebSocketLeaseExpiryRedispatchChaosRunner")
                ));

                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.capturedMessageId() != null,
                        config.timeoutSeconds(),
                        "chaos worker should capture the first dispatch frame"
                );
                String messageId = activeChaosWorker.capturedMessageId();
                runtime.waitForActiveAttemptOnWorker(
                        task.getTaskId(),
                        messageId,
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "first active lease should stay bound to the chaos worker before expiry"
                );

                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.disconnectCycles() >= 1,
                        config.timeoutSeconds(),
                        "chaos worker should disconnect after receiving the first dispatch"
                );
                runtime.waitForWorkerOffline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "runtime should observe the chaos worker offline after disconnect"
                );

                steadyWorker.start();
                runtime.waitForWorkerOnline(
                        STEADY_WORKER_ID,
                        config.timeoutSeconds(),
                        "steady worker should come online before redispatch"
                );

                runtime.waitForAttemptCount(
                        task.getTaskId(),
                        messageId,
                        2,
                        config.timeoutSeconds(),
                        "runtime should reopen the work after watchdog expiry and redispatch"
                );

                ChaosProofAssertions.TerminalRuntimeProof terminalProof =
                        ChaosProofAssertions.requireSuccessfulTerminalRuntime(
                                runtime,
                                task.getTaskId(),
                                messageId,
                                1,
                                config.timeoutSeconds(),
                                "websocket lease-expiry redispatch"
                        );
                TaskOutcomeSnapshot terminalOutcome = terminalProof.outcome();
                var finalReceipt = terminalProof.finalReceipt();
                ChaosSupport.require(chaosWorker.receivedDispatches() == 1,
                        "chaos worker should receive exactly one dispatch");
                ChaosSupport.require(chaosWorker.disconnectCycles() == 1,
                        "chaos worker should disconnect exactly once");
                ChaosSupport.require(chaosWorker.resultSubmissions() == 0,
                        "chaos worker should not submit a result");
                ChaosSupport.require(steadyWorker.receivedDispatches() >= 1,
                        "steady worker should receive the redispatched work");
                ChaosSupport.require(steadyWorker.resultSubmissions() >= 1,
                        "steady worker should submit the final success result");

                TraceEventAssertions.of(traceArtifacts.captureSink())
                        .forTask(task.getTaskId())
                        .requireMinTotalEvents(5)
                        .requireCallbackAccepted(messageId);
                ChaosProofAssertions.requireLeaseExpirySuccessTrace(traceArtifacts.captureSink(), task.getTaskId());
                traceArtifacts.close();
                List<TraceAnalyzeResponse> analyses = ChaosTraceAnalysisPlanner.analyze(
                        traceArtifacts.outputDir(),
                        ChaosTraceAnalysisPlanner.ChaosProofProfile.LEASE_EXPIRY_REDISPATCH,
                        task.getTaskId(),
                        traceArtifacts.droppedCount()
                );
                ChaosTraceAnalysisPlanner.requireAllOk(analyses);

                Path reportPath = ChaosReportWriter.write("sdk-websocket-lease-expiry-redispatch-chaos",
                        WorkerFaultReportMetadata.merge(
                                WorkerFaultScenarioIndex.Scenario.WEBSOCKET_LEASE_EXPIRY_REDISPATCH,
                                Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of(
                                "transport", "websocket",
                                "transportPort", extractPort(runtime.serverUri(CHAOS_WORKER_ID)),
                                "endpointPath", ENDPOINT_PATH
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
                                        .map(SdkWebSocketLeaseExpiryRedispatchChaosRunner::analysisMap)
                                        .toList()
                        ),
                        "terminalOutcome", terminalOutcome.toMap(),
                        "workers", Map.of(
                                "chaosWorker", chaosWorker.snapshot().toMap(),
                                "steadyWorker", steadyWorker.snapshot().toMap()
                        )
                )));

                return new ChaosReport(
                        extractPort(runtime.serverUri(CHAOS_WORKER_ID)),
                        task.getTaskId(),
                        messageId,
                        chaosWorker.disconnectCycles(),
                        chaosWorker.receivedDispatches(),
                        steadyWorker.receivedDispatches(),
                        finalReceipt.retryCount() + 1,
                        finalReceipt.retryCount(),
                        terminalOutcome.terminalReason(),
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
        DISCONNECT_WITHOUT_RESULT
    }

    private static final class LeaseExpiryWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final URI serverUri;
        private final ChaosConfig config;
        private final WorkerMode mode;
        private final ScheduledExecutorService executor;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean disconnectBudgetConsumed = new AtomicBoolean(false);
        private final AtomicInteger openEvents = new AtomicInteger();
        private final AtomicInteger closeEvents = new AtomicInteger();
        private final AtomicInteger errorEvents = new AtomicInteger();
        private final AtomicInteger disconnectCycles = new AtomicInteger();
        private final AtomicInteger resultSubmissions = new AtomicInteger();
        private final AtomicInteger receivedDispatches = new AtomicInteger();
        private final AtomicReference<JsonObject> capturedDispatchFrame = new AtomicReference<>();
        private final Object clientLock = new Object();

        private WorkerSocketClient client;

        private LeaseExpiryWorkerDriver(String workerId, URI serverUri, ChaosConfig config, WorkerMode mode) {
            this.workerId = workerId;
            this.serverUri = serverUri;
            this.config = config;
            this.mode = mode;
            this.executor = Executors.newSingleThreadScheduledExecutor(namedFactory("SdkLeaseChaosWorker-" + workerId));
        }

        private void start() throws Exception {
            WorkerSocketClient nextClient = new WorkerSocketClient(serverUri);
            ChaosSupport.require(nextClient.connectBlocking(5, TimeUnit.SECONDS),
                    "websocket worker failed to connect: " + workerId + " uri=" + serverUri);
            synchronized (clientLock) {
                client = nextClient;
            }
        }

        private int disconnectCycles() {
            return disconnectCycles.get();
        }

        private int resultSubmissions() {
            return resultSubmissions.get();
        }

        private int receivedDispatches() {
            return receivedDispatches.get();
        }

        private String capturedMessageId() {
            return ChaosSupport.readString(capturedDispatchFrame.get(), "messageId");
        }

        private WorkerRuntimeSnapshot snapshot() {
            return new WorkerRuntimeSnapshot(
                    workerId,
                    mode.name(),
                    openEvents.get(),
                    closeEvents.get(),
                    errorEvents.get(),
                    disconnectCycles.get(),
                    receivedDispatches.get(),
                    resultSubmissions.get()
            );
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            executor.shutdownNow();
            synchronized (clientLock) {
                if (client != null) {
                    client.closeBlocking();
                    client = null;
                }
            }
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        private void onDispatch(JsonObject frame) {
            receivedDispatches.incrementAndGet();
            capturedDispatchFrame.compareAndSet(null, frame.deepCopy());
            executor.execute(() -> handleDispatch(frame));
        }

        private void handleDispatch(JsonObject frame) {
            ChaosSupport.maybeSleep(config.processingDelayMillis());
            if (mode == WorkerMode.DISCONNECT_WITHOUT_RESULT
                    && disconnectBudgetConsumed.compareAndSet(false, true)) {
                disconnectCycles.incrementAndGet();
                try {
                    synchronized (clientLock) {
                        if (client != null) {
                            client.closeBlocking();
                            client = null;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while closing websocket client for " + workerId, e);
                }
                return;
            }
            sendTaskResult(frame);
        }

        private void sendTaskResult(JsonObject frame) {
            WorkerSocketClient activeClient;
            synchronized (clientLock) {
                activeClient = client;
            }
            ChaosSupport.require(activeClient != null && activeClient.isOpen(),
                    "websocket client must be open for " + workerId);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("workerId", workerId);
            output.put("mode", mode.name());
            output.put("receivedDispatches", receivedDispatches.get());
            activeClient.send(ChaosSupport.buildTaskResult(frame, true, "ok", output));
            resultSubmissions.incrementAndGet();
        }

        private final class WorkerSocketClient extends WebSocketClient {
            private WorkerSocketClient(URI serverUri) {
                super(serverUri);
            }

            @Override
            public void onOpen(ServerHandshake handshakedata) {
                openEvents.incrementAndGet();
            }

            @Override
            public void onMessage(String message) {
                JsonObject frame = ChaosSupport.parseFrame(message);
                if (ChaosSupport.isTaskDispatchFrame(frame)) {
                    onDispatch(frame);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                closeEvents.incrementAndGet();
            }

            @Override
            public void onError(Exception ex) {
                errorEvents.incrementAndGet();
            }
        }
    }

    private record WorkerRuntimeSnapshot(String workerId,
                                         String mode,
                                         int openEvents,
                                         int closeEvents,
                                         int errorEvents,
                                         int disconnectCycles,
                                         int receivedDispatches,
                                         int resultSubmissions) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workerId", workerId);
            map.put("mode", mode);
            map.put("openEvents", openEvents);
            map.put("closeEvents", closeEvents);
            map.put("errorEvents", errorEvents);
            map.put("disconnectCycles", disconnectCycles);
            map.put("receivedDispatches", receivedDispatches);
            map.put("resultSubmissions", resultSubmissions);
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

    private record ChaosReport(int transportPort,
                               String taskId,
                               String messageId,
                               int chaosDisconnectCycles,
                               int chaosDispatches,
                               int steadyDispatches,
                               int finalAttemptCount,
                               int finalRetryCount,
                               String terminalReason,
                               double wallClockMillis,
                               Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkWebSocketLeaseExpiryChaos port=%d task=%s message=%s chaosDisconnects=%d chaosDispatches=%d "
                            + "steadyDispatches=%d attempts=%d retryCount=%d terminalReason=%s wall=%.3fms report=%s",
                    transportPort,
                    taskId,
                    messageId,
                    chaosDisconnectCycles,
                    chaosDispatches,
                    steadyDispatches,
                    finalAttemptCount,
                    finalRetryCount,
                    terminalReason,
                    wallClockMillis,
                    reportPath
            );
        }
    }

    private static ThreadFactory namedFactory(String namePrefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static int extractPort(URI uri) {
        return uri.getPort();
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
