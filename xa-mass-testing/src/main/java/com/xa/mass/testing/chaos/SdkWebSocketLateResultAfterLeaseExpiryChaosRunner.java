package com.xa.mass.testing.chaos;

import com.google.gson.JsonObject;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.testing.chaos.support.ChaosReportWriter;
import com.xa.mass.testing.chaos.support.ChaosRuntimeHarness;
import com.xa.mass.testing.chaos.support.ChaosSupport;
import com.xa.mass.testing.chaos.support.TaskOutcomeSnapshot;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
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

public final class SdkWebSocketLateResultAfterLeaseExpiryChaosRunner {

    private static final String ENDPOINT_PATH = "/testing-chaos";
    private static final String PROJECT_CODE = "demoApp";
    private static final String ROUTING_CODE = "us";
    private static final String CHAOS_WORKER_ID = "sdk-late-result-chaos-worker-0";
    private static final String STEADY_WORKER_ID = "sdk-late-result-chaos-worker-1";

    private SdkWebSocketLateResultAfterLeaseExpiryChaosRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            ChaosConfig config = ChaosConfig.fromSystemProperties();
            ChaosReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK websocket late-result chaos report written to: " + report.reportPath());
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
            ChaosRuntimeHarness runtime = ChaosRuntimeHarness.createWebSocket(
                    new ChaosRuntimeHarness.WebSocketRuntimeConfig(
                            ENDPOINT_PATH,
                            "sdk-late-result-chaos",
                            4,
                            config.assignmentRetryDelayMillis(),
                            config.leaseWatchdogIntervalSeconds(),
                            config.taskMessageLeaseSeconds()
                    )
            );
            LateResultWorkerDriver chaosWorker = null;
            LateResultWorkerDriver steadyWorker = null;
            long wallStartNanos = System.nanoTime();

