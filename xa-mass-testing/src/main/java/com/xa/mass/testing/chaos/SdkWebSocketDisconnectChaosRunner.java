package com.xa.mass.testing.chaos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.transport.model.TransportOutboundMessage;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Runnable chaos probe for WebSocket worker disconnect/reconnect behavior.
 *
 * <p>This scenario uses the public SDK surface to start an embedded runtime,
 * register WebSocket workers, force one worker to disconnect after receiving a
 * real task-dispatch frame, reconnect, submit the delayed result, and then
 * prove that later tasks still dispatch successfully.
 *
 * <p>Useful JVM properties:
 *
 * <pre>{@code
 * -Dmass.sdk.chaos.messagesPerTask=1
 * -Dmass.sdk.chaos.processingDelayMillis=25
 * -Dmass.sdk.chaos.reconnectDelayMillis=800
 * -Dmass.sdk.chaos.assignmentRetryDelayMillis=100
 * -Dmass.sdk.chaos.leaseWatchdogIntervalSeconds=1
 * -Dmass.sdk.chaos.timeoutSeconds=25
 * }</pre>
 */
public final class SdkWebSocketDisconnectChaosRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
            System.out.println("SDK websocket chaos report written to: " + report.reportPath());
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
                        runtime.serverUri(CHAOS_WORKER_ID),
                        config,
                        true
                );
                steadyWorker = new WebSocketWorkerDriver(
                        STEADY_WORKER_ID,
                        runtime.serverUri(STEADY_WORKER_ID),
                        config,
                        false
                );
                WebSocketWorkerDriver activeChaosWorker = chaosWorker;
                chaosWorker.start();
                steadyWorker.start();

                waitForWorkerOnline(app, CHAOS_WORKER_ID, "chaos worker should be online before scenario starts");
                waitForWorkerOnline(app, STEADY_WORKER_ID, "steady worker should be online before scenario starts");

                Task chaosTask = createTargetedTask(app, "sdk-chaos-disconnect-inflight", CHAOS_WORKER_ID);
                waitForCondition(
                        () -> activeChaosWorker.disconnectCycles() >= 1,
                        config.timeoutSeconds(),
                        "chaos worker should disconnect after receiving a dispatch"
                );
                waitForCondition(
                        () -> !app.isWorkerOnline(CHAOS_WORKER_ID),
                        config.timeoutSeconds(),
                        "runtime should observe chaos worker offline after disconnect"
                );

                Task steadyTask = createTargetedTask(app, "sdk-chaos-steady-control", STEADY_WORKER_ID);
                TaskOutcome steadyOutcome = waitForTerminalTask(app, steadyTask.getTid(), "steady control task must converge");

                waitForCondition(
                        () -> activeChaosWorker.reconnectCycles() >= 1 && app.isWorkerOnline(CHAOS_WORKER_ID),
                        config.timeoutSeconds(),
                        "chaos worker should reconnect and become online again"
                );
                TaskOutcome chaosOutcome = waitForTerminalTask(app, chaosTask.getTid(), "chaos task must converge after reconnect");

                Task followUpTask = createTargetedTask(app, "sdk-chaos-follow-up", CHAOS_WORKER_ID);
                TaskOutcome followUpOutcome = waitForTerminalTask(app, followUpTask.getTid(), "follow-up task must converge after reconnect");

                require(steadyOutcome.allMessagesSuccessful(), "steady control task should succeed");
                require(chaosOutcome.allMessagesSuccessful(), "chaos task should succeed after delayed result submission");
                require(followUpOutcome.allMessagesSuccessful(), "follow-up task should succeed after reconnect");
                require(chaosWorker.delayedResultSubmissions() >= 1,
                        "chaos worker should submit at least one delayed result after reconnect");

                Path reportPath = writeReport(
                        config,
                        runtime,
                        chaosOutcome,
                        steadyOutcome,
                        followUpOutcome,
                        chaosWorker.snapshot(),
                        steadyWorker.snapshot(),
                        System.nanoTime() - wallStartNanos
                );

                return new ChaosReport(
                        runtime.transportPort(),
                        chaosOutcome.taskId(),
                        steadyOutcome.taskId(),
                        followUpOutcome.taskId(),
                        chaosWorker.disconnectCycles(),
                        chaosWorker.reconnectCycles(),
                        chaosWorker.delayedResultSubmissions(),
                        steadyOutcome.successCount(),
                        chaosOutcome.successCount(),
                        followUpOutcome.successCount(),
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
                    .transport(transport -> transport
                            .webSocketAdapter(webSocket -> webSocket
                                    .server(transportPort, ENDPOINT_PATH)
                                    .enabled(true)
                                    .serverEnabled(true))
                            .inputQueue(new InMemoryMessageQueue<>("sdk-chaos-input", String.class))
                            .outputQueue(new InMemoryMessageQueue<>("sdk-chaos-output", com.xa.mass.transport.model.TransportOutboundMessage.class))
                            .queueMode())
                    .engine(engine -> engine
                            .enabled(true)
                            .workerThreads(4)
                            .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                            .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds()))
                    .build();
            return new EmbeddedRuntime(app, transportPort, ENDPOINT_PATH);
        }

        private void registerWorker(MassSdkApplication app, String workerId) {
            app.registerWorker(WorkerRegistration.builder()
                    .workerId(workerId)
                    .workerGroupId("sdk-chaos")
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

        private Task createTargetedTask(MassSdkApplication app, String taskName, String targetWorkerId) {
            Task task = app.createTask(MassTaskCreateRequest.builder()
                    .userId("sdk-chaos")
                    .project(PROJECT_CODE)
                    .taskName(taskName)
                    .sharedConfig(Map.of(
                            TaskSharedConfig.TARGET_WORKER_ID, targetWorkerId,
                            TaskSharedConfig.ROUTING_CODE, ROUTING_CODE,
                            "source", "SdkWebSocketDisconnectChaosRunner"
                    ))
                    .inputs(buildInputs(taskName))
                    .batchSize(1)
                    .defaultMsgMaxRetryCount(1)
                    .openEnded(false)
                    .maxRuntimeSeconds(config.timeoutSeconds())
                    .build());
            require(app.approveTask(task.getTid()), "task approval should succeed for " + task.getTid());
            return task;
        }

        private List<Map<String, Object>> buildInputs(String taskName) {
            List<Map<String, Object>> inputs = new ArrayList<>(config.messagesPerTask());
            for (int i = 0; i < config.messagesPerTask(); i++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("seq", i);
                input.put("taskName", taskName);
                input.put("target", taskName + "-target-" + i);
                inputs.add(input);
            }
            return inputs;
        }

        private void waitForWorkerOnline(MassSdkApplication app, String workerId, String failureMessage) throws Exception {
            waitForCondition(() -> app.isWorkerOnline(workerId), config.timeoutSeconds(), failureMessage);
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
            List<TaskMsg> messages = app.getTaskMessages(taskId, config.messagesPerTask());
            List<MessageOutcome> messageOutcomes = new ArrayList<>(messages.size());
            for (TaskMsg message : messages) {
                List<TaskMsgAttempt> attempts = app.getTaskMessageAttempts(taskId, message.getMessageId());
                messageOutcomes.add(new MessageOutcome(
                        message.getMessageId(),
                        message.getStatus() != null ? message.getStatus().name() : null,
                        message.getFinalReason() != null ? message.getFinalReason().name() : null,
                        message.getLatestAttemptWorkerId(),
                        attempts.size(),
                        attempts.stream()
                                .map(TaskMsgAttempt::getStatus)
                                .filter(Objects::nonNull)
                                .map(Enum::name)
                                .toList(),
                        attempts.stream()
                                .map(TaskMsgAttempt::getFinalReason)
                                .filter(Objects::nonNull)
                                .map(Enum::name)
                                .toList()
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
                                 TaskOutcome chaosOutcome,
                                 TaskOutcome steadyOutcome,
                                 TaskOutcome followUpOutcome,
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
            report.put("phases", Map.of(
                    "chaosTask", chaosOutcome.toMap(),
                    "steadyControlTask", steadyOutcome.toMap(),
                    "followUpTask", followUpOutcome.toMap()
            ));
            report.put("workers", Map.of(
                    "chaosWorker", chaosWorker.toMap(),
                    "steadyWorker", steadyWorker.toMap()
            ));

            Path reportDir = TestingPaths.reportDir("chaos-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("sdk-websocket-disconnect-chaos-" + timestamp + ".json");
            Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private static final class WebSocketWorkerDriver implements AutoCloseable {
        private final String workerId;
        private final URI serverUri;
        private final ChaosConfig config;
        private final boolean disconnectBeforeFirstResult;
        private final ExecutorService processingExecutor;
        private final ScheduledExecutorService reconnectExecutor;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean disconnectBudgetConsumed = new AtomicBoolean(false);
        private final AtomicInteger openEvents = new AtomicInteger();
        private final AtomicInteger closeEvents = new AtomicInteger();
        private final AtomicInteger errorEvents = new AtomicInteger();
        private final AtomicInteger disconnectCycles = new AtomicInteger();
        private final AtomicInteger reconnectCycles = new AtomicInteger();
        private final AtomicInteger delayedResultSubmissions = new AtomicInteger();
        private final AtomicInteger normalResultSubmissions = new AtomicInteger();
        private final AtomicLong receivedDispatches = new AtomicLong();
        private final Object clientLock = new Object();

        private WorkerSocketClient client;

        private WebSocketWorkerDriver(String workerId,
                                      URI serverUri,
                                      ChaosConfig config,
                                      boolean disconnectBeforeFirstResult) {
            this.workerId = workerId;
            this.serverUri = serverUri;
            this.config = config;
            this.disconnectBeforeFirstResult = disconnectBeforeFirstResult;
            this.processingExecutor = Executors.newSingleThreadExecutor(namedFactory("SdkChaosWorker-" + workerId + "-processor"));
            this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(namedFactory("SdkChaosWorker-" + workerId + "-reconnect"));
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
            reconnectExecutor.shutdownNow();
            synchronized (clientLock) {
                if (client != null) {
                    client.closeBlocking();
                    client = null;
                }
            }
            processingExecutor.shutdownNow();
            reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS);
            processingExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        private void connectNewClient(boolean reconnect) throws Exception {
            WorkerSocketClient nextClient = new WorkerSocketClient(serverUri);
            require(nextClient.connectBlocking(5, TimeUnit.SECONDS),
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
            processingExecutor.submit(() -> handleDispatch(frame));
        }

        private void handleDispatch(JsonObject frame) {
            maybeSleep(config.processingDelayMillis());
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

                reconnectExecutor.schedule(() -> reconnectAndSubmit(frame), config.reconnectDelayMillis(), TimeUnit.MILLISECONDS);
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
                maybeSleep(config.processingDelayMillis());
                sendTaskResult(frame, true);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to reconnect and submit delayed result for " + workerId, e);
            }
        }

        private void sendTaskResult(JsonObject frame, boolean delayedAfterReconnect) {
            String detail = delayedAfterReconnect ? "delayed result after reconnect" : "ok";
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("workerId", workerId);
            output.put("chaos", disconnectBeforeFirstResult);
            output.put("delayedAfterReconnect", delayedAfterReconnect);
            output.put("receivedDispatches", receivedDispatches.get());

            String payload = buildTaskResult(frame, true, detail, output);
            WorkerSocketClient activeClient;
            synchronized (clientLock) {
                activeClient = client;
            }
            require(activeClient != null && activeClient.isOpen(), "websocket client must be open for " + workerId);
            activeClient.send(payload);
            if (delayedAfterReconnect) {
                delayedResultSubmissions.incrementAndGet();
            } else {
                normalResultSubmissions.incrementAndGet();
            }
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
        private URI serverUri(String workerId) {
            require(transportPort > 0, "websocket server port must be allocated");
            return URI.create("ws://127.0.0.1:" + transportPort + endpointPath + "?workerId=" + workerId);
        }
    }

    private record WorkerRuntimeSnapshot(String workerId,
                                         boolean chaosWorker,
                                         int openEvents,
                                         int closeEvents,
                                         int errorEvents,
                                         int disconnectCycles,
                                         int reconnectCycles,
                                         long receivedDispatches,
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

    private record MessageOutcome(String messageId,
                                  String status,
                                  String finalReason,
                                  String latestAttemptWorkerId,
                                  int attemptCount,
                                  List<String> attemptStatuses,
                                  List<String> attemptFinalReasons) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("latestAttemptWorkerId", latestAttemptWorkerId);
            map.put("attemptCount", attemptCount);
            map.put("attemptStatuses", attemptStatuses);
            map.put("attemptFinalReasons", attemptFinalReasons);
            return Map.copyOf(map);
        }
    }

    private record TaskOutcome(String taskId,
                               String status,
                               String terminalReason,
                               List<MessageOutcome> messages) {
        private boolean allMessagesSuccessful() {
            return !messages.isEmpty() && messages.stream().allMatch(message -> TaskMsgStatus.SUCCESS.name().equals(message.status()));
        }

        private long successCount() {
            return messages.stream().filter(message -> TaskMsgStatus.SUCCESS.name().equals(message.status())).count();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskId", taskId);
            map.put("status", status);
            map.put("terminalReason", terminalReason);
            map.put("messages", messages.stream().map(MessageOutcome::toMap).toList());
            return Map.copyOf(map);
        }
    }

    private record ChaosConfig(int messagesPerTask,
                               int processingDelayMillis,
                               long reconnectDelayMillis,
                               long assignmentRetryDelayMillis,
                               long leaseWatchdogIntervalSeconds,
                               int timeoutSeconds) {
        private static ChaosConfig fromSystemProperties() {
            ChaosConfig config = new ChaosConfig(
                    intProperty("mass.sdk.chaos.messagesPerTask", 1),
                    intProperty("mass.sdk.chaos.processingDelayMillis", 25),
                    longProperty("mass.sdk.chaos.reconnectDelayMillis", 800L),
                    longProperty("mass.sdk.chaos.assignmentRetryDelayMillis", 100L),
                    longProperty("mass.sdk.chaos.leaseWatchdogIntervalSeconds", 1L),
                    intProperty("mass.sdk.chaos.timeoutSeconds", 25)
            );
            require(config.messagesPerTask > 0, "messagesPerTask must be positive");
            require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            require(config.reconnectDelayMillis >= 0, "reconnectDelayMillis must not be negative");
            require(config.assignmentRetryDelayMillis > 0, "assignmentRetryDelayMillis must be positive");
            require(config.leaseWatchdogIntervalSeconds > 0, "leaseWatchdogIntervalSeconds must be positive");
            require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messagesPerTask", messagesPerTask);
            map.put("processingDelayMillis", processingDelayMillis);
            map.put("reconnectDelayMillis", reconnectDelayMillis);
            map.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            map.put("leaseWatchdogIntervalSeconds", leaseWatchdogIntervalSeconds);
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
                    "SdkWebSocketChaos port=%d chaosTask=%s steadyTask=%s followUpTask=%s disconnects=%d reconnects=%d "
                            + "delayedResults=%d steadySuccess=%d chaosSuccess=%d followUpSuccess=%d wall=%.3fms report=%s",
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

