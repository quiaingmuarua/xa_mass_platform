package com.xa.mass.testing.concurrency;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.testing.support.WorkerRegistrationSpineSupport;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.sdk.worker.WorkerAction;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runnable SDK-driven transport load model using registered worker transports.
 *
 * <p>This harness starts an embedded {@link MassSdkApplication}, registers
 * SDK-native workers, and drives the runtime through either:
 *
 * <ul>
 *   <li>`polling`: real {@link EmbeddedPullWorkerSession} polling/result submission</li>
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
    private static final String WORKER_GROUP_ID = "sdk-load";

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
                }

                AtomicReference<Throwable> churnFailure = new AtomicReference<>();
                Thread churnThread = startTransportChurnThread(app, workers, churnFailure);
                for (String taskId : taskIds) {
                    require(app.approveTask(taskId), "task approval should succeed for " + taskId);
                }

                waitForTerminalTasks(app, taskIds);
                waitForTransportChurn(churnThread, churnFailure);

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

        private Thread startTransportChurnThread(MassSdkApplication app,
                                                 List<WorkerDriver> workers,
                                                 AtomicReference<Throwable> churnFailure) {
            if (!config.transportChurnEnabled()) {
                return null;
            }
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(config.transportChurnInitialDelayMillis());
                    int workerLimit = Math.min(config.transportChurnWorkerCount(), workers.size());
                    for (int cycle = 0; cycle < config.transportChurnCycles(); cycle++) {
                        for (int i = 0; i < workerLimit; i++) {
                            workers.get(i).injectTransportChurn();
                            if (config.transportChurnReconnectDelayMillis() > 0) {
                                Thread.sleep(config.transportChurnReconnectDelayMillis());
                            }
                        }
                    }
                    waitForRealtimeWorkersReady(app, workers);
                } catch (Throwable t) {
                    churnFailure.set(t);
                }
            }, "SdkTransportLoad-churn");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private void waitForTransportChurn(Thread churnThread, AtomicReference<Throwable> churnFailure)
                throws InterruptedException {
            if (churnThread == null) {
                return;
            }
            churnThread.join(TimeUnit.SECONDS.toMillis(config.timeoutSeconds()));
            require(!churnThread.isAlive(), "transport churn thread did not finish before timeout");
            Throwable failure = churnFailure.get();
            if (failure != null) {
                throw new IllegalStateException("transport churn failed", failure);
            }
            require(metrics.transportChurnDisconnects.sum() >= config.transportChurnCycles(),
                    "transport churn did not record a disconnect");
            require(metrics.transportChurnReconnects.sum() >= config.transportChurnCycles(),
                    "transport churn did not record a reconnect");
        }

        private EmbeddedRuntime buildRuntime(LoadConfig config) {
            int transportPort = config.transport() == WorkerTransportMode.WEBSOCKET ? findFreePort() : 0;
            InMemoryTaskShellStore taskStorage = new InMemoryTaskShellStore();
            MassSdkApplication app = MassSdk.builder()
                    .transport(transport -> transport
                            .webSocketAdapter(webSocket -> webSocket
                                    .server(transportPort, ENDPOINT_PATH)
                                    .enabled(config.transport() == WorkerTransportMode.WEBSOCKET)
                                    .serverEnabled(config.transport() == WorkerTransportMode.WEBSOCKET))
                            .socketAdapter(socket -> socket
                                    .server(transportPort)
                                    .enabled(config.transport() == WorkerTransportMode.SOCKET)
                                    .serverEnabled(config.transport() == WorkerTransportMode.SOCKET)))
                    .engine(engine -> engine.enabled(true)
                            .taskShellStore(taskStorage)
                            .memoryTaskRuntime())
                    .build();
            return new EmbeddedRuntime(app, transportPort, ENDPOINT_PATH);
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
                    .groupId(WORKER_GROUP_ID)
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
            WorkerRegistrationSpineSupport.registerAdapterNode(app, adapterNodeId, transportHint);
            WorkerRegistrationSpineSupport.bindNodeGroup(app, adapterNodeId, WORKER_GROUP_ID);
            for (int i = 0; i < workerCount; i++) {
                String workerId = "sdk-load-worker-" + i;
                app.registerWorker(WorkerRegistration.builder()
                        .workerId(workerId)
                        .workerGroupId(WORKER_GROUP_ID)
                        .transportHint(transportHint)
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
                if (reachableWorkerCount(app, workers) >= config.workerCount()) {
                    return;
                }
                for (WorkerDriver worker : workers) {
                    worker.refreshReadySignal();
                }
                Thread.sleep(25L);
            }
            require(reachableWorkerCount(app, workers) >= config.workerCount(),
                    "realtime workers did not become ready for adapter=" + config.transport().adapterId()
                            + " unreachableWorkers=" + unreachableWorkers(app, workers));
        }

        private long reachableWorkerCount(MassSdkApplication app, List<WorkerDriver> workers) {
            return workers.stream()
                    .filter(worker -> app.isWorkerReachable(worker.workerId()))
                    .count();
        }

        private List<String> unreachableWorkers(MassSdkApplication app, List<WorkerDriver> workers) {
            return workers.stream()
                    .map(WorkerDriver::workerId)
                    .filter(workerId -> !app.isWorkerReachable(workerId))
                    .toList();
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
                TaskWorkStatsSnapshot stats = runtime.app().taskDiagnostics().getTaskWorkStats(taskId);
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
            require(snapshot.queuedItems() == snapshot.queuedItemsByAdapter(),
                    "deliveryQueue global queued count should match per-adapter breakdown; queued="
                            + snapshot.queuedItems() + " adapterQueued=" + snapshot.queuedItemsByAdapter());
            require(snapshot.queuedItems() == 0,
                    "deliveryQueue should drain to zero after terminal convergence; queued=" + snapshot.queuedItems());
            require(snapshot.oldestQueuedAgeMillis() == 0,
                    "deliveryQueue oldest queued age should reset after drain; age=" + snapshot.oldestQueuedAgeMillis());
            require(snapshot.backpressureRejectedItems() == 0,
                    "SDK transport load should not hit delivery backpressure; rejected="
                            + snapshot.backpressureRejectedItems());
            if (config.transport() == WorkerTransportMode.POLLING) {
                require(snapshot.enqueuedItems() >= expectedWorkItems,
                        "polling delivery should enqueue at least every logical message; enqueued="
                                + snapshot.enqueuedItems() + " total=" + expectedWorkItems);
                require(snapshot.drainedItems() >= expectedWorkItems,
                        "polling delivery should drain at least every logical message; drained="
                                + snapshot.drainedItems() + " total=" + expectedWorkItems);
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
            Map<String, Object> report = new LinkedHashMap<>(WorkerFaultReportMetadata.topLevel(
                    config.scenario()));
            report.put("actualTransport", config.transport().label());
            report.put("config", config.toMap());
            report.put("runtime", Map.of(
                    "actualTransport", config.transport().label(),
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
        String workerId();

        void start() throws Exception;

        default void injectTransportChurn() throws Exception {
            throw new UnsupportedOperationException("transport churn is not supported by this worker driver");
        }

        default void refreshReadySignal() {
            // Most transports complete registration during start.
        }

        default void startReceiving() {
            // Some transport clients receive as soon as they connect.
        }
    }

    private static final class PollingWorkerDriver implements WorkerDriver {
        private final String workerId;
        private final EmbeddedPullWorkerSession session;
        private final LoadConfig config;
        private final RuntimeMetrics metrics;
        private final AtomicBoolean stopRequested;
        private final Map<String, AtomicInteger> deliveryAttempts;
        private final ExecutorService processingExecutor;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Thread pollThread;

        private PollingWorkerDriver(String workerId,
                                    EmbeddedPullWorkerSession session,
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
        public String workerId() {
            return workerId;
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
                    List<WorkerAction> items = session.poll(config.pollBatchSize());
                    metrics.recordReceiveBatch(items == null ? 0 : items.size());
                    if (items == null || items.isEmpty()) {
                        if (stopRequested.get()) {
                            break;
                        }
                        Thread.sleep(20L);
                        continue;
                    }
                    for (WorkerAction item : items) {
                        processingExecutor.submit(() -> processTaskDispatch(item, workerId, config, metrics, deliveryAttempts,
                                (success, detail, output) -> submitPollingResult(session, item, success, detail, output)));
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
        public String workerId() {
            return workerId;
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

        @Override
        public synchronized void injectTransportChurn() throws Exception {
            if (client != null) {
                client.closeBlocking();
                metrics.transportChurnDisconnects.increment();
            }
            WorkerSocketClient nextClient = new WorkerSocketClient(serverUri);
            require(nextClient.connectBlocking(5, TimeUnit.SECONDS),
                    "websocket worker failed to reconnect during transport churn: "
                            + workerId + " uri=" + serverUri);
            client = nextClient;
            metrics.transportChurnReconnects.increment();
        }

        private final class WorkerSocketClient extends WebSocketClient {
            private WorkerSocketClient(URI serverUri) {
                super(serverUri);
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
        public String workerId() {
            return workerId;
        }

        @Override
        public void start() throws Exception {
            require(port > 0, "socket transport port must be allocated");
            socket = new Socket("127.0.0.1", port);
            outputStream = socket.getOutputStream();
            running.set(true);
            require(sendLine(buildSocketHello(workerId, canonicalRouteKey(workerId))),
                    "socket worker failed to send hello: " + workerId);
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
            sendLine(buildSocketHello(workerId, canonicalRouteKey(workerId)));
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

    private static boolean submitPollingResult(EmbeddedPullWorkerSession session,
                                               WorkerAction item,
                                               boolean success,
                                               String detail,
                                               Map<String, Object> output) {
        String resultCode = success ? null : normalizeResultCode(detail);
        String result = FRAME_GSON.toJson(output != null ? output : Map.of());
        return session.submitActionReply(item, success, resultCode, result);
    }

    private static String normalizeResultCode(String detail) {
        if (detail == null || detail.isBlank()) {
            return "SYNTHETIC_FAILURE";
        }
        return detail.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static void processTaskDispatch(WorkerAction item,
                                            String workerId,
                                            LoadConfig config,
                                            RuntimeMetrics metrics,
                                            Map<String, AtomicInteger> deliveryAttempts,
                                            ResultSubmitter submitter) {
        int concurrent = metrics.onProcessingStart();
        try {
            maybeSleep(config.processingDelayMillis());
            String resultCorrelationRef = item.getReplyRef();
            int attemptNo = deliveryAttempts
                    .computeIfAbsent(resultCorrelationRef, ignored -> new AtomicInteger())
                    .incrementAndGet();
            int seq = readSeq(actionBody(item));
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
            require(submitted, "result submission should succeed for " + resultCorrelationRef);
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
            String resultCorrelationRef = readString(frame, "resultCorrelationRef");
            int attemptNo = deliveryAttempts
                    .computeIfAbsent(resultCorrelationRef, ignored -> new AtomicInteger())
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
            require(submitted, "websocket result submission should succeed for " + resultCorrelationRef);
        } finally {
            metrics.onProcessingFinish();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> actionBody(WorkerAction item) {
        String body = item.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        Object decoded = FRAME_GSON.fromJson(body, Object.class);
        if (decoded instanceof Map<?, ?> values) {
            return (Map<String, Object>) values;
        }
        return Map.of("rawBody", body);
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
                && readString(frame, "resultCorrelationRef") != null
                && readString(frame, "eventCode") != null
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
        frame.addProperty("resultCorrelationRef", readString(taskFrame, "resultCorrelationRef"));
        frame.addProperty("success", success);
        frame.addProperty("detail", detail);
        frame.add("output", FRAME_GSON.toJsonTree(output != null ? output : Map.of()));
        return FRAME_GSON.toJson(frame);
    }

    private static String buildSocketHello(String workerId, String routeKey) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty("workerId", workerId);
        frame.addProperty("routeKey", routeKey);
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

    private static URI appendWorkerIdentity(URI serverUri, String workerId, String workerGroupId, String routeKey) {
        String existingQuery = serverUri.getRawQuery();
        String workerQuery = "workerId=" + workerId.trim()
                + "&workerGroupId=" + workerGroupId.trim();
        if (routeKey != null && !routeKey.isBlank()) {
            workerQuery += "&routeKey=" + routeKey.trim();
        }
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
            throw new IllegalArgumentException("Failed to append worker identity to serverUri", ex);
        }
    }

    private static String canonicalRouteKey(String workerId) {
        return CanonicalWorkerGroupRouteKeyCodec.encode(WORKER_GROUP_ID);
    }

    private static RuntimeDiagnosticsOperations runtimeDiagnostics(MassSdkApplication app) {
        return app.runtimeDiagnostics();
    }

    private record EmbeddedRuntime(MassSdkApplication app,
                                   int transportPort,
                                   String endpointPath) {
        private URI serverUri(String workerId) {
            require(transportPort > 0, "websocket server port must be allocated");
            return appendWorkerIdentity(
                    URI.create("ws://127.0.0.1:" + transportPort + endpointPath),
                    workerId,
                    WORKER_GROUP_ID,
                    canonicalRouteKey(workerId)
            );
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
        private final LongAdder transportChurnDisconnects = new LongAdder();
        private final LongAdder transportChurnReconnects = new LongAdder();
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
                    transportChurnDisconnects.sum(),
                    transportChurnReconnects.sum(),
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
                                          long transportChurnDisconnects,
                                          long transportChurnReconnects,
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
                    "transportChurnDisconnects", transportChurnDisconnects,
                    "transportChurnReconnects", transportChurnReconnects,
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

    private record LoadConfig(WorkerFaultScenarioIndex.Scenario scenario,
                              WorkerTransportMode transport,
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
                              int timeoutSeconds,
                              boolean transportChurnEnabled,
                              int transportChurnCycles,
                              int transportChurnWorkerCount,
                              int transportChurnInitialDelayMillis,
                              int transportChurnReconnectDelayMillis) {
        private static LoadConfig fromSystemProperties() {
            WorkerFaultScenarioIndex.Scenario scenario = scenarioProperty();
            boolean scenarioChurnEnabled = isTransportChurnScenario(scenario);
            LoadConfig config = new LoadConfig(
                    scenario,
                    transportForScenario(scenario, System.getProperty("mass.sdk.load.transport")),
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
                    intProperty("mass.sdk.load.timeoutSeconds", 60),
                    booleanProperty("mass.sdk.load.transportChurn.enabled", scenarioChurnEnabled),
                    intProperty("mass.sdk.load.transportChurn.cycles", scenarioChurnEnabled ? 1 : 0),
                    intProperty("mass.sdk.load.transportChurn.workerCount", scenarioChurnEnabled ? 1 : 0),
                    intProperty("mass.sdk.load.transportChurn.initialDelayMillis", scenarioChurnEnabled ? 10 : 0),
                    intProperty("mass.sdk.load.transportChurn.reconnectDelayMillis", 0)
            );
            require(config.taskCount > 0, "taskCount must be positive");
            require(config.messagesPerTask > 0, "messagesPerTask must be positive");
            require(config.workerCount > 0, "workerCount must be positive");
            require(config.batchSize > 0, "batchSize must be positive");
            require(config.pollBatchSize > 0, "pollBatchSize must be positive");
            require(config.workerProcessingThreads > 0, "workerProcessingThreads must be positive");
            require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            require(config.transportChurnCycles >= 0, "transportChurnCycles must not be negative");
            require(config.transportChurnWorkerCount >= 0, "transportChurnWorkerCount must not be negative");
            require(config.transportChurnInitialDelayMillis >= 0,
                    "transportChurnInitialDelayMillis must not be negative");
            require(config.transportChurnReconnectDelayMillis >= 0,
                    "transportChurnReconnectDelayMillis must not be negative");
            if (config.retryFailureEveryNth > 0) {
                require(config.maxRetryCount > 0,
                        "maxRetryCount must be positive when retryFailureEveryNth is enabled");
            }
            if (config.transportChurnEnabled) {
                require(config.transport == WorkerTransportMode.WEBSOCKET,
                        "current SDK transport churn row supports websocket only");
                require(config.transportChurnCycles > 0,
                        "transportChurnCycles must be positive when transport churn is enabled");
                require(config.transportChurnWorkerCount > 0,
                        "transportChurnWorkerCount must be positive when transport churn is enabled");
                require(config.transportChurnWorkerCount <= config.workerCount,
                        "transportChurnWorkerCount must not exceed workerCount");
            }
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("scenarioId", scenario.scenarioId());
            values.put("actualTransport", transport.label());
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
            values.put("transportChurnEnabled", transportChurnEnabled);
            values.put("transportChurnCycles", transportChurnCycles);
            values.put("transportChurnWorkerCount", transportChurnWorkerCount);
            values.put("transportChurnInitialDelayMillis", transportChurnInitialDelayMillis);
            values.put("transportChurnReconnectDelayMillis", transportChurnReconnectDelayMillis);
            return Map.copyOf(values);
        }

        private static WorkerFaultScenarioIndex.Scenario scenarioProperty() {
            String scenarioId = System.getProperty("mass.sdk.load.scenarioId");
            WorkerFaultScenarioIndex.Scenario scenario;
            if (scenarioId == null || scenarioId.isBlank()) {
                scenario = WorkerFaultScenarioIndex.Scenario.SDK_TRANSPORT_LOAD;
            } else {
                scenario = WorkerFaultScenarioIndex.scenarioForId(scenarioId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "unknown mass.sdk.load.scenarioId: " + scenarioId.trim()));
            }
            if (scenario.runnerFamily() != WorkerFaultScenarioIndex.RunnerFamily.SDK_TRANSPORT_LOAD) {
                throw new IllegalArgumentException(
                        "mass.sdk.load.scenarioId must reference an SDK transport load scenario: "
                                + scenario.scenarioId());
            }
            return scenario;
        }

        private static boolean isTransportChurnScenario(WorkerFaultScenarioIndex.Scenario scenario) {
            return "transport-connection-churn".equals(scenario.faultShape());
        }

        private static WorkerTransportMode transportForScenario(WorkerFaultScenarioIndex.Scenario scenario,
                                                                String explicitTransport) {
            WorkerTransportMode scenarioTransport = switch (scenario.transport()) {
                case "polling" -> WorkerTransportMode.POLLING;
                case "websocket" -> WorkerTransportMode.WEBSOCKET;
                case "socket" -> WorkerTransportMode.SOCKET;
                case "multi" -> null;
                default -> throw new IllegalArgumentException("unsupported SDK transport load scenario transport: "
                        + scenario.transport());
            };
            WorkerTransportMode configuredTransport = WorkerTransportMode.fromProperty(explicitTransport);
            if (scenarioTransport == null) {
                return configuredTransport;
            }
            if (explicitTransport != null && !explicitTransport.isBlank()
                    && configuredTransport != scenarioTransport) {
                throw new IllegalArgumentException("mass.sdk.load.transport=" + configuredTransport.label()
                        + " conflicts with mass.sdk.load.scenarioId=" + scenario.scenarioId());
            }
            return scenarioTransport;
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
                                         Map<String, QueueAdapterSnapshot> queueByAdapter) {
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
                    parseQueueByAdapter(source.get("queueByAdapter"))
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
            values.put("queueByAdapter", queueByAdapter);
            return Map.copyOf(values);
        }

        private long queuedItemsByAdapter() {
            return queueByAdapter.values().stream()
                    .mapToLong(QueueAdapterSnapshot::queuedItems)
                    .sum();
        }
    }

    private record QueueAdapterSnapshot(long queuedItems,
                                        long queueCount,
                                        long waitingPollers,
                                        long oldestQueuedAgeMillis,
                                        long backpressureRejectedItems) {
        private static QueueAdapterSnapshot from(Map<?, ?> source) {
            return new QueueAdapterSnapshot(
                    longValue(source.get("queuedItems")),
                    longValue(source.get("queueCount")),
                    longValue(source.get("waitingPollers")),
                    longValue(source.get("oldestQueuedAgeMillis")),
                    longValue(source.get("backpressureRejectedItems"))
            );
        }
    }

    private static Map<String, QueueAdapterSnapshot> parseQueueByAdapter(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, QueueAdapterSnapshot> snapshots = new LinkedHashMap<>();
        source.forEach((adapterId, rawStats) -> {
            if (adapterId != null && rawStats instanceof Map<?, ?> stats) {
                snapshots.put(String.valueOf(adapterId), QueueAdapterSnapshot.from(stats));
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
