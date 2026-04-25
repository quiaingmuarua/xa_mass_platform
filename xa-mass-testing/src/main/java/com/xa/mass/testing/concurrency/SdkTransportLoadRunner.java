package com.xa.mass.testing.concurrency;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runnable SDK-driven transport load model using registered worker transports.
 *
 * <p>This harness starts an embedded {@link MassSdkApplication}, registers
 * SDK-native workers, and drives the runtime through either:
 *
 * <ul>
 *   <li>`polling`: real {@link PullWorkerSession} polling/result submission</li>
 *   <li>`websocket`: real gateway WebSocket scheduling/result callbacks</li>
 * </ul>
 *
 * <p>The goal is to keep the setup lighter than Boot-shell E2E while still
 * exercising runtime composition through the public SDK surface.
 *
 * <p>Useful JVM properties:
 *
 * <pre>{@code
 * -Dmass.sdk.load.transport=websocket
 * -Dmass.sdk.load.tasks=16
 * -Dmass.sdk.load.messagesPerTask=32
 * -Dmass.sdk.load.workers=8
 * -Dmass.sdk.load.batchSize=4
 * -Dmass.sdk.load.pollBatchSize=4
 * -Dmass.sdk.load.workerProcessingThreads=2
 * -Dmass.sdk.load.processingDelayMillis=5
 * -Dmass.sdk.load.retryFailureEveryNth=11
 * }</pre>
 */
