package com.xa.mass.testing.concurrency;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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
 *   <li>`websocket`: real WebSocket-adapter scheduling/result callbacks</li>
 *   <li>`socket`: real raw socket-adapter scheduling/result callbacks</li>
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
    private static final Gson FRAME_GSON = new GsonBuilder().create();
    private static final String ENDPOINT_PATH = "/testing";
    private static final String TASK_EVENT_CODE = "demo.dispatch";

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
            // The embedded runtime currently leaves non-daemon transport_runtime threads alive
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
                bootstrapCatalog(app);
                registerWorkers(app, config.workerCount(), config.transport());
                workers = startWorkers(app, runtime);
                waitForRealtimeWorkersReady(app, workers);
                for (WorkerDriver worker : workers) {
                    worker.startReceiving();
                }

                for (int i = 0; i < config.taskCount(); i++) {
                    TaskShellSnapshot task = createTask(app, buildTaskRequest(i));
                    taskIds.add(task.getTaskId());
                    require(app.approveTask(task.getTaskId()), "task approval should succeed for " + task.getTaskId());
                }

                waitForTerminalTasks(app, taskIds);

                long wallNanos = System.nanoTime() - wallStartNanos;
                FinalTaskStats finalTaskStats = collectFinalTaskStats(app, taskIds);
                FinalWorkStats finalWorkStats = collectFinalWorkStats(runtime, taskIds);
                DeliveryQueueSnapshot deliveryQueue = collectDeliveryQueueSnapshot(app, finalWorkStats.totalWorkItems());
                Path reportPath = writeReport(config, runtime, finalTaskStats, finalWorkStats,
                        deliveryQueue, wallNanos, metrics.snapshot());

                return new LoadReport(
                        config,
                        runtime.boundTransportPort(config.transport()),
                        taskIds.size(),
                        finalTaskStats.terminalTasks(),
                        finalTaskStats.terminalReasons(),
                        finalWorkStats.totalWorkItems(),
                        finalWorkStats.successWorkItems(),
                        finalWorkStats.failedWorkItems(),
                        finalWorkStats.expiredWorkItems(),
                        nanosToMillis(wallNanos),
                        metrics.receiveCycles.sum(),
                        metrics.emptyReceiveCycles.sum(),
                        metrics.receivedDispatchItems.sum(),
                        metrics.resultSubmissions.sum(),
                        metrics.syntheticRetryFailures.sum(),
                        deliveryQueue.queuedItems(),
                        deliveryQueue.enqueuedItems(),
                        deliveryQueue.drainedItems(),
                        deliveryQueue.backpressureRejectedItems(),
                        deliveryQueue.oldestQueuedAgeMillis(),
                        deliveryQueue.directSentItems(),
                        deliveryQueue.directOfflineItems(),
                        deliveryQueue.directFailedItems(),
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
            InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
            TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
            MassSdkApplication app = MassSdk.builder()
                    .transport(transport -> transport
                            .webSocketAdapter(webSocket -> webSocket
                                    .server(transportPort, ENDPOINT_PATH)
                                    .enabled(config.transport() == WorkerTransportMode.WEBSOCKET)
                                    .serverEnabled(config.transport() == WorkerTransportMode.WEBSOCKET))
                            .socketAdapter(socket -> socket
                                    .server(transportPort)
                                    .enabled(config.transport() == WorkerTransportMode.SOCKET)
                                    .serverEnabled(config.transport() == WorkerTransportMode.SOCKET))
                            .inputQueue(new InMemoryMessageQueue<>("sdk-load-input", String.class))
                            .outputQueue(new InMemoryMessageQueue<>("sdk-load-output", com.xa.mass.transport.model.TransportOutboundMessage.class))
                            .queueMode())
                    .engine(engine -> engine.enabled(true)
                            .taskStorage(taskStorage)
                            .taskDetailStore(taskStorage)
                            .taskWorkRuntime(taskWorkRuntime))
                    .build();
            return new EmbeddedRuntime(app, taskWorkRuntime, transportPort, ENDPOINT_PATH);
        }

        private void bootstrapCatalog(MassSdkApplication app) {
            if (app.getEvent(TASK_EVENT_CODE) == null) {
                app.registerEventDefinition(EventDefinition.builder()
                        .code(TASK_EVENT_CODE)
                        .name("Demo Dispatch")
                        .description("Dispatch a generic SDK transport load work item.")
                        .payloadTypes(List.of(PayloadType.JSON))
                        .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                        .projectCodes(List.of("demoApp"))
                        .build());
            }
            if (app.getProject("demoApp") == null) {
                app.registerProject(ProjectDefinition.builder()
                        .code("demoApp")
                        .name("Demo App")
                        .description("SDK transport load project.")
                        .eventCodes(List.of(TASK_EVENT_CODE))
                        .build());
            }
            app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                    .groupId("sdk-load")
                    .eventBindings(List.of(WorkerEventBinding.builder()
                            .eventCode(TASK_EVENT_CODE)
                            .projectCodes(List.of("demoApp"))
                            .build()))
                    .build());
        }

        private void registerWorkers(MassSdkApplication app,
                                     int workerCount,
                                     WorkerTransportMode transportMode) {
            String transportHint = transportMode.transportHint();
            String adapterNodeId = transportMode.adapterId() + "-load-node";
            app.registerAdapterNode(AdapterNodeRegistration.builder()
                    .adapterNodeId(adapterNodeId)
                    .adapterType(transportHint)
                    .endpointId(adapterNodeId)
                    .build());
            app.bindNodeGroup(NodeGroupBindingRegistration.builder()
                    .adapterNodeId(adapterNodeId)
                    .workerGroupId("sdk-load")
                    .build());
            for (int i = 0; i < workerCount; i++) {
                String workerId = "sdk-load-worker-" + i;
                app.registerWorker(WorkerRegistration.builder()
                        .workerId(workerId)
                        .adapterNodeId(adapterNodeId)
                        .workerGroupId("sdk-load")
                        .transportHint(transportHint)
                        .adapterId(transportMode.adapterId())
                        .attributes(Map.of("routingTags", "us", "country", "us"))
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
                    case SOCKET -> new SocketWorkerDriver(
                            workerId,
                            runtime.socketPort(),
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

        private void waitForRealtimeWorkersReady(MassSdkApplication app, List<WorkerDriver> workers)
                throws InterruptedException {
            if (!config.transport().usesServer()) {
                return;
            }
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadlineNanos) {
                if (activeSessionCount(app, config.transport().adapterId()) >= config.workerCount()) {
                    return;
                }
                for (WorkerDriver worker : workers) {
                    worker.refreshReadySignal();
                }
                Thread.sleep(25L);
            }
            require(activeSessionCount(app, config.transport().adapterId()) >= config.workerCount(),
                    "realtime workers did not become ready for adapter=" + config.transport().adapterId()
                            + " sessions=" + runtimeDiagnostics(app).listSessions());
        }

        private int activeSessionCount(MassSdkApplication app, String adapterId) {
            int active = 0;
            for (Map<String, Object> session : runtimeDiagnostics(app).listSessions()) {
                Object connections = session.get("connections");
                if (!(connections instanceof List<?> list)) {
                    continue;
                }
                for (Object connection : list) {
                    if (!(connection instanceof Map<?, ?> connectionInfo)) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(connectionInfo.get("active"))
                            && Objects.equals(adapterId, connectionInfo.get("adapterId"))) {
                        active++;
                    }
                }
            }
            return active;
        }

        private TaskCreateSpec buildTaskRequest(int taskIndex) {
            return new TaskCreateSpec(
                    MassTaskShellCreateRequest.builder()
                            .userId("sdk-load")
                            .project("demoApp")
                            .sourceRef("sdk-transport-load-" + taskIndex)
                            .executionSpec(taskExecutionSpec(
                                    config.workloadClass(),
                                    config.batchSize(),
                                    config.timeoutSeconds(),
                                    config.maxRetryCount()
                            ))
                            .sharedConfig(Map.of(
                                    "source", "SdkTransportLoadRunner",
                                    "taskIndex", taskIndex,
                                    "routingCode", "us"
                            ))
                            .build(),
                    new ArrayList<>(buildInputs(taskIndex)),
                    false
            );
        }

        private TaskShellSnapshot createTask(MassSdkApplication app, TaskCreateSpec request) {
            TaskShellSnapshot task = app.createTaskShell(request.shell());
            if (request.items() != null && !request.items().isEmpty()) {
                app.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                        .eventCode(TASK_EVENT_CODE)
                        .items(request.items())
                        .build());
            }
            if (!request.keepIntakeOpen()) {
                require(app.sealTask(task.getTaskId()), "task seal should succeed for " + task.getTaskId());
            }
            return task;
        }

        private TaskExecutionOptions taskExecutionSpec(String workloadClass,
                                                       int batchSize,
                                                       int maxRuntimeSeconds,
                                                       int defaultMaxRetryCount) {
            TaskExecutionOptions spec = new TaskExecutionOptions();
            spec.setWorkloadClass(workloadClass);
            spec.setBatchSize(batchSize);
            spec.setMaxRuntimeSeconds(maxRuntimeSeconds);
            spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
            return spec;
        }

        private record TaskCreateSpec(MassTaskShellCreateRequest shell,
                                      List<Object> items,
                                      boolean keepIntakeOpen) {
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
                    TaskStateSnapshot task = app.getTaskState(taskId);
                    return task != null && "TERMINAL".equals(task.getStatus());
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
                TaskStateSnapshot task = app.getTaskState(taskId);
                require(task != null, "task should exist: " + taskId);
                require("TERMINAL".equals(task.getStatus()), "task should be terminal: " + taskId);
                terminalTasks++;
                String terminalReasonName = task.getTerminalReason() != null ? task.getTerminalReason() : "<null>";
                terminalReasons.merge(terminalReasonName, 1L, Long::sum);
            }
            return new FinalTaskStats(terminalTasks, terminalReasons);
        }

        private FinalWorkStats collectFinalWorkStats(EmbeddedRuntime runtime, List<String> taskIds) {
            long total = 0;
            long success = 0;
            long failed = 0;
            long expired = 0;
            for (String taskId : taskIds) {
                TaskWorkStats stats = runtime.taskWorkRuntime().stats(taskId);
                require(stats.totalCount() == config.messagesPerTask(),
                        "unexpected runtime work count for task=" + taskId + " total=" + stats.totalCount());
                require(stats.finalCount() == stats.totalCount(),
                        "runtime work should be final after terminal convergence for task=" + taskId);
                total += stats.totalCount();
                success += stats.successCount();
                failed += stats.failedCount();
                expired += stats.expiredCount();
            }
            require(total == (long) config.taskCount() * config.messagesPerTask(),
                    "unexpected runtime work count");
            if (config.retryFailureEveryNth() > 0) {
                require(success == total,
                        "retry-enabled SDK load model should converge to success for all runtime work");
            }
            return new FinalWorkStats(total, success, failed, expired);
        }

        @SuppressWarnings("unchecked")
        private DeliveryQueueSnapshot collectDeliveryQueueSnapshot(MassSdkApplication app, long expectedWorkItems) {
            Map<String, Object> queueDetail = runtimeDiagnostics(app).getQueueDetail();
            Map<String, Object> deliveryQueue = (Map<String, Object>) queueDetail.get("deliveryDiagnostics");
            require(deliveryQueue != null, "deliveryDiagnostics should be available");
            DeliveryQueueSnapshot snapshot = DeliveryQueueSnapshot.from(deliveryQueue);
            require(snapshot.available(), "deliveryQueue should be available during SDK transport load run");
            require(snapshot.queuedItems() == 0,
                    "deliveryQueue should drain to zero after terminal convergence; queued=" + snapshot.queuedItems());
            require(snapshot.oldestQueuedAgeMillis() == 0,
                    "deliveryQueue oldest queued age should reset after drain; age=" + snapshot.oldestQueuedAgeMillis());
            require(snapshot.backpressureRejectedItems() == 0,
                    "SDK transport load should not hit delivery backpressure; rejected="
                            + snapshot.backpressureRejectedItems());
            require(snapshot.directFailedItems() == 0,
                    "SDK transport load should not hit direct-send failures; failed="
                            + snapshot.directFailedItems());
            if (config.transport() == WorkerTransportMode.POLLING) {
                require(snapshot.enqueuedItems() >= expectedWorkItems,
                        "polling delivery should enqueue at least every logical message; enqueued="
                                + snapshot.enqueuedItems() + " total=" + expectedWorkItems);
                require(snapshot.drainedItems() >= expectedWorkItems,
                        "polling delivery should drain at least every logical message; drained="
                                + snapshot.drainedItems() + " total=" + expectedWorkItems);
            } else {
                require(snapshot.directSentItems() >= expectedWorkItems,
                        "realtime delivery should direct-send at least every logical message; directSent="
                                + snapshot.directSentItems() + " total=" + expectedWorkItems);
                require(snapshot.directOfflineItems() == 0,
                        "realtime delivery should not observe offline endpoints during steady load; offline="
                                + snapshot.directOfflineItems());
                DirectAdapterSnapshot adapterSnapshot = snapshot.directByAdapter().get(config.transport().adapterId());
                require(adapterSnapshot != null,
                        "realtime delivery should expose adapter direct diagnostics for "
                                + config.transport().adapterId());
                require(adapterSnapshot.sentItems() >= expectedWorkItems,
                        "realtime adapter should direct-send at least every logical message; adapter="
                                + config.transport().adapterId() + " sent=" + adapterSnapshot.sentItems()
                                + " total=" + expectedWorkItems);
                require(adapterSnapshot.offlineItems() == 0,
                        "realtime adapter should not observe offline endpoints during steady load; adapter="
                                + config.transport().adapterId() + " offline=" + adapterSnapshot.offlineItems());
                require(adapterSnapshot.failedItems() == 0,
                        "realtime adapter should not observe direct-send failures during steady load; adapter="
                                + config.transport().adapterId() + " failed=" + adapterSnapshot.failedItems());
            }
            return snapshot;
        }

        private static Path writeReport(LoadConfig config,
                                        EmbeddedRuntime runtime,
                                        FinalTaskStats finalTaskStats,
                                        FinalWorkStats finalWorkStats,
                                        DeliveryQueueSnapshot deliveryQueue,
                                        long wallNanos,
                                        RuntimeMetricsSnapshot metrics) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("config", config.toMap());
            report.put("runtime", Map.of(
                    "transport", config.transport().label(),
                    "transportPort", runtime.transportPort(),
                    "boundTransportPort", runtime.boundTransportPort(config.transport()),
                    "endpointPath", runtime.endpointPath()
            ));
            report.put("wallClock", Map.of("totalMillis", nanosToMillis(wallNanos)));
            report.put("tasks", finalTaskStats.toMap());
            report.put("runtimeWork", finalWorkStats.toMap());
            report.put("deliveryQueue", deliveryQueue.toMap());
            report.put("workerMetrics", metrics.toMap());

            Path reportDir = TestingPaths.reportDir("concurrency-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("sdk-transport-load-" + config.transport().label() + "-" + timestamp + ".json");
            Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private interface WorkerDriver extends AutoCloseable {
        void start() throws Exception;

        default void refreshReadySignal() {
            // Most transports complete registration during start.
        }

        default void startReceiving() {
            // Some transport clients receive as soon as they connect.
        }
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

    private static final class SocketWorkerDriver implements WorkerDriver {
        private final String workerId;
        private final int port;
        private final LoadConfig config;
        private final RuntimeMetrics metrics;
        private final Map<String, AtomicInteger> deliveryAttempts;
        private final ExecutorService processingExecutor;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Socket socket;
        private OutputStream outputStream;
        private Thread readerThread;

        private SocketWorkerDriver(String workerId,
                                   int port,
                                   LoadConfig config,
                                   RuntimeMetrics metrics,
                                   Map<String, AtomicInteger> deliveryAttempts) {
            this.workerId = workerId;
            this.port = port;
            this.config = config;
            this.metrics = metrics;
            this.deliveryAttempts = deliveryAttempts;
            this.processingExecutor = newProcessingExecutor(workerId, config.workerProcessingThreads());
        }

        @Override
        public void start() throws Exception {
            require(port > 0, "socket transport port must be allocated");
            socket = new Socket("127.0.0.1", port);
            outputStream = socket.getOutputStream();
            running.set(true);
            require(sendLine(buildSocketHello(workerId)), "socket worker failed to send hello: " + workerId);
        }

        @Override
        public void startReceiving() {
            readerThread = new Thread(this::runReadLoop, "SdkSocketWorker-" + workerId + "-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void runReadLoop() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    JsonObject frame = parseFrame(line);
                    if (!isTaskDispatchFrame(frame)) {
                        continue;
                    }
                    metrics.recordReceiveBatch(1);
                    processingExecutor.submit(() -> processTaskDispatch(
                            frame,
                            workerId,
                            config,
                            metrics,
                            deliveryAttempts,
                            (success, detail, output) -> sendLine(buildTaskResult(frame, success, detail, output))
                    ));
                }
            } catch (IOException ex) {
                if (running.get()) {
                    throw new RuntimeException("Socket worker read loop failed for " + workerId, ex);
                }
            } finally {
                stopped.countDown();
            }
        }

        private boolean sendLine(String json) {
            try {
                synchronized (this) {
                    outputStream.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
                return true;
            } catch (IOException ex) {
                return false;
            }
        }

        @Override
        public void refreshReadySignal() {
            sendLine(buildSocketHello(workerId));
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            closeQuietly(socket);
            stopped.await(5, TimeUnit.SECONDS);
            processingExecutor.shutdown();
            if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
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
        frame.add("output", FRAME_GSON.toJsonTree(output != null ? output : Map.of()));
        return FRAME_GSON.toJson(frame);
    }

    private static String buildSocketHello(String workerId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty("workerId", workerId);
        return FRAME_GSON.toJson(frame);
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

    private static RuntimeDiagnosticsOperations runtimeDiagnostics(MassSdkApplication app) {
        return app.runtimeDiagnostics();
    }

    private record EmbeddedRuntime(MassSdkApplication app,
                                   TaskWorkRuntime taskWorkRuntime,
                                   int transportPort,
                                   String endpointPath) {
        private URI serverUri(String workerId) {
            require(transportPort > 0, "websocket server port must be allocated");
            return URI.create("ws://127.0.0.1:" + transportPort + endpointPath + "?workerId=" + workerId);
        }

        private int socketPort() {
            int boundPort = socketBoundPort();
            require(boundPort > 0, "socket server port must be allocated");
            return boundPort;
        }

        private int boundTransportPort(WorkerTransportMode transportMode) {
            return transportMode == WorkerTransportMode.SOCKET ? socketBoundPort() : transportPort;
        }

        private int socketBoundPort() {
            String rawPort = System.getProperty("mass.socket.bound-port");
            if (rawPort == null || rawPort.isBlank()) {
                return 0;
            }
            return Integer.parseInt(rawPort.trim());
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

    private record FinalWorkStats(long totalWorkItems,
                                  long successWorkItems,
                                  long failedWorkItems,
                                  long expiredWorkItems) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "totalWorkItems", totalWorkItems,
                    "successWorkItems", successWorkItems,
                    "failedWorkItems", failedWorkItems,
                    "expiredWorkItems", expiredWorkItems
            );
        }
    }

    private enum WorkerTransportMode {
        POLLING("polling", WorkerTransportHints.POLLING),
        WEBSOCKET("websocket", WorkerTransportHints.REALTIME),
        SOCKET("socket", WorkerTransportHints.REALTIME);

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

        private String adapterId() {
            return label;
        }

        private boolean usesServer() {
            return this != POLLING;
        }

        private static WorkerTransportMode fromProperty(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return POLLING;
            }
            String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "poll", "pull", "polling" -> POLLING;
                case "websocket", "ws", "realtime", "push" -> WEBSOCKET;
                case "socket", "tcp", "tcp-socket", "raw-socket" -> SOCKET;
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
                              String workloadClass,
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
                    workloadClassProperty("mass.sdk.load.workloadClass", "BULK"),
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
            values.put("workloadClass", workloadClass);
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
                              long totalWorkItems,
                              long successWorkItems,
                              long failedWorkItems,
                              long expiredWorkItems,
                              double wallClockMillis,
                              long receiveCycles,
                              long emptyReceiveCycles,
                              long receivedDispatchItems,
                              long resultSubmissions,
                              long syntheticRetryFailures,
                              long queuedDeliveryItems,
                              long enqueuedDeliveryItems,
                              long drainedDeliveryItems,
                              long backpressureRejectedDeliveryItems,
                              long oldestQueuedDeliveryAgeMillis,
                              long directSentDeliveryItems,
                              long directOfflineDeliveryItems,
                              long directFailedDeliveryItems,
                              long maxReceivedBatchSize,
                              int maxConcurrentProcessing,
                              double totalProcessingMillis,
                              Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkTransportLoad transport=%s port=%d tasks=%d terminal=%d reasons=%s workItems=%d success=%d "
                            + "failed=%d expired=%d wall=%.3fms receiveCycles=%d emptyReceiveCycles=%d dispatches=%d "
                            + "results=%d syntheticRetries=%d deliveryQueued=%d deliveryEnqueued=%d "
                            + "deliveryDrained=%d deliveryBackpressure=%d deliveryOldestAgeMs=%d "
                            + "deliveryDirectSent=%d deliveryDirectOffline=%d deliveryDirectFailed=%d "
                            + "maxReceiveBatch=%d maxConcurrentProcessing=%d report=%s",
                    config.transport().label(),
                    transportPort,
                    createdTasks,
                    terminalTasks,
                    terminalReasons,
                    totalWorkItems,
                    successWorkItems,
                    failedWorkItems,
                    expiredWorkItems,
                    wallClockMillis,
                    receiveCycles,
                    emptyReceiveCycles,
                    receivedDispatchItems,
                    resultSubmissions,
                    syntheticRetryFailures,
                    queuedDeliveryItems,
                    enqueuedDeliveryItems,
                    drainedDeliveryItems,
                    backpressureRejectedDeliveryItems,
                    oldestQueuedDeliveryAgeMillis,
                    directSentDeliveryItems,
                    directOfflineDeliveryItems,
                    directFailedDeliveryItems,
                    maxReceivedBatchSize,
                    maxConcurrentProcessing,
                    reportPath
            );
        }
    }

    private record DeliveryQueueSnapshot(boolean available,
                                         long queuedItems,
                                         long queueCount,
                                         long waitingPollers,
                                         long maxQueuedItems,
                                         long oldestQueuedAgeMillis,
                                         long enqueuedItems,
                                         long drainedItems,
                                         long backpressureRejectedItems,
                                         long invalidItems,
                                         long unavailableItems,
                                         long shutdownClearedItems,
                                         long directSentItems,
                                         long directOfflineItems,
                                         long directFailedItems,
                                         long directInvalidItems,
                                         long directUnavailableItems,
                                         Map<String, DirectAdapterSnapshot> directByAdapter) {
        private static DeliveryQueueSnapshot from(Map<String, Object> source) {
            return new DeliveryQueueSnapshot(
                    booleanValue(source.get("available")),
                    longValue(source.get("queuedItems")),
                    longValue(source.get("queueCount")),
                    longValue(source.get("waitingPollers")),
                    longValue(source.get("maxQueuedItems")),
                    longValue(source.get("oldestQueuedAgeMillis")),
                    longValue(source.get("enqueuedItems")),
                    longValue(source.get("drainedItems")),
                    longValue(source.get("backpressureRejectedItems")),
                    longValue(source.get("invalidItems")),
                    longValue(source.get("unavailableItems")),
                    longValue(source.get("shutdownClearedItems")),
                    longValue(source.get("directSentItems")),
                    longValue(source.get("directOfflineItems")),
                    longValue(source.get("directFailedItems")),
                    longValue(source.get("directInvalidItems")),
                    longValue(source.get("directUnavailableItems")),
                    parseDirectByAdapter(source.get("directByAdapter"))
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("available", available);
            values.put("queuedItems", queuedItems);
            values.put("queueCount", queueCount);
            values.put("waitingPollers", waitingPollers);
            values.put("maxQueuedItems", maxQueuedItems);
            values.put("oldestQueuedAgeMillis", oldestQueuedAgeMillis);
            values.put("enqueuedItems", enqueuedItems);
            values.put("drainedItems", drainedItems);
            values.put("backpressureRejectedItems", backpressureRejectedItems);
            values.put("invalidItems", invalidItems);
            values.put("unavailableItems", unavailableItems);
            values.put("shutdownClearedItems", shutdownClearedItems);
            values.put("directSentItems", directSentItems);
            values.put("directOfflineItems", directOfflineItems);
            values.put("directFailedItems", directFailedItems);
            values.put("directInvalidItems", directInvalidItems);
            values.put("directUnavailableItems", directUnavailableItems);
            values.put("directByAdapter", directByAdapter);
            return Map.copyOf(values);
        }
    }

    private record DirectAdapterSnapshot(long sentItems,
                                         long offlineItems,
                                         long failedItems,
                                         long invalidItems,
                                         long unavailableItems) {
        private static DirectAdapterSnapshot from(Map<?, ?> source) {
            return new DirectAdapterSnapshot(
                    longValue(source.get("sentItems")),
                    longValue(source.get("offlineItems")),
                    longValue(source.get("failedItems")),
                    longValue(source.get("invalidItems")),
                    longValue(source.get("unavailableItems"))
            );
        }
    }

    private static Map<String, DirectAdapterSnapshot> parseDirectByAdapter(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, DirectAdapterSnapshot> snapshots = new LinkedHashMap<>();
        source.forEach((adapterId, rawStats) -> {
            if (adapterId != null && rawStats instanceof Map<?, ?> stats) {
                snapshots.put(String.valueOf(adapterId), DirectAdapterSnapshot.from(stats));
            }
        });
        return Map.copyOf(snapshots);
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static String workloadClassProperty(String key, String defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return 0L;
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

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort test driver cleanup.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
