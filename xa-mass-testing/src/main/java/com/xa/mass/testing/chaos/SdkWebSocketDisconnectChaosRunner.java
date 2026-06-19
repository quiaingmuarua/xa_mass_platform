package com.xa.mass.testing.chaos;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.testing.chaos.support.CapturingExecutionEventSink;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import com.xa.mass.testing.chaos.support.TraceEventAssertions;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * Runnable chaos probe for WebSocket worker disconnect/reconnect behavior.
 *
 * <p>The scenario keeps the disconnect inside an active lease window: the first worker receives a
 * real dispatch, disconnects before submitting, reconnects, and submits the delayed result. The
 * proof surface is runtime/aggregate/trace-first; compatibility projection is report context only.</p>
 */
public final class SdkWebSocketDisconnectChaosRunner {

    private static final String ENDPOINT_PATH = "/testing-chaos";
    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String CHAOS_WORKER_ID = "sdk-chaos-worker-0";
    private static final String STEADY_WORKER_ID = "sdk-chaos-worker-1";

    private SdkWebSocketDisconnectChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK websocket disconnect chaos report written to: " + report.reportPath());
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
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createWebSocket(
                    new ChaosRuntimeHarness.WebSocketRuntimeConfig(
                            ENDPOINT_PATH,
                            "sdk-disconnect-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    ),
                    traceSink
            );
            DisconnectWorkerDriver chaosWorker = null;
            DisconnectWorkerDriver steadyWorker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime.start();
                runtime.registerRealtimeWorker(CHAOS_WORKER_ID, "sdk-disconnect-chaos", PROJECT_CODE, ROUTING_CODE);
                runtime.registerRealtimeWorker(STEADY_WORKER_ID, "sdk-disconnect-chaos", PROJECT_CODE, ROUTING_CODE);

                chaosWorker = new DisconnectWorkerDriver(
                        CHAOS_WORKER_ID,
                        runtime.serverUri(CHAOS_WORKER_ID),
                        config,
                        true
                );
                DisconnectWorkerDriver activeChaosWorker = chaosWorker;
                steadyWorker = new DisconnectWorkerDriver(
                        STEADY_WORKER_ID,
                        runtime.serverUri(STEADY_WORKER_ID),
                        config,
                        false
                );

                chaosWorker.start();
                steadyWorker.start();
                runtime.waitForWorkerOnline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "chaos worker should be online before scenario starts"
                );
                runtime.waitForWorkerOnline(
                        STEADY_WORKER_ID,
                        config.timeoutSeconds(),
                        "steady worker should be online before scenario starts"
                );