public final class SdkTransportLoadRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENDPOINT_PATH = "/testing";

    private SdkTransportLoadRunner() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            LoadConfig config = LoadConfig.fromSystemProperties();
            LoadReport report = new ScenarioRunner(config).run();
            System.out.println(report.toConsoleSummary());
            System.out.println("SDK transport load report written to: " + report.reportPath());
        } catch (Throwable t) {
            exitCode = 1;
            throw t;
        } finally {
            // The embedded runtime currently leaves non-daemon transport/runtime threads alive
            // after the scenario completes. Force process exit for this CLI-style load runner so
            // Maven exec and CI-style invocations terminate deterministically.
            if (booleanProperty("mass.sdk.load.forceExit", true)) {
                System.exit(exitCode);
            }
        }
    }

    private static final class ScenarioRunner {
        private final LoadConfig config;
        private final RuntimeMetrics metrics = new RuntimeMetrics();
        private final Map<String, AtomicInteger> deliveryAttempts = new ConcurrentHashMap<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);

        private ScenarioRunner(LoadConfig config) {
            this.config = config;
        }

        private LoadReport run() throws Exception {
            EmbeddedRuntime runtime = buildRuntime(config);
            MassSdkApplication app = runtime.app();
            List<WorkerDriver> workers = new ArrayList<>();
            List<String> taskIds = new ArrayList<>(config.taskCount());
            long wallStartNanos = System.nanoTime();

            try {
                app.start();
                registerWorkers(app, config.workerCount(), config.transport());
                workers = startWorkers(app, runtime);

                for (int i = 0; i < config.taskCount(); i++) {
                    Task task = app.createTask(buildTaskRequest(i));
                    taskIds.add(task.getTid());
                    require(app.approveTask(task.getTid()), "task approval should succeed for " + task.getTid());
                }

                waitForTerminalTasks(app, taskIds);

                long wallNanos = System.nanoTime() - wallStartNanos;
                FinalTaskStats finalTaskStats = collectFinalTaskStats(app, taskIds);
                FinalMessageStats finalMessageStats = collectFinalMessageStats(app, taskIds);
                Path reportPath = writeReport(config, runtime, finalTaskStats, finalMessageStats, wallNanos, metrics.snapshot());

                return new LoadReport(
                        config,
                        runtime.transportPort(),
                        taskIds.size(),
                        finalTaskStats.terminalTasks(),
                        finalTaskStats.terminalReasons(),
                        finalMessageStats.totalMessages(),
                        finalMessageStats.successMessages(),
                        finalMessageStats.failedMessages(),
                        finalMessageStats.expiredMessages(),
                        nanosToMillis(wallNanos),
                        metrics.receiveCycles.sum(),
                        metrics.emptyReceiveCycles.sum(),
                        metrics.receivedDispatchItems.sum(),
                        metrics.resultSubmissions.sum(),
                        metrics.syntheticRetryFailures.sum(),
                        metrics.maxReceivedBatchSize.get(),
                        metrics.maxConcurrentProcessing.get(),
                        nanosToMillis(metrics.totalProcessingNanos.sum()),
                        reportPath
                );
            } finally {
                stopRequested.set(true);
                for (WorkerDriver worker : workers) {
                    worker.close();
                }
                app.stop();
            }
        }

        private EmbeddedRuntime buildRuntime(LoadConfig config) {
            int transportPort = config.transport() == WorkerTransportMode.WEBSOCKET ? findFreePort() : 0;
            MassSdkApplication app = MassSdk.builder()
                    .transportServer(transportPort, ENDPOINT_PATH)
                    .gateway(gateway -> gateway
                            .enabled(config.transport() == WorkerTransportMode.WEBSOCKET)
                            .transportServerEnabled(config.transport() == WorkerTransportMode.WEBSOCKET)
                            .inputQueue(new InMemoryMessageQueue<>("sdk-load-input", String.class))
                            .outputQueue(new InMemoryMessageQueue<>("sdk-load-output", OutboundDelivery.class))
                            .queueMode())
                    .engine(engine -> engine.enabled(true))
                    .build();
            return new EmbeddedRuntime(app, transportPort, ENDPOINT_PATH);
        }

        private void registerWorkers(MassSdkApplication app,
                                     int workerCount,
                                     WorkerTransportMode transportMode) {
            String transportHint = transportMode.transportHint();
            for (int i = 0; i < workerCount; i++) {
                String workerId = "sdk-load-worker-" + i;
                app.registerWorker(WorkerRegistration.builder()
                        .workerId(workerId)
                        .workerGroupId("sdk-load")
                        .supportedProjects(List.of("demoApp"))
                        .transportHint(transportHint)
                        .build());
                app.registerWorkerContext(WorkerContextRegistration.builder()
                        .workerContextId("sdk-load-context-" + i)
                        .workerId(workerId)
                        .project("demoApp")
                        .routingTags(Set.of("us"))
                        .build());
            }
        }

        private List<WorkerDriver> startWorkers(MassSdkApplication app, EmbeddedRuntime runtime) throws Exception {
            List<WorkerDriver> workers = new ArrayList<>(config.workerCount());
            for (int i = 0; i < config.workerCount(); i++) {
                String workerId = "sdk-load-worker-" + i;
                WorkerDriver worker = switch (config.transport()) {
                    case POLLING -> new PollingWorkerDriver(
                            workerId,
                            app.pullWorker(workerId),
                            config,
                            metrics,
                            stopRequested,
                            deliveryAttempts
                    );
                    case WEBSOCKET -> new WebSocketWorkerDriver(
                            workerId,
                            runtime.serverUri(workerId),
                            config,
                            metrics,
                            deliveryAttempts
                    );
                };
                worker.start();
                workers.add(worker);
            }
            return workers;
        }

        private MassTaskCreateRequest buildTaskRequest(int taskIndex) {
            return MassTaskCreateRequest.builder()
                    .userId("sdk-load")
                    .project("demoApp")
                    .taskName("sdk-transport-load-" + taskIndex)
                    .sharedConfig(Map.of(
                            "source", "SdkTransportLoadRunner",
                            "taskIndex", taskIndex,
                            "routingCode", "us"
                    ))
                    .inputs(buildInputs(taskIndex))
                    .batchSize(config.batchSize())
                    .defaultMsgMaxRetryCount(config.maxRetryCount())
                    .openEnded(false)
                    .maxRuntimeSeconds(config.timeoutSeconds())
                    .build();
        }

        private List<Map<String, Object>> buildInputs(int taskIndex) {
            List<Map<String, Object>> inputs = new ArrayList<>(config.messagesPerTask());
            for (int messageIndex = 0; messageIndex < config.messagesPerTask(); messageIndex++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("taskIndex", taskIndex);
                input.put("seq", taskIndex * config.messagesPerTask() + messageIndex);
                input.put("target", "sdk-target-" + taskIndex + "-" + messageIndex);
                inputs.add(input);
            }
            return inputs;
        }

        private void waitForTerminalTasks(MassSdkApplication app, List<String> taskIds) throws Exception {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
            Set<String> pending = new LinkedHashSet<>(taskIds);
            while (!pending.isEmpty()) {
                require(System.nanoTime() < deadlineNanos,
                        "timed out before all SDK load-model tasks reached TERMINAL; pending=" + pending.size());
                pending.removeIf(taskId -> {
                    Task task = app.getTask(taskId);
                    return task != null && task.getStatus() == TaskStatus.TERMINAL;
                });
                if (!pending.isEmpty()) {
                    Thread.sleep(100L);
                }
            }
        }

        private FinalTaskStats collectFinalTaskStats(MassSdkApplication app, List<String> taskIds) {
            Map<String, Long> terminalReasons = new LinkedHashMap<>();
            int terminalTasks = 0;
            for (String taskId : taskIds) {
                Task task = app.getTask(taskId);
                require(task != null, "task should exist: " + taskId);
                require(task.getStatus() == TaskStatus.TERMINAL, "task should be terminal: " + taskId);
                terminalTasks++;
                TaskTerminalReason terminalReason = task.getTerminalReason();
                String terminalReasonName = terminalReason != null ? terminalReason.name() : "<null>";
                terminalReasons.merge(terminalReasonName, 1L, Long::sum);
            }
            return new FinalTaskStats(terminalTasks, terminalReasons);
        }

        private FinalMessageStats collectFinalMessageStats(MassSdkApplication app, List<String> taskIds) {
            long total = 0;
            long success = 0;
            long failed = 0;
            long expired = 0;
            for (String taskId : taskIds) {
                List<TaskMsg> messages = app.getTaskMessages(taskId);
                total += messages.size();
                for (TaskMsg message : messages) {
                    if (message.getStatus() == TaskMsgStatus.SUCCESS) {
                        success++;
                    } else if (message.getStatus() == TaskMsgStatus.FAILED) {
                        failed++;
                    } else if (message.getStatus() == TaskMsgStatus.EXPIRED) {
                        expired++;
                    }
                }
            }
            require(total == (long) config.taskCount() * config.messagesPerTask(),
                    "unexpected logical message count");
            if (config.retryFailureEveryNth() > 0) {
                require(success == total,
                        "retry-enabled SDK load model should converge to success for all logical messages");
            }
            return new FinalMessageStats(total, success, failed, expired);
        }

        private static Path writeReport(LoadConfig config,
                                        EmbeddedRuntime runtime,
                                        FinalTaskStats finalTaskStats,
                                        FinalMessageStats finalMessageStats,
                                        long wallNanos,
                                        RuntimeMetricsSnapshot metrics) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("config", config.toMap());
            report.put("runtime", Map.of(
                    "transport", config.transport().label(),
                    "transportPort", runtime.transportPort(),
                    "endpointPath", runtime.endpointPath()
            ));
            report.put("wallClock", Map.of("totalMillis", nanosToMillis(wallNanos)));
            report.put("tasks", finalTaskStats.toMap());
            report.put("messages", finalMessageStats.toMap());
            report.put("workerMetrics", metrics.toMap());

            Path reportDir = Path.of("xa-mass-testing", "target", "concurrency-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("sdk-transport-load-" + config.transport().label() + "-" + timestamp + ".json");
            Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private interface WorkerDriver extends AutoCloseable {
        void start() throws Exception;
    }

    private static final class PollingWorkerDriver implements WorkerDriver {
        private final String workerId;
        private final PullWorkerSession session;
        private final LoadConfig config;
        private final RuntimeMetrics metrics;
        private final AtomicBoolean stopRequested;
        private final Map<String, AtomicInteger> deliveryAttempts;
        private final ExecutorService processingExecutor;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Thread pollThread;

        private PollingWorkerDriver(String workerId,
                                    PullWorkerSession session,
                                    LoadConfig config,
                                    RuntimeMetrics metrics,
                                    AtomicBoolean stopRequested,
                                    Map<String, AtomicInteger> deliveryAttempts) {
            this.workerId = workerId;
            this.session = session;
            this.config = config;
            this.metrics = metrics;
            this.stopRequested = stopRequested;
            this.deliveryAttempts = deliveryAttempts;
            this.processingExecutor = newProcessingExecutor(workerId, config.workerProcessingThreads());
        }

        @Override
        public void start() {
            session.connect("sdk-load-start");
            pollThread = new Thread(this::runLoop, "SdkPollingWorker-" + workerId + "-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }

        private void runLoop() {
            try {
                while (running.get()) {
                    List<TaskDispatchItem> items = session.poll(config.pollBatchSize());
                    metrics.recordReceiveBatch(items == null ? 0 : items.size());
                    if (items == null || items.isEmpty()) {
                        if (stopRequested.get()) {
                            break;
                        }
                        Thread.sleep(20L);
                        continue;
                    }
                    for (TaskDispatchItem item : items) {
                        processingExecutor.submit(() -> processTaskDispatch(item, workerId, config, metrics, deliveryAttempts,
                                (success, detail, output) -> session.submitResult(item, success, detail, output)));
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
            running.set(false);
            processingExecutor.shutdown();
            try {
                stopped.await(5, TimeUnit.SECONDS);
            } finally {
                session.disconnect("sdk-load-stop");
            }
            if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
            }
        }
    }

    private static final class WebSocketWorkerDriver implements WorkerDriver {
        private final String workerId;
        private final URI serverUri;
        private final LoadConfig config;
        private final RuntimeMetrics metrics;
        private final Map<String, AtomicInteger> deliveryAttempts;
        private final ExecutorService processingExecutor;
        private WorkerSocketClient client;

        private WebSocketWorkerDriver(String workerId,
                                      URI serverUri,
                                      LoadConfig config,
                                      RuntimeMetrics metrics,
                                      Map<String, AtomicInteger> deliveryAttempts) {
            this.workerId = workerId;
            this.serverUri = serverUri;
            this.config = config;
            this.metrics = metrics;
            this.deliveryAttempts = deliveryAttempts;
            this.processingExecutor = newProcessingExecutor(workerId, config.workerProcessingThreads());
        }

        @Override
        public void start() throws Exception {
            client = new WorkerSocketClient(serverUri);
            require(client.connectBlocking(5, TimeUnit.SECONDS),
                    "websocket worker failed to connect: " + workerId + " uri=" + serverUri);
        }

        @Override
        public void close() throws Exception {
            if (client != null) {
                client.closeBlocking();
            }
            processingExecutor.shutdown();
            if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
            }
        }

        private final class WorkerSocketClient extends WebSocketClient {
            private WorkerSocketClient(URI serverUri) {
                super(appendWorkerId(serverUri, workerId));
            }

            @Override
            public void onOpen(ServerHandshake handshakedata) {
                // No-op.
            }

            @Override
            public void onMessage(String message) {
                JsonObject frame = parseFrame(message);
                if (!isTaskDispatchFrame(frame)) {
                    return;
                }
                metrics.recordReceiveBatch(1);
                processingExecutor.submit(() -> processTaskDispatch(
                        frame,
                        workerId,
                        config,
                        metrics,
                        deliveryAttempts,
                        (success, detail, output) -> {
                            send(buildTaskResult(frame, success, detail, output));
                            return true;
                        }
                ));
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                // No-op.
            }

            @Override
            public void onError(Exception ex) {
                throw new RuntimeException("WebSocket worker error for " + workerId, ex);
            }
        }
    }

    @FunctionalInterface
    private interface ResultSubmitter {
        boolean submit(boolean success, String detail, Map<String, Object> output);
    }

    private static void processTaskDispatch(TaskDispatchItem item,
                                            String workerId,
                                            LoadConfig config,
                                            RuntimeMetrics metrics,
                                            Map<String, AtomicInteger> deliveryAttempts,
                                            ResultSubmitter submitter) {
        int concurrent = metrics.onProcessingStart();
        try {
            maybeSleep(config.processingDelayMillis());
            String messageId = item.getMessageId();
            int attemptNo = deliveryAttempts
                    .computeIfAbsent(messageId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            int seq = readSeq(item.getInput());
            boolean syntheticRetry = shouldInjectRetry(config, seq, attemptNo);
            if (syntheticRetry) {
                metrics.syntheticRetryFailures.increment();
            }
            long startNanos = System.nanoTime();
            boolean submitted = submitter.submit(
                    !syntheticRetry,
                    syntheticRetry ? "synthetic retryable failure" : "ok",
                    Map.of(
                            "workerId", workerId,
                            "logicalAttempt", attemptNo,
                            "seq", seq
                    )
            );
            metrics.onProcessingComplete(System.nanoTime() - startNanos, concurrent, submitted);
            require(submitted, "result submission should succeed for " + messageId);
        } finally {
            metrics.onProcessingFinish();
        }
    }

    private static void processTaskDispatch(JsonObject frame,
                                            String workerId,
                                            LoadConfig config,
                                            RuntimeMetrics metrics,
                                            Map<String, AtomicInteger> deliveryAttempts,
                                            ResultSubmitter submitter) {
        int concurrent = metrics.onProcessingStart();
        try {
            maybeSleep(config.processingDelayMillis());
            String messageId = readString(frame, "messageId");
            int attemptNo = deliveryAttempts
                    .computeIfAbsent(messageId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            int seq = readSeq(readObject(frame, "input"));
            boolean syntheticRetry = shouldInjectRetry(config, seq, attemptNo);
            if (syntheticRetry) {
                metrics.syntheticRetryFailures.increment();
            }
            long startNanos = System.nanoTime();
            boolean submitted = submitter.submit(
                    !syntheticRetry,
                    syntheticRetry ? "synthetic retryable failure" : "ok",
                    Map.of(
                            "workerId", workerId,
                            "logicalAttempt", attemptNo,
                            "seq", seq
                    )
            );
            metrics.onProcessingComplete(System.nanoTime() - startNanos, concurrent, submitted);
            require(submitted, "websocket result submission should succeed for " + messageId);
        } finally {
            metrics.onProcessingFinish();
        }
    }

    private static boolean shouldInjectRetry(LoadConfig config, int seq, int attemptNo) {
        return config.retryFailureEveryNth() > 0
                && seq > 0
                && seq % config.retryFailureEveryNth() == 0
                && attemptNo == 1;
    }

    private static ExecutorService newProcessingExecutor(String workerId, int threads) {
        return Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "SdkWorker-" + workerId + "-processor");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void maybeSleep(int processingDelayMillis) {
        if (processingDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(processingDelayMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int readSeq(Map<String, Object> input) {
        Object seq = input == null ? null : input.get("seq");
        if (seq instanceof Number number) {
            return number.intValue();
        }
        return -1;
    }

    private static int readSeq(JsonObject input) {
        if (input == null || !input.has("seq") || input.get("seq").isJsonNull()) {
            return -1;
        }
        try {
            return input.get("seq").getAsInt();
        } catch (Exception ignored) {
            return -1;
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

    private static JsonObject readObject(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(field);
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

    private record EmbeddedRuntime(MassSdkApplication app, int transportPort, String endpointPath) {
        private URI serverUri(String workerId) {
            require(transportPort > 0, "websocket server port must be allocated");
            return URI.create("ws://127.0.0.1:" + transportPort + endpointPath + "?workerId=" + workerId);
        }
    }

    private static final class RuntimeMetrics {
        private final LongAdder receiveCycles = new LongAdder();
        private final LongAdder emptyReceiveCycles = new LongAdder();
        private final LongAdder receivedDispatchItems = new LongAdder();
        private final LongAdder resultSubmissions = new LongAdder();
        private final LongAdder syntheticRetryFailures = new LongAdder();
        private final LongAdder totalProcessingNanos = new LongAdder();
        private final AtomicInteger concurrentProcessing = new AtomicInteger();
        private final AtomicInteger maxConcurrentProcessing = new AtomicInteger();
        private final LongAccumulator maxReceivedBatchSize = new LongAccumulator(Long::max, 0);

        private void recordReceiveBatch(int itemCount) {
            receiveCycles.increment();
            if (itemCount <= 0) {
                emptyReceiveCycles.increment();
                return;
            }
            receivedDispatchItems.add(itemCount);
            maxReceivedBatchSize.accumulate(itemCount);
        }

        private int onProcessingStart() {
            int active = concurrentProcessing.incrementAndGet();
            maxConcurrentProcessing.updateAndGet(current -> Math.max(current, active));
            return active;
        }

        private void onProcessingComplete(long durationNanos, int concurrent, boolean submitted) {
            totalProcessingNanos.add(durationNanos);
            if (submitted) {
                resultSubmissions.increment();
            }
            maxConcurrentProcessing.updateAndGet(current -> Math.max(current, concurrent));
        }

        private void onProcessingFinish() {
            concurrentProcessing.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }

        private RuntimeMetricsSnapshot snapshot() {
            return new RuntimeMetricsSnapshot(
                    receiveCycles.sum(),
                    emptyReceiveCycles.sum(),
                    receivedDispatchItems.sum(),
                    resultSubmissions.sum(),
                    syntheticRetryFailures.sum(),
                    nanosToMillis(totalProcessingNanos.sum()),
                    maxReceivedBatchSize.get(),
                    maxConcurrentProcessing.get()
            );
        }
    }

    private record RuntimeMetricsSnapshot(long receiveCycles,
                                          long emptyReceiveCycles,
                                          long receivedDispatchItems,
                                          long resultSubmissions,
                                          long syntheticRetryFailures,
                                          double totalProcessingMillis,
                                          long maxReceivedBatchSize,
                                          int maxConcurrentProcessing) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "receiveCycles", receiveCycles,
                    "emptyReceiveCycles", emptyReceiveCycles,
                    "receivedDispatchItems", receivedDispatchItems,
                    "resultSubmissions", resultSubmissions,
                    "syntheticRetryFailures", syntheticRetryFailures,
                    "totalProcessingMillis", totalProcessingMillis,
                    "maxReceivedBatchSize", maxReceivedBatchSize,
                    "maxConcurrentProcessing", maxConcurrentProcessing
            );
        }
    }

    private record FinalTaskStats(int terminalTasks, Map<String, Long> terminalReasons) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "terminalTasks", terminalTasks,
                    "terminalReasons", terminalReasons
            );
        }
    }

    private record FinalMessageStats(long totalMessages,
                                     long successMessages,
                                     long failedMessages,
                                     long expiredMessages) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "totalMessages", totalMessages,
                    "successMessages", successMessages,
                    "failedMessages", failedMessages,
                    "expiredMessages", expiredMessages
            );
        }
    }

    private enum WorkerTransportMode {
        POLLING("polling", WorkerTransportHints.POLLING),
        WEBSOCKET("websocket", WorkerTransportHints.REALTIME);

        private final String label;
        private final String transportHint;

        WorkerTransportMode(String label, String transportHint) {
            this.label = label;
            this.transportHint = transportHint;
        }

        private String label() {
            return label;
        }

        private String transportHint() {
            return transportHint;
        }

        private static WorkerTransportMode fromProperty(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return POLLING;
            }
            String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "poll", "pull", "polling" -> POLLING;
                case "websocket", "ws", "realtime", "push" -> WEBSOCKET;
                default -> throw new IllegalArgumentException("Unsupported mass.sdk.load.transport: " + rawValue);
            };
        }
    }

    private record LoadConfig(WorkerTransportMode transport,
                              int taskCount,
                              int messagesPerTask,
                              int workerCount,
                              int batchSize,
                              int pollBatchSize,
                              int workerProcessingThreads,
                              int processingDelayMillis,
                              int retryFailureEveryNth,
                              int maxRetryCount,
                              int timeoutSeconds) {
        private static LoadConfig fromSystemProperties() {
            LoadConfig config = new LoadConfig(
                    WorkerTransportMode.fromProperty(System.getProperty("mass.sdk.load.transport")),
                    intProperty("mass.sdk.load.tasks", 16),
                    intProperty("mass.sdk.load.messagesPerTask", 32),
                    intProperty("mass.sdk.load.workers", 8),
                    intProperty("mass.sdk.load.batchSize", 4),
                    intProperty("mass.sdk.load.pollBatchSize", 4),
                    intProperty("mass.sdk.load.workerProcessingThreads", 2),
                    intProperty("mass.sdk.load.processingDelayMillis", 5),
                    intProperty("mass.sdk.load.retryFailureEveryNth", 0),
                    intProperty("mass.sdk.load.maxRetryCount", 2),
                    intProperty("mass.sdk.load.timeoutSeconds", 60)
            );
            require(config.taskCount > 0, "taskCount must be positive");
            require(config.messagesPerTask > 0, "messagesPerTask must be positive");
            require(config.workerCount > 0, "workerCount must be positive");
            require(config.batchSize > 0, "batchSize must be positive");
            require(config.pollBatchSize > 0, "pollBatchSize must be positive");
            require(config.workerProcessingThreads > 0, "workerProcessingThreads must be positive");
            require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            if (config.retryFailureEveryNth > 0) {
                require(config.maxRetryCount > 0,
                        "maxRetryCount must be positive when retryFailureEveryNth is enabled");
            }
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("transport", transport.label());
            values.put("taskCount", taskCount);
            values.put("messagesPerTask", messagesPerTask);
            values.put("workerCount", workerCount);
            values.put("batchSize", batchSize);
            values.put("pollBatchSize", pollBatchSize);
            values.put("workerProcessingThreads", workerProcessingThreads);
            values.put("processingDelayMillis", processingDelayMillis);
            values.put("retryFailureEveryNth", retryFailureEveryNth);
            values.put("maxRetryCount", maxRetryCount);
            values.put("timeoutSeconds", timeoutSeconds);
            return Map.copyOf(values);
        }
    }

    private record LoadReport(LoadConfig config,
                              int transportPort,
                              int createdTasks,
                              int terminalTasks,
                              Map<String, Long> terminalReasons,
                              long totalMessages,
                              long successMessages,
                              long failedMessages,
                              long expiredMessages,
                              double wallClockMillis,
                              long receiveCycles,
                              long emptyReceiveCycles,
                              long receivedDispatchItems,
                              long resultSubmissions,
                              long syntheticRetryFailures,
                              long maxReceivedBatchSize,
                              int maxConcurrentProcessing,
                              double totalProcessingMillis,
                              Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkTransportLoad transport=%s port=%d tasks=%d terminal=%d reasons=%s messages=%d success=%d "
                            + "failed=%d expired=%d wall=%.3fms receiveCycles=%d emptyReceiveCycles=%d dispatches=%d "
                            + "results=%d syntheticRetries=%d maxReceiveBatch=%d maxConcurrentProcessing=%d report=%s",
                    config.transport().label(),
                    transportPort,
                    createdTasks,
                    terminalTasks,
                    terminalReasons,
                    totalMessages,
                    successMessages,
                    failedMessages,
                    expiredMessages,
                    wallClockMillis,
                    receiveCycles,
                    emptyReceiveCycles,
                    receivedDispatchItems,
                    resultSubmissions,
                    syntheticRetryFailures,
                    maxReceivedBatchSize,
                    maxConcurrentProcessing,
                    reportPath
            );
        }
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
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
