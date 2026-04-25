package com.xa.mass.testing.chaos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.transport.websocket.queue.OutboundDelivery;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.transport.WorkerTransportHints;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.ServerSocket;
import java.net.URI;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Runnable chaos probe for websocket disconnect -> real short lease expiry ->
 * redispatch to a different worker.
 *
 * <p>The runtime path stays real for dispatch, disconnect, worker online/offline
 * observation, redispatch, and result ingest. The embedded runtime is configured
 * with a deliberately short task-message lease window so watchdog expiry can be
 * exercised in a routine acceptance loop without mutating persisted attempt data.
 *
 * <p>Useful JVM properties:
 *
 * <pre>{@code
 * -Dmass.sdk.chaos.processingDelayMillis=25
 * -Dmass.sdk.chaos.assignmentRetryDelayMillis=100
 * -Dmass.sdk.chaos.leaseWatchdogIntervalSeconds=1
 * -Dmass.sdk.chaos.taskMessageLeaseSeconds=2
 * -Dmass.sdk.chaos.timeoutSeconds=25
 * }</pre>
 */
public final class SdkWebSocketLeaseExpiryRedispatchChaosRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
            System.out.println("SDK lease-expiry redispatch chaos report written to: " + report.reportPath());
        } catch (Throwable t) {
            exitCode = 1;
            throw t;
        } finally {
            if (booleanProperty("mass.sdk.chaos.forceExit", true)) {
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
            EmbeddedRuntime runtime = buildRuntime(config);
            MassSdkApplication app = runtime.app();
            WebSocketWorkerDriver chaosWorker = null;
            WebSocketWorkerDriver steadyWorker = null;
            long wallStartNanos = System.nanoTime();

            try {
                app.start();
                registerWorker(app, CHAOS_WORKER_ID);
                registerWorker(app, STEADY_WORKER_ID);

                chaosWorker = new WebSocketWorkerDriver(
                        CHAOS_WORKER_ID,
                        runtime.serverUri(),
                        config,
                        WorkerMode.DISCONNECT_WITHOUT_RESULT
                );
                WebSocketWorkerDriver activeChaosWorker = chaosWorker;
                steadyWorker = new WebSocketWorkerDriver(
                        STEADY_WORKER_ID,
                        runtime.serverUri(),
                        config,
                        WorkerMode.NORMAL
                );

                chaosWorker.start();
                waitForWorkerOnline(app, CHAOS_WORKER_ID, "chaos worker should be online before scenario starts");

                Task task = createUntargetedTask(app, "sdk-chaos-lease-expiry-redispatch");
                TaskMsg message = waitForSingleMessage(app, task.getTid());

                waitForCondition(
                        () -> activeChaosWorker.disconnectCycles() >= 1,
                        config.timeoutSeconds(),
                        "chaos worker should disconnect after receiving the first dispatch"
                );
                waitForCondition(
                        () -> !app.isWorkerOnline(CHAOS_WORKER_ID),
                        config.timeoutSeconds(),
                        "runtime should observe the chaos worker offline after disconnect"
                );

                TaskMsgAttempt firstAttempt = waitForActiveAttemptOnWorker(
                        app,
                        task.getTid(),
                        message.getMessageId(),
                        CHAOS_WORKER_ID,
                        "first active attempt should stay bound to the chaos worker before lease expiry"
                );

                steadyWorker.start();
                waitForWorkerOnline(app, STEADY_WORKER_ID, "steady worker should come online before redispatch");

                waitForCondition(
                        () -> app.getTaskManager().getTaskMessageAttempts(task.getTid(), message.getMessageId()).size() >= 2,
                        config.timeoutSeconds(),
                        "second attempt should appear after watchdog expiry and redispatch"
                );

                TaskOutcome outcome = waitForTerminalTask(app, task.getTid(), "lease-expiry redispatch task must converge");
                TaskMsg finalMessage = app.getTaskManager().getTaskMessage(task.getTid(), message.getMessageId());
                List<TaskMsgAttempt> finalAttempts = app.getTaskManager().getTaskMessageAttempts(task.getTid(), message.getMessageId());

                require(finalAttempts.size() == 2, "task should finish with exactly two attempts");
                TaskMsgAttempt expiredAttempt = finalAttempts.get(0);
                TaskMsgAttempt successAttempt = finalAttempts.get(1);

                require(CHAOS_WORKER_ID.equals(expiredAttempt.getWorkerId()),
                        "first attempt should belong to the chaos worker");
                require(expiredAttempt.getStatus() == TaskMsgAttemptStatus.EXPIRED,
                        "first attempt should close as EXPIRED");
                require(expiredAttempt.getFinalReason() == TaskMsgAttemptFinalReason.LEASE_EXPIRED,
                        "first attempt final reason should be LEASE_EXPIRED");

                require(STEADY_WORKER_ID.equals(successAttempt.getWorkerId()),
                        "second attempt should belong to the steady worker");
                require(successAttempt.getStatus() == TaskMsgAttemptStatus.SUCCEEDED,
                        "second attempt should close as SUCCEEDED");

                require(finalMessage != null, "final task message should exist");
                require(finalMessage.getStatus() == TaskMsgStatus.SUCCESS,
                        "logical message should converge to SUCCESS after redispatch");
                require(finalMessage.getFinalReason() == TaskMsgFinalReason.BUSINESS_SUCCESS,
                        "logical message final reason should be BUSINESS_SUCCESS");
                require(finalMessage.getRetryCount() == 1,
                        "logical message retryCount should record one expiry-driven retry");
                require(STEADY_WORKER_ID.equals(finalMessage.getLatestAttemptWorkerId()),
                        "latest attempt worker should be the steady worker");

                require(outcome.status().equals(TaskStatus.TERMINAL.name()),
                        "task should converge to TERMINAL");
                require("ALL_MESSAGES_SUCCEEDED".equals(outcome.terminalReason()),
                        "task terminal reason should be ALL_MESSAGES_SUCCEEDED");
                require(chaosWorker.receivedDispatches() == 1,
                        "chaos worker should receive exactly one dispatch");
                require(chaosWorker.disconnectCycles() == 1,
                        "chaos worker should disconnect exactly once");
                require(chaosWorker.resultSubmissions() == 0,
                        "chaos worker should not submit a result");
                require(steadyWorker.resultSubmissions() >= 1,
                        "steady worker should submit the final success result");

                Path reportPath = writeReport(
                        config,
                        runtime,
                        outcome,
                        firstAttempt.getLeaseExpireTime(),
                        new MessageProjection(
                                finalMessage.getMessageId(),
                                finalMessage.getStatus() != null ? finalMessage.getStatus().name() : null,
                                finalMessage.getFinalReason() != null ? finalMessage.getFinalReason().name() : null,
                                finalMessage.getRetryCount(),
                                finalMessage.getLatestAttemptWorkerId()
                        ),
                        finalAttempts.stream().map(AttemptProjection::fromAttempt).toList(),
                        chaosWorker.snapshot(),
                        steadyWorker.snapshot(),
                        System.nanoTime() - wallStartNanos
                );

                return new ChaosReport(
                        runtime.transportPort(),
                        task.getTid(),
                        finalMessage.getMessageId(),
                        chaosWorker.disconnectCycles(),
                        chaosWorker.receivedDispatches(),
                        steadyWorker.receivedDispatches(),
                        finalAttempts.size(),
                        finalMessage.getRetryCount(),
                        outcome.terminalReason(),
                        nanosToMillis(System.nanoTime() - wallStartNanos),
                        reportPath
                );
            } finally {
                closeQuietly(chaosWorker);
                closeQuietly(steadyWorker);
                app.stop();
            }
        }

        private EmbeddedRuntime buildRuntime(ChaosConfig config) {
            int transportPort = findFreePort();
            MassSdkApplication app = MassSdk.builder()
                    .transportServer(transportPort, ENDPOINT_PATH)
                    .websocket(gateway -> gateway
                            .enabled(true)
                            .transportServerEnabled(true)
                            .inputQueue(new InMemoryMessageQueue<>("sdk-lease-chaos-input", String.class))
                            .outputQueue(new InMemoryMessageQueue<>("sdk-lease-chaos-output", OutboundDelivery.class))
                            .queueMode())
                    .engine(engine -> engine
                            .enabled(true)
                            .workerThreads(4)
                            .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                            .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                            .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds()))
                    .build();
            return new EmbeddedRuntime(app, transportPort, ENDPOINT_PATH);
        }

        private void registerWorker(MassSdkApplication app, String workerId) {
            app.registerWorker(WorkerRegistration.builder()
                    .workerId(workerId)
                    .workerGroupId("sdk-lease-chaos")
                    .supportedProjects(List.of(PROJECT_CODE))
                    .transportHint(WorkerTransportHints.REALTIME)
                    .build());
            app.registerWorkerContext(WorkerContextRegistration.builder()
                    .workerContextId(workerId + "-context")
                    .workerId(workerId)
                    .project(PROJECT_CODE)
                    .routingTags(java.util.Set.of(ROUTING_CODE))
                    .build());
        }

        private Task createUntargetedTask(MassSdkApplication app, String taskName) {
            Task task = app.createTask(MassTaskCreateRequest.builder()
                    .userId("sdk-chaos")
                    .project(PROJECT_CODE)
                    .taskName(taskName)
                    .sharedConfig(Map.of(
                            TaskSharedConfig.ROUTING_CODE, ROUTING_CODE,
                            "source", "SdkWebSocketLeaseExpiryRedispatchChaosRunner"
                    ))
                    .inputs(List.of(Map.of(
                            "seq", 0,
                            "taskName", taskName,
                            "target", taskName + "-target-0"
                    )))
                    .batchSize(1)
                    .defaultMsgMaxRetryCount(1)
                    .openEnded(false)
                    .maxRuntimeSeconds(config.timeoutSeconds())
                    .build());
            require(app.approveTask(task.getTid()), "task approval should succeed for " + task.getTid());
            return task;
        }

        private void waitForWorkerOnline(MassSdkApplication app, String workerId, String failureMessage) throws Exception {
            waitForCondition(() -> app.isWorkerOnline(workerId), config.timeoutSeconds(), failureMessage);
        }

        private TaskMsg waitForSingleMessage(MassSdkApplication app, String taskId) throws Exception {
            waitForCondition(
                    () -> app.getTaskMessages(taskId).size() == 1,
                    config.timeoutSeconds(),
                    "task should materialize exactly one logical message"
            );
            return app.getTaskMessages(taskId).get(0);
        }

        private TaskMsgAttempt waitForActiveAttemptOnWorker(MassSdkApplication app,
                                                            String taskId,
                                                            String messageId,
                                                            String workerId,
                                                            String failureMessage) throws Exception {
            waitForCondition(() -> {
                TaskMsgAttempt attempt = app.getTaskManager().getLatestActiveTaskMessageAttempt(taskId, messageId);
                return attempt != null
                        && workerId.equals(attempt.getWorkerId())
                        && !attempt.getStatus().isFinal();
            }, config.timeoutSeconds(), failureMessage);
            return app.getTaskManager().getLatestActiveTaskMessageAttempt(taskId, messageId);
        }

        private TaskOutcome waitForTerminalTask(MassSdkApplication app, String taskId, String failureMessage) throws Exception {
            waitForCondition(
                    () -> {
                        Task current = app.getTask(taskId);
                        return current != null && current.getStatus() == TaskStatus.TERMINAL;
                    },
                    config.timeoutSeconds(),
                    failureMessage
            );

            Task task = app.getTask(taskId);
            require(task != null, "task should exist: " + taskId);
            List<TaskMsg> messages = app.getTaskMessages(taskId);
            List<MessageOutcome> messageOutcomes = new ArrayList<>(messages.size());
            for (TaskMsg message : messages) {
                List<TaskMsgAttempt> attempts = app.getTaskManager().getTaskMessageAttempts(taskId, message.getMessageId());
                messageOutcomes.add(new MessageOutcome(
                        message.getMessageId(),
                        message.getStatus() != null ? message.getStatus().name() : null,
                        message.getFinalReason() != null ? message.getFinalReason().name() : null,
                        message.getRetryCount(),
                        message.getLatestAttemptWorkerId(),
                        attempts.stream().map(AttemptProjection::fromAttempt).toList()
                ));
            }
            return new TaskOutcome(
                    task.getTid(),
                    task.getStatus() != null ? task.getStatus().name() : null,
                    task.getTerminalReason() != null ? task.getTerminalReason().name() : null,
                    messageOutcomes
            );
        }

        private Path writeReport(ChaosConfig config,
                                 EmbeddedRuntime runtime,
                                 TaskOutcome outcome,
                                 LocalDateTime initialLeaseExpireTime,
                                 MessageProjection finalMessage,
                                 List<AttemptProjection> finalAttempts,
                                 WorkerRuntimeSnapshot chaosWorker,
                                 WorkerRuntimeSnapshot steadyWorker,
                                 long wallNanos) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("config", config.toMap());
            report.put("runtime", Map.of(
                    "transport", "websocket",
                    "transportPort", runtime.transportPort(),
                    "endpointPath", runtime.endpointPath()
            ));
            report.put("wallClock", Map.of("totalMillis", nanosToMillis(wallNanos)));
            report.put("leaseWindow", Map.of(
                    "strategy", "runtime-configured-short-lease",
                    "taskMessageLeaseSeconds", config.taskMessageLeaseSeconds(),
                    "initialLeaseExpireTime", String.valueOf(initialLeaseExpireTime)
            ));
            report.put("task", outcome.toMap());
            report.put("finalMessage", finalMessage.toMap());
            report.put("finalAttempts", finalAttempts.stream().map(AttemptProjection::toMap).toList());
            report.put("workers", Map.of(
                    "chaosWorker", chaosWorker.toMap(),
                    "steadyWorker", steadyWorker.toMap()
            ));

            Path reportDir = TestingPaths.reportDir("chaos-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("sdk-websocket-lease-expiry-redispatch-chaos-" + timestamp + ".json");
            Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private enum WorkerMode {
        NORMAL,
        DISCONNECT_WITHOUT_RESULT
    }

    private static final class WebSocketWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final URI serverUri;
        private final ChaosConfig config;
        private final WorkerMode mode;
        private final ExecutorService processingExecutor;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean disconnectBudgetConsumed = new AtomicBoolean(false);
        private final AtomicInteger openEvents = new AtomicInteger();
        private final AtomicInteger closeEvents = new AtomicInteger();
        private final AtomicInteger errorEvents = new AtomicInteger();
        private final AtomicInteger disconnectCycles = new AtomicInteger();
        private final AtomicInteger resultSubmissions = new AtomicInteger();
        private final AtomicLong receivedDispatches = new AtomicLong();
        private final Object clientLock = new Object();

        private WorkerSocketClient client;

        private WebSocketWorkerDriver(String workerId,
                                      URI serverUri,
                                      ChaosConfig config,
                                      WorkerMode mode) {
            this.workerId = workerId;
            this.serverUri = serverUri;
            this.config = config;
            this.mode = mode;
            this.processingExecutor = Executors.newSingleThreadExecutor(namedFactory("SdkLeaseChaosWorker-" + workerId));
        }

        private void start() throws Exception {
            WorkerSocketClient nextClient = new WorkerSocketClient(serverUri);
            require(nextClient.connectBlocking(5, TimeUnit.SECONDS),
                    "websocket worker failed to connect: " + workerId + " uri=" + serverUri);
            synchronized (clientLock) {
                client = nextClient;
            }
        }

        private int disconnectCycles() {
            return disconnectCycles.get();
        }

        private long receivedDispatches() {
            return receivedDispatches.get();
        }

        private int resultSubmissions() {
            return resultSubmissions.get();
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
            synchronized (clientLock) {
                if (client != null) {
                    client.closeBlocking();
                    client = null;
                }
            }
            processingExecutor.shutdownNow();
            processingExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        private void onDispatch(JsonObject frame) {
            receivedDispatches.incrementAndGet();
            processingExecutor.submit(() -> handleDispatch(frame));
        }

        private void handleDispatch(JsonObject frame) {
            maybeSleep(config.processingDelayMillis());
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
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("workerId", workerId);
            output.put("mode", mode.name());
            output.put("receivedDispatches", receivedDispatches.get());

            String payload = buildTaskResult(frame, true, "ok", output);
            WorkerSocketClient activeClient;
            synchronized (clientLock) {
                activeClient = client;
            }
            require(activeClient != null && activeClient.isOpen(), "websocket client must be open for " + workerId);
            activeClient.send(payload);
            resultSubmissions.incrementAndGet();
        }

        private final class WorkerSocketClient extends WebSocketClient {
            private WorkerSocketClient(URI serverUri) {
                super(appendWorkerId(serverUri, workerId));
            }

            @Override
            public void onOpen(ServerHandshake handshakedata) {
                openEvents.incrementAndGet();
            }

            @Override
            public void onMessage(String message) {
                JsonObject frame = parseFrame(message);
                if (isTaskDispatchFrame(frame)) {
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

    private record EmbeddedRuntime(MassSdkApplication app, int transportPort, String endpointPath) {
        private URI serverUri() {
            require(transportPort > 0, "websocket server port must be allocated");
            return URI.create("ws://127.0.0.1:" + transportPort + endpointPath);
        }
    }

    private record WorkerRuntimeSnapshot(String workerId,
                                         String mode,
                                         int openEvents,
                                         int closeEvents,
                                         int errorEvents,
                                         int disconnectCycles,
                                         long receivedDispatches,
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

    private record AttemptProjection(int attemptNo,
                                     String attemptId,
                                     String workerId,
                                     String workerContextId,
                                     String batchId,
                                     String status,
                                     String finalReason,
                                     String leaseExpireTime) {
        private static AttemptProjection fromAttempt(TaskMsgAttempt attempt) {
            return new AttemptProjection(
                    attempt.getAttemptNo(),
                    attempt.getAttemptId(),
                    attempt.getWorkerId(),
                    attempt.getWorkerContextId(),
                    attempt.getBatchId(),
                    attempt.getStatus() != null ? attempt.getStatus().name() : null,
                    attempt.getFinalReason() != null ? attempt.getFinalReason().name() : null,
                    attempt.getLeaseExpireTime() != null ? String.valueOf(attempt.getLeaseExpireTime()) : null
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("attemptNo", attemptNo);
            map.put("attemptId", attemptId);
            map.put("workerId", workerId);
            map.put("workerContextId", workerContextId);
            map.put("batchId", batchId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("leaseExpireTime", leaseExpireTime);
            return Map.copyOf(map);
        }
    }

    private record MessageProjection(String messageId,
                                     String status,
                                     String finalReason,
                                     int retryCount,
                                     String latestAttemptWorkerId) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("retryCount", retryCount);
            map.put("latestAttemptWorkerId", latestAttemptWorkerId);
            return Map.copyOf(map);
        }
    }

    private record MessageOutcome(String messageId,
                                  String status,
                                  String finalReason,
                                  int retryCount,
                                  String latestAttemptWorkerId,
                                  List<AttemptProjection> attempts) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("retryCount", retryCount);
            map.put("latestAttemptWorkerId", latestAttemptWorkerId);
            map.put("attempts", attempts.stream().map(AttemptProjection::toMap).toList());
            return Map.copyOf(map);
        }
    }

    private record TaskOutcome(String taskId,
                               String status,
                               String terminalReason,
                               List<MessageOutcome> messages) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskId", taskId);
            map.put("status", status);
            map.put("terminalReason", terminalReason);
            map.put("messages", messages.stream().map(MessageOutcome::toMap).toList());
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
                    intProperty("mass.sdk.chaos.processingDelayMillis", 25),
                    longProperty("mass.sdk.chaos.assignmentRetryDelayMillis", 100L),
                    longProperty("mass.sdk.chaos.leaseWatchdogIntervalSeconds", 1L),
                    longProperty("mass.sdk.chaos.taskMessageLeaseSeconds", 2L),
                    intProperty("mass.sdk.chaos.timeoutSeconds", 25)
            );
            require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            require(config.assignmentRetryDelayMillis > 0, "assignmentRetryDelayMillis must be positive");
            require(config.leaseWatchdogIntervalSeconds > 0, "leaseWatchdogIntervalSeconds must be positive");
            require(config.taskMessageLeaseSeconds > 0, "taskMessageLeaseSeconds must be positive");
            require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
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
                               long chaosDispatches,
                               long steadyDispatches,
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

    private static void waitForCondition(BooleanSupplier condition,
                                         int timeoutSeconds,
                                         String failureMessage) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        require(condition.getAsBoolean(), failureMessage);
    }

    private static void maybeSleep(int processingDelayMillis) {
        if (processingDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(processingDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonObject parseFrame(String message) {
        try {
            return GSON.fromJson(message, JsonObject.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTaskDispatchFrame(JsonObject frame) {
        return frame != null
                && readString(frame, "taskId") != null
                && readString(frame, "messageId") != null
                && !hasBoolean(frame, "success")
                && !isResponseFrame(frame);
    }

    private static boolean isResponseFrame(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    private static boolean hasBoolean(JsonObject frame, String field) {
        if (frame == null || !frame.has(field) || frame.get(field).isJsonNull()) {
            return false;
        }
        try {
            frame.get(field).getAsBoolean();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String buildTaskResult(JsonObject taskFrame,
                                          boolean success,
                                          String detail,
                                          Map<String, Object> output) {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", readString(taskFrame, "messageId"));
        frame.addProperty("workerId", readString(taskFrame, "workerId"));
        frame.addProperty("taskId", readString(taskFrame, "taskId"));
        frame.addProperty("project", readString(taskFrame, "project"));
        frame.addProperty("success", success);
        frame.addProperty("detail", detail);
        frame.add("output", GSON.toJsonTree(output != null ? output : Map.of()));
        return GSON.toJson(frame);
    }

    private static String readString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static URI appendWorkerId(URI serverUri, String workerId) {
        String existingQuery = serverUri.getRawQuery();
        String workerQuery = "workerId=" + workerId.trim();
        String mergedQuery = (existingQuery == null || existingQuery.isBlank())
                ? workerQuery
                : existingQuery + "&" + workerQuery;
        try {
            return new URI(
                    serverUri.getScheme(),
                    serverUri.getRawAuthority(),
                    serverUri.getRawPath(),
                    mergedQuery,
                    serverUri.getRawFragment()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to append workerId to serverUri", ex);
        }
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static long longProperty(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to allocate a free transport port", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