                TaskShellSnapshot chaosTask = createTargetedTask(
                        runtime,
                        "sdk-chaos-disconnect-inflight",
                        CHAOS_WORKER_ID
                );
                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.disconnectCycles() >= 1,
                        config.timeoutSeconds(),
                        "chaos worker should disconnect after receiving a dispatch"
                );
                runtime.waitForWorkerOffline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "runtime should observe chaos worker offline after disconnect"
                );

                TaskShellSnapshot steadyTask = createTargetedTask(
                        runtime,
                        "sdk-chaos-steady-control",
                        STEADY_WORKER_ID
                );
                TaskOutcomeSnapshot steadyOutcome = waitForSuccessfulTerminal(runtime, steadyTask.getTaskId(), "steady control task");

                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.reconnectCycles() >= 1,
                        config.timeoutSeconds(),
                        "chaos worker should reconnect"
                );
                runtime.waitForWorkerOnline(
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "chaos worker should be online again after reconnect"
                );
                TaskOutcomeSnapshot chaosOutcome = waitForSuccessfulTerminal(runtime, chaosTask.getTaskId(), "chaos task");

                String chaosMessageId = activeChaosWorker.capturedMessageId();
                ChaosSupport.require(chaosMessageId != null, "chaos worker should capture the delayed message id");
                RecentFinalWorkReceipt chaosReceipt =
                        runtime.recentFinalReceipt(chaosTask.getTaskId(), chaosMessageId).orElse(null);
                ChaosSupport.require(chaosReceipt != null, "chaos task should have a runtime final receipt");
                ChaosSupport.require(chaosReceipt.retryCount() == 0,
                        "disconnect/reconnect result should complete inside the same lease without retry");

                TaskShellSnapshot followUpTask = createTargetedTask(runtime, "sdk-chaos-follow-up", CHAOS_WORKER_ID);
                TaskOutcomeSnapshot followUpOutcome =
                        waitForSuccessfulTerminal(runtime, followUpTask.getTaskId(), "follow-up task");

                ChaosSupport.require(activeChaosWorker.delayedResultSubmissions() >= 1,
                        "chaos worker should submit at least one delayed result after reconnect");
                ChaosSupport.require(activeChaosWorker.receivedDispatches() >= 2,
                        "chaos worker should receive the initial and follow-up dispatches");
                ChaosSupport.require(steadyWorker.normalResultSubmissions() >= 1,
                        "steady worker should submit the control result");

                TraceEventAssertions.of(traceSink)
                        .forTask(chaosTask.getTaskId())
                        .requireMinTotalEvents(5)
                        .requireCallbackAccepted(chaosMessageId)
                        .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                        .requireTerminalReason("ALL_MESSAGES_SUCCEEDED");
                TraceEventAssertions.of(traceSink)
                        .forTask(steadyTask.getTaskId())
                        .requireEventType(ExecutionEventType.CALLBACK_ACCEPTED)
                        .requireTerminalReason("ALL_MESSAGES_SUCCEEDED");
                TraceEventAssertions.of(traceSink)
                        .forTask(followUpTask.getTaskId())
                        .requireEventType(ExecutionEventType.CALLBACK_ACCEPTED)
                        .requireTerminalReason("ALL_MESSAGES_SUCCEEDED");

                Path reportPath = ChaosReportWriter.write("sdk-websocket-disconnect-chaos",
                        WorkerFaultReportMetadata.merge(
                                WorkerFaultScenarioIndex.Scenario.WEBSOCKET_DISCONNECT_RECONNECT,
                                Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of(
                                "transport", "websocket",
                                "transportPort", extractPort(runtime.serverUri(CHAOS_WORKER_ID)),
                                "endpointPath", ENDPOINT_PATH
                        ),
                        "wallClock", Map.of("totalMillis", ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                        "trace", Map.of(
                                "chaosTask", TraceEventAssertions.of(traceSink).summaryMap(chaosTask.getTaskId()),
                                "steadyControlTask", TraceEventAssertions.of(traceSink).summaryMap(steadyTask.getTaskId()),
                                "followUpTask", TraceEventAssertions.of(traceSink).summaryMap(followUpTask.getTaskId())
                        ),
                        "phases", Map.of(
                                "chaosTask", chaosOutcome.toMap(),
                                "steadyControlTask", steadyOutcome.toMap(),
                                "followUpTask", followUpOutcome.toMap()
                        ),
                        "workers", Map.of(
                                "chaosWorker", chaosWorker.snapshot().toMap(),
                                "steadyWorker", steadyWorker.snapshot().toMap()
                        )
                )));

                return new ChaosReport(
                        extractPort(runtime.serverUri(CHAOS_WORKER_ID)),
                        chaosTask.getTaskId(),
                        steadyTask.getTaskId(),
                        followUpTask.getTaskId(),
                        chaosWorker.disconnectCycles(),
                        chaosWorker.reconnectCycles(),
                        chaosWorker.delayedResultSubmissions(),
                        steadyOutcome.runtime().successCount(),
                        chaosOutcome.runtime().successCount(),
                        followUpOutcome.runtime().successCount(),
                        ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos),
                        reportPath
                );
            } finally {
                closeQuietly(chaosWorker);
                closeQuietly(steadyWorker);
                runtime.close();
            }
        }

        private TaskShellSnapshot createTargetedTask(ChaosRuntimeHarness runtime,
                                                     String taskName,
                                                     String targetWorkerId) {
            return runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.singleMessage(
                    "sdk-chaos",
                    PROJECT_CODE,
                    taskName,
                    ROUTING_CODE,
                    1,
                    config.timeoutSeconds(),
                    Map.of(
                            TaskSharedConfig.TARGET_WORKER_ID, targetWorkerId,
                            "source", "SdkWebSocketDisconnectChaosRunner"
                    )
            ));
        }

        private TaskOutcomeSnapshot waitForSuccessfulTerminal(ChaosRuntimeHarness runtime,
                                                              String taskId,
                                                              String label) throws Exception {
            TaskOutcomeSnapshot outcome = runtime.waitForTerminalTask(
                    taskId,
                    config.messagesPerTask(),
                    config.timeoutSeconds(),
                    label + " must converge"
            );
            TaskWorkStats stats = runtime.waitForRuntimeStats(
                    taskId,
                    config.messagesPerTask(),
                    config.messagesPerTask(),
                    0,
                    0,
                    config.timeoutSeconds(),
                    label + " runtime counters should finalize as success"
            );
            ChaosSupport.require(stats.readyCount() == 0, label + " ready queue should be drained");
            ChaosSupport.require(stats.inflightCount() == 0, label + " active leases should be drained");
            ChaosSupport.require(runtime.activeLeases(taskId).isEmpty(), label + " active leases should be empty");
            TaskStateSnapshot state = runtime.app().getTaskState(taskId);
            ChaosSupport.require(state != null && "TERMINAL".equals(state.getStatus()),
                    label + " task should be TERMINAL");
            ChaosSupport.require("ALL_MESSAGES_SUCCEEDED".equals(outcome.terminalReason()),
                    label + " terminal reason should be ALL_MESSAGES_SUCCEEDED");
            return outcome;
        }
    }

    private static final class DisconnectWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final URI serverUri;
        private final ChaosConfig config;
        private final boolean disconnectBeforeFirstResult;
        private final ScheduledExecutorService executor;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean disconnectBudgetConsumed = new AtomicBoolean(false);
        private final AtomicInteger openEvents = new AtomicInteger();
        private final AtomicInteger closeEvents = new AtomicInteger();
        private final AtomicInteger errorEvents = new AtomicInteger();
        private final AtomicInteger disconnectCycles = new AtomicInteger();
        private final AtomicInteger reconnectCycles = new AtomicInteger();
        private final AtomicInteger receivedDispatches = new AtomicInteger();
        private final AtomicInteger normalResultSubmissions = new AtomicInteger();
        private final AtomicInteger delayedResultSubmissions = new AtomicInteger();
        private final AtomicReference<JsonObject> capturedDispatchFrame = new AtomicReference<>();
        private final Object clientLock = new Object();

        private WorkerSocketClient client;

        private DisconnectWorkerDriver(String workerId,
                                       URI serverUri,
                                       ChaosConfig config,
                                       boolean disconnectBeforeFirstResult) {
            this.workerId = workerId;
            this.serverUri = serverUri;
            this.config = config;
            this.disconnectBeforeFirstResult = disconnectBeforeFirstResult;
            this.executor = Executors.newSingleThreadScheduledExecutor(namedFactory("SdkDisconnectChaosWorker-" + workerId));
        }

        private void start() throws Exception {
            connectNewClient(false);
        }

        private int disconnectCycles() {
            return disconnectCycles.get();
        }

        private int reconnectCycles() {
            return reconnectCycles.get();
        }

        private int delayedResultSubmissions() {
            return delayedResultSubmissions.get();
        }

        private int normalResultSubmissions() {
            return normalResultSubmissions.get();
        }

        private int receivedDispatches() {
            return receivedDispatches.get();
        }

        private String capturedMessageId() {
            return ChaosSupport.dispatchMessageId(capturedDispatchFrame.get());
        }

        private WorkerRuntimeSnapshot snapshot() {
            return new WorkerRuntimeSnapshot(
                    workerId,
                    disconnectBeforeFirstResult,
                    openEvents.get(),
                    closeEvents.get(),
                    errorEvents.get(),
                    disconnectCycles.get(),
                    reconnectCycles.get(),
                    receivedDispatches.get(),
                    normalResultSubmissions.get(),
                    delayedResultSubmissions.get()
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

        private void connectNewClient(boolean reconnect) throws Exception {
            WorkerSocketClient nextClient = new WorkerSocketClient(serverUri);
            ChaosSupport.require(nextClient.connectBlocking(5, TimeUnit.SECONDS),
                    "websocket worker failed to connect: " + workerId + " uri=" + serverUri);
            synchronized (clientLock) {
                client = nextClient;
            }
            if (reconnect) {
                reconnectCycles.incrementAndGet();
            }
        }

        private void onDispatch(JsonObject frame) {
            receivedDispatches.incrementAndGet();
            capturedDispatchFrame.compareAndSet(null, frame.deepCopy());
            executor.execute(() -> handleDispatch(frame));
        }

        private void handleDispatch(JsonObject frame) {
            ChaosSupport.maybeSleep(config.processingDelayMillis());
            if (disconnectBeforeFirstResult && disconnectBudgetConsumed.compareAndSet(false, true)) {
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
                executor.schedule(() -> reconnectAndSubmit(frame), config.reconnectDelayMillis(), TimeUnit.MILLISECONDS);
                return;
            }
            sendTaskResult(frame, false);
        }

        private void reconnectAndSubmit(JsonObject frame) {
            if (closed.get()) {
                return;
            }
            try {
                connectNewClient(true);
                ChaosSupport.maybeSleep(config.processingDelayMillis());
                sendTaskResult(frame, true);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to reconnect and submit delayed result for " + workerId, e);
            }
        }

        private void sendTaskResult(JsonObject frame, boolean delayedAfterReconnect) {
            WorkerSocketClient activeClient;
            synchronized (clientLock) {
                activeClient = client;
            }
            ChaosSupport.require(activeClient != null && activeClient.isOpen(),
                    "websocket client must be open for " + workerId);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("workerId", workerId);
            output.put("chaos", disconnectBeforeFirstResult);
            output.put("delayedAfterReconnect", delayedAfterReconnect);
            output.put("receivedDispatches", receivedDispatches.get());
            activeClient.send(ChaosSupport.buildTaskResult(
                    frame,
                    true,
                    delayedAfterReconnect ? "delayed result after reconnect" : "ok",
                    output
            ));
            if (delayedAfterReconnect) {
                delayedResultSubmissions.incrementAndGet();
            } else {
                normalResultSubmissions.incrementAndGet();
            }
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
                                         boolean chaosWorker,
                                         int openEvents,
                                         int closeEvents,
                                         int errorEvents,
                                         int disconnectCycles,
                                         int reconnectCycles,
                                         int receivedDispatches,
                                         int normalResultSubmissions,
                                         int delayedResultSubmissions) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workerId", workerId);
            map.put("chaosWorker", chaosWorker);
            map.put("openEvents", openEvents);
            map.put("closeEvents", closeEvents);
            map.put("errorEvents", errorEvents);
            map.put("disconnectCycles", disconnectCycles);
            map.put("reconnectCycles", reconnectCycles);
            map.put("receivedDispatches", receivedDispatches);
            map.put("normalResultSubmissions", normalResultSubmissions);
            map.put("delayedResultSubmissions", delayedResultSubmissions);
            return Map.copyOf(map);
        }
    }

    private record ChaosConfig(int messagesPerTask,
                               int processingDelayMillis,
                               long reconnectDelayMillis,
                               long assignmentRetryDelayMillis,
                               long leaseWatchdogIntervalSeconds,
                               long taskMessageLeaseSeconds,
                               int timeoutSeconds) {
        private static ChaosConfig fromSystemProperties() {
            ChaosConfig config = new ChaosConfig(
                    ChaosSupport.intProperty("mass.sdk.chaos.messagesPerTask", 1),
                    ChaosSupport.intProperty("mass.sdk.chaos.processingDelayMillis", 25),
                    ChaosSupport.longProperty("mass.sdk.chaos.reconnectDelayMillis", 800L),
                    ChaosSupport.longProperty("mass.sdk.chaos.assignmentRetryDelayMillis", 100L),
                    ChaosSupport.longProperty("mass.sdk.chaos.leaseWatchdogIntervalSeconds", 1L),
                    ChaosSupport.longProperty("mass.sdk.chaos.taskMessageLeaseSeconds", 30L),
                    ChaosSupport.intProperty("mass.sdk.chaos.timeoutSeconds", 25)
            );
            ChaosSupport.require(config.messagesPerTask == 1,
                    "messagesPerTask must stay 1 for disconnect/reconnect chaos proof");
            ChaosSupport.require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            ChaosSupport.require(config.reconnectDelayMillis >= 0, "reconnectDelayMillis must not be negative");
            ChaosSupport.require(config.assignmentRetryDelayMillis > 0, "assignmentRetryDelayMillis must be positive");
            ChaosSupport.require(config.leaseWatchdogIntervalSeconds > 0, "leaseWatchdogIntervalSeconds must be positive");
            ChaosSupport.require(config.taskMessageLeaseSeconds > 0, "taskMessageLeaseSeconds must be positive");
            ChaosSupport.require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messagesPerTask", messagesPerTask);
            map.put("processingDelayMillis", processingDelayMillis);
            map.put("reconnectDelayMillis", reconnectDelayMillis);
            map.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            map.put("leaseWatchdogIntervalSeconds", leaseWatchdogIntervalSeconds);
            map.put("taskMessageLeaseSeconds", taskMessageLeaseSeconds);
            map.put("timeoutSeconds", timeoutSeconds);
            return Map.copyOf(map);
        }
    }

    private record ChaosReport(int transportPort,
                               String chaosTaskId,
                               String steadyTaskId,
                               String followUpTaskId,
                               int disconnectCycles,
                               int reconnectCycles,
                               int delayedResultSubmissions,
                               long steadySuccessMessages,
                               long chaosSuccessMessages,
                               long followUpSuccessMessages,
                               double wallClockMillis,
                               Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkWebSocketDisconnectChaos port=%d chaosTask=%s steadyTask=%s followUpTask=%s disconnects=%d "
                            + "reconnects=%d delayedResults=%d steadySuccess=%d chaosSuccess=%d "
                            + "followUpSuccess=%d wall=%.3fms report=%s",
                    transportPort,
                    chaosTaskId,
                    steadyTaskId,
                    followUpTaskId,
                    disconnectCycles,
                    reconnectCycles,
                    delayedResultSubmissions,
                    steadySuccessMessages,
                    chaosSuccessMessages,
                    followUpSuccessMessages,
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
}