            try {
                runtime.start();
                runtime.registerRealtimeWorker(CHAOS_WORKER_ID, "sdk-late-result-chaos", PROJECT_CODE, ROUTING_CODE);
                runtime.registerRealtimeWorker(STEADY_WORKER_ID, "sdk-late-result-chaos", PROJECT_CODE, ROUTING_CODE);

                chaosWorker = new LateResultWorkerDriver(
                        CHAOS_WORKER_ID,
                        runtime.serverUri(CHAOS_WORKER_ID),
                        config,
                        WorkerMode.DISCONNECT_AND_SUBMIT_LATE_RESULT
                );
                LateResultWorkerDriver activeChaosWorker = chaosWorker;
                steadyWorker = new LateResultWorkerDriver(
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

                Task task = runtime.createApprovedTask(ChaosRuntimeHarness.TaskCreateSpec.singleMessage(
                        "sdk-chaos",
                        PROJECT_CODE,
                        "sdk-chaos-late-result-after-lease-expiry",
                        ROUTING_CODE,
                        1,
                        config.timeoutSeconds(),
                        Map.of("source", "SdkWebSocketLateResultAfterLeaseExpiryChaosRunner")
                ));

                TaskMsg message = runtime.waitForSingleMessage(task.getTid(), config.timeoutSeconds());
                runtime.waitForActiveAttemptOnWorker(
                        task.getTid(),
                        message.getMessageId(),
                        CHAOS_WORKER_ID,
                        config.timeoutSeconds(),
                        "first active attempt should stay bound to the chaos worker before lease expiry"
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
                        task.getTid(),
                        message.getMessageId(),
                        2,
                        config.timeoutSeconds(),
                        "second attempt should appear after watchdog expiry and redispatch"
                );

                TaskOutcomeSnapshot terminalOutcome = runtime.waitForTerminalTask(
                        task.getTid(),
                        1,
                        config.timeoutSeconds(),
                        "late-result chaos task must converge"
                );

                TaskMsg terminalMessage = runtime.app().getTaskMessageProjection(task.getTid(), message.getMessageId());
                List<TaskMsgAttempt> terminalAttempts = runtime.app().getTaskMessageAttemptAuditTrail(task.getTid(), message.getMessageId());
                ChaosSupport.require(terminalAttempts.size() == 2, "task should finish with exactly two attempts before late replay");

                TaskMsgAttempt expiredAttempt = terminalAttempts.get(0);
                TaskMsgAttempt successAttempt = terminalAttempts.get(1);
                LocalDateTime initialLeaseExpireTime = expiredAttempt.getLeaseExpireTime();

                ChaosSupport.require(CHAOS_WORKER_ID.equals(expiredAttempt.getWorkerId()),
                        "first attempt should belong to the chaos worker");
                ChaosSupport.require(expiredAttempt.getStatus() == TaskMsgAttemptStatus.EXPIRED,
                        "first attempt should close as EXPIRED");
                ChaosSupport.require(expiredAttempt.getFinalReason() == TaskMsgAttemptFinalReason.LEASE_EXPIRED,
                        "first attempt final reason should be LEASE_EXPIRED");
                ChaosSupport.require(STEADY_WORKER_ID.equals(successAttempt.getWorkerId()),
                        "second attempt should belong to the steady worker");
                ChaosSupport.require(successAttempt.getStatus() == TaskMsgAttemptStatus.SUCCEEDED,
                        "second attempt should close as SUCCEEDED");
                ChaosSupport.require(terminalMessage.getStatus() == TaskMsgStatus.SUCCESS,
                        "logical message should converge to SUCCESS before late replay");
                ChaosSupport.require(terminalMessage.getFinalReason() == TaskMsgFinalReason.BUSINESS_SUCCESS,
                        "logical message final reason should be BUSINESS_SUCCESS before late replay");
                ChaosSupport.require(terminalMessage.getRetryCount() == 1,
                        "logical message retryCount should record one expiry-driven retry before late replay");

                activeChaosWorker.reconnectAndSubmitLateResult();
                ChaosSupport.waitForCondition(
                        () -> activeChaosWorker.lateResultSubmissions() >= 1,
                        config.timeoutSeconds(),
                        "chaos worker should submit a late stale result after reconnect"
                );
                ChaosSupport.maybeSleep(config.postReplayObserveDelayMillis());

                TaskOutcomeSnapshot afterReplayOutcome = runtime.snapshotTaskOutcome(task.getTid(), 1);
                TaskMsg finalMessage = runtime.app().getTaskMessageProjection(task.getTid(), message.getMessageId());
                List<TaskMsgAttempt> finalAttempts = runtime.app().getTaskMessageAttemptAuditTrail(task.getTid(), message.getMessageId());

                ChaosSupport.require(finalAttempts.size() == 2, "late stale result must not create a third attempt");
                ChaosSupport.require(finalMessage != null, "final task message should exist");
                ChaosSupport.require(finalMessage.getStatus() == TaskMsgStatus.SUCCESS,
                        "late stale result must not change logical message success");
                ChaosSupport.require(finalMessage.getFinalReason() == TaskMsgFinalReason.BUSINESS_SUCCESS,
                        "late stale result must not change logical final reason");
                ChaosSupport.require(finalMessage.getRetryCount() == 1,
                        "late stale result must not change retryCount");
                ChaosSupport.require(STEADY_WORKER_ID.equals(finalMessage.getLatestAttemptWorkerId()),
                        "late stale result must not steal latest attempt ownership");
                ChaosSupport.require(TaskStatus.TERMINAL == runtime.app().getTask(task.getTid()).getStatus(),
                        "late stale result must not reopen the task");
                ChaosSupport.require("ALL_MESSAGES_SUCCEEDED".equals(afterReplayOutcome.terminalReason()),
                        "late stale result must not change task terminal reason");

                Path reportPath = ChaosReportWriter.write("sdk-websocket-late-result-after-lease-expiry-chaos", Map.of(
                        "config", config.toMap(),
                        "runtime", Map.of(
                                "transport", "websocket",
                                "transportPort", extractPort(runtime.serverUri(CHAOS_WORKER_ID)),
                                "endpointPath", ENDPOINT_PATH
                        ),
                        "wallClock", Map.of("totalMillis", ChaosSupport.nanosToMillis(System.nanoTime() - wallStartNanos)),
                        "leaseWindow", Map.of(
                                "taskMessageLeaseSeconds", config.taskMessageLeaseSeconds(),
                                "initialLeaseExpireTime", String.valueOf(initialLeaseExpireTime)
                        ),
                        "beforeReplay", terminalOutcome.toMap(),
                        "afterReplay", afterReplayOutcome.toMap(),
                        "workers", Map.of(
                                "chaosWorker", chaosWorker.snapshot().toMap(),
                                "steadyWorker", steadyWorker.snapshot().toMap()
                        )
                ));

                return new ChaosReport(
                        extractPort(runtime.serverUri(CHAOS_WORKER_ID)),
                        task.getTid(),
                        message.getMessageId(),
                        chaosWorker.disconnectCycles(),
                        chaosWorker.reconnectCycles(),
                        chaosWorker.lateResultSubmissions(),
                        finalAttempts.size(),
                        finalMessage.getRetryCount(),
                        afterReplayOutcome.terminalReason(),
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
        DISCONNECT_AND_SUBMIT_LATE_RESULT
    }

    private static final class LateResultWorkerDriver implements AutoCloseable {
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
        private final AtomicInteger reconnectCycles = new AtomicInteger();
        private final AtomicInteger normalResultSubmissions = new AtomicInteger();
        private final AtomicInteger lateResultSubmissions = new AtomicInteger();
        private final AtomicInteger receivedDispatches = new AtomicInteger();
        private final AtomicReference<JsonObject> capturedDispatchFrame = new AtomicReference<>();
        private final Object clientLock = new Object();

        private WorkerSocketClient client;

        private LateResultWorkerDriver(String workerId, URI serverUri, ChaosConfig config, WorkerMode mode) {
            this.workerId = workerId;
            this.serverUri = serverUri;
            this.config = config;
            this.mode = mode;
            this.executor = Executors.newSingleThreadScheduledExecutor(namedFactory("SdkLateChaosWorker-" + workerId));
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

        private int lateResultSubmissions() {
            return lateResultSubmissions.get();
        }

        private void reconnectAndSubmitLateResult() throws Exception {
            JsonObject frame = capturedDispatchFrame.get();
            ChaosSupport.require(frame != null, "captured dispatch frame must exist before late replay");
            connectNewClient(true);
            ChaosSupport.maybeSleep(config.processingDelayMillis());
            sendTaskResult(frame, true);
        }

        private WorkerRuntimeSnapshot snapshot() {
            return new WorkerRuntimeSnapshot(
                    workerId,
                    mode.name(),
                    openEvents.get(),
                    closeEvents.get(),
                    errorEvents.get(),
                    disconnectCycles.get(),
                    reconnectCycles.get(),
                    receivedDispatches.get(),
                    normalResultSubmissions.get(),
                    lateResultSubmissions.get()
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
            if (mode == WorkerMode.DISCONNECT_AND_SUBMIT_LATE_RESULT
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
            sendTaskResult(frame, false);
        }

        private void sendTaskResult(JsonObject frame, boolean lateReplay) {
            WorkerSocketClient activeClient;
            synchronized (clientLock) {
                activeClient = client;
            }
            ChaosSupport.require(activeClient != null && activeClient.isOpen(),
                    "websocket client must be open for " + workerId);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("workerId", workerId);
            output.put("mode", mode.name());
            output.put("lateReplay", lateReplay);
            output.put("receivedDispatches", receivedDispatches.get());
            activeClient.send(ChaosSupport.buildTaskResult(frame, true, lateReplay ? "late stale result" : "ok", output));
            if (lateReplay) {
                lateResultSubmissions.incrementAndGet();
            } else {
                normalResultSubmissions.incrementAndGet();
            }
        }

        private final class WorkerSocketClient extends WebSocketClient {
            private WorkerSocketClient(URI serverUri) {
                super(ChaosSupport.appendWorkerId(serverUri, workerId));
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
                                         int reconnectCycles,
                                         int receivedDispatches,
                                         int normalResultSubmissions,
                                         int lateResultSubmissions) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workerId", workerId);
            map.put("mode", mode);
            map.put("openEvents", openEvents);
            map.put("closeEvents", closeEvents);
            map.put("errorEvents", errorEvents);
            map.put("disconnectCycles", disconnectCycles);
            map.put("reconnectCycles", reconnectCycles);
            map.put("receivedDispatches", receivedDispatches);
            map.put("normalResultSubmissions", normalResultSubmissions);
            map.put("lateResultSubmissions", lateResultSubmissions);
            return Map.copyOf(map);
        }
    }

    private record ChaosConfig(int processingDelayMillis,
                               long assignmentRetryDelayMillis,
                               long leaseWatchdogIntervalSeconds,
                               long taskMessageLeaseSeconds,
                               int postReplayObserveDelayMillis,
                               int timeoutSeconds) {
        private static ChaosConfig fromSystemProperties() {
            ChaosConfig config = new ChaosConfig(
                    ChaosSupport.intProperty("mass.sdk.chaos.processingDelayMillis", 25),
                    ChaosSupport.longProperty("mass.sdk.chaos.assignmentRetryDelayMillis", 100L),
                    ChaosSupport.longProperty("mass.sdk.chaos.leaseWatchdogIntervalSeconds", 1L),
                    ChaosSupport.longProperty("mass.sdk.chaos.taskMessageLeaseSeconds", 2L),
                    ChaosSupport.intProperty("mass.sdk.chaos.postReplayObserveDelayMillis", 500),
                    ChaosSupport.intProperty("mass.sdk.chaos.timeoutSeconds", 25)
            );
            ChaosSupport.require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            ChaosSupport.require(config.assignmentRetryDelayMillis > 0, "assignmentRetryDelayMillis must be positive");
            ChaosSupport.require(config.leaseWatchdogIntervalSeconds > 0, "leaseWatchdogIntervalSeconds must be positive");
            ChaosSupport.require(config.taskMessageLeaseSeconds > 0, "taskMessageLeaseSeconds must be positive");
            ChaosSupport.require(config.postReplayObserveDelayMillis >= 0, "postReplayObserveDelayMillis must not be negative");
            ChaosSupport.require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("processingDelayMillis", processingDelayMillis);
            map.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            map.put("leaseWatchdogIntervalSeconds", leaseWatchdogIntervalSeconds);
            map.put("taskMessageLeaseSeconds", taskMessageLeaseSeconds);
            map.put("postReplayObserveDelayMillis", postReplayObserveDelayMillis);
            map.put("timeoutSeconds", timeoutSeconds);
            return Map.copyOf(map);
        }
    }

    private record ChaosReport(int transportPort,
                               String taskId,
                               String messageId,
                               int chaosDisconnectCycles,
                               int chaosReconnectCycles,
                               int chaosLateResultSubmissions,
                               int finalAttemptCount,
                               int finalRetryCount,
                               String terminalReason,
                               double wallClockMillis,
                               Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkWebSocketLateResultChaos port=%d task=%s message=%s chaosDisconnects=%d chaosReconnects=%d "
                            + "lateResults=%d attempts=%d retryCount=%d terminalReason=%s wall=%.3fms report=%s",
                    transportPort,
                    taskId,
                    messageId,
                    chaosDisconnectCycles,
                    chaosReconnectCycles,
                    chaosLateResultSubmissions,
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
}

