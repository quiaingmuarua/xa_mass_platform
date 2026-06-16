package com.xa.mass.testing.chaos.support;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
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
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.runtime.redis.RedisTaskResultRuntime;
import com.xa.mass.runtime.redis.RedisTaskWorkRuntime;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.testing.support.WorkerRegistrationSpineSupport;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.TransportOutboundMessage;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ChaosRuntimeHarness implements AutoCloseable {
    private static final List<PayloadType> DEFAULT_PAYLOAD_TYPES = List.of(PayloadType.JSON);
    private static final List<TaskMode> DEFAULT_TASK_MODES = List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING);

    private final MassSdkApplication app;
    private final TaskWorkRuntime taskWorkRuntime;
    private final InMemoryTaskShellStore taskStorage;
    private final int transportPort;
    private final String endpointPath;
    private final ExecutionEventSink traceSink;
    private final PollingRedisRuntimeConfig pollingRedisConfig;
    private final Map<String, String> workerGroupIdByWorkerId = new LinkedHashMap<>();

    private ChaosRuntimeHarness(MassSdkApplication app,
                                TaskWorkRuntime taskWorkRuntime,
                                InMemoryTaskShellStore taskStorage,
                                int transportPort,
                                String endpointPath,
                                ExecutionEventSink traceSink,
                                PollingRedisRuntimeConfig pollingRedisConfig) {
        this.app = app;
        this.taskWorkRuntime = taskWorkRuntime;
        this.taskStorage = taskStorage;
        this.transportPort = transportPort;
        this.endpointPath = endpointPath;
        this.traceSink = traceSink;
        this.pollingRedisConfig = pollingRedisConfig;
    }

    public static ChaosRuntimeHarness createWebSocket(WebSocketRuntimeConfig config) {
        return createWebSocket(config, null);
    }

    public static ChaosRuntimeHarness createWebSocket(WebSocketRuntimeConfig config,
                                                      ExecutionEventSink traceSink) {
        int transportPort = ChaosSupport.findFreePort();
        InMemoryTaskShellStore taskStorage = new InMemoryTaskShellStore();
        TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
        MassSdk.Builder builder = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(transportPort, config.endpointPath())
                                .enabled(true)
                                .serverEnabled(true))
                        .inputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> {
                    engine.enabled(true)
                            .workerThreads(config.workerThreads())
                            .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                            .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                            .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds())
                            .taskShellStore(taskStorage)
                            .taskWorkRuntime(taskWorkRuntime);
                    if (traceSink != null) {
                        engine.executionEventSink(traceSink);
                    }
                });
        return createBootstrappedHarness(builder.build(), taskWorkRuntime, taskStorage, transportPort, config.endpointPath(), traceSink, null);
    }

    public static ChaosRuntimeHarness createPolling(PollingRuntimeConfig config) {
        return createPolling(config, null);
    }

    public static ChaosRuntimeHarness createPolling(PollingRuntimeConfig config,
                                                    ExecutionEventSink traceSink) {
        InMemoryTaskShellStore taskStorage = new InMemoryTaskShellStore();
        TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
        MassSdk.Builder builder = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .enabled(false)
                                .serverEnabled(false))
                        .inputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> {
                    engine.enabled(true)
                            .workerThreads(config.workerThreads())
                            .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                            .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                            .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds())
                            .taskShellStore(taskStorage)
                            .taskWorkRuntime(taskWorkRuntime);
                    if (traceSink != null) {
                        engine.executionEventSink(traceSink);
                    }
                });
        return createBootstrappedHarness(builder.build(), taskWorkRuntime, taskStorage, 0, "", traceSink, null);
    }

    public static ChaosRuntimeHarness createPollingRedis(PollingRedisRuntimeConfig config,
                                                         ExecutionEventSink traceSink) {
        return createPollingRedis(config, new InMemoryTaskShellStore(), traceSink);
    }

    private static ChaosRuntimeHarness createPollingRedis(PollingRedisRuntimeConfig config,
                                                          InMemoryTaskShellStore taskStorage,
                                                          ExecutionEventSink traceSink) {
        TaskWorkRuntime taskWorkRuntime = new RedisTaskWorkRuntime(
                config.redisUri(),
                config.redisNamespace(),
                config.maxQueuedItems()
        );
        RedisTaskResultRuntime taskResultRuntime = new RedisTaskResultRuntime(
                config.redisUri(),
                config.redisNamespace() + ":result"
        );
        MassSdk.Builder builder = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .enabled(false)
                                .serverEnabled(false))
                        .inputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> {
                    engine.enabled(true)
                            .workerThreads(config.workerThreads())
                            .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                            .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                            .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds())
                            .taskShellStore(taskStorage)
                            .taskWorkRuntime(taskWorkRuntime)
                            .taskResultRuntime(taskResultRuntime);
                    if (traceSink != null) {
                        engine.executionEventSink(traceSink);
                    }
                });
        return createBootstrappedHarness(builder.build(), taskWorkRuntime, taskStorage, 0, "", traceSink, config);
    }

    private static ChaosRuntimeHarness createBootstrappedHarness(MassSdkApplication app,
                                                                 TaskWorkRuntime taskWorkRuntime,
                                                                 InMemoryTaskShellStore taskStorage,
                                                                 int transportPort,
                                                                 String endpointPath,
                                                                 ExecutionEventSink traceSink,
                                                                 PollingRedisRuntimeConfig pollingRedisConfig) {
        bootstrapCatalog(app);
        return new ChaosRuntimeHarness(app, taskWorkRuntime, taskStorage, transportPort, endpointPath, traceSink, pollingRedisConfig);
    }

    private static void bootstrapCatalog(MassSdkApplication app) {
        Objects.requireNonNull(app, "app");
        registerEventIfMissing(app, EventDefinition.builder()
                .code("demo.dispatch")
                .name("Demo Dispatch")
                .description("Dispatch a generic demo work item to an online demo worker.")
                .payloadTypes(DEFAULT_PAYLOAD_TYPES)
                .taskModes(DEFAULT_TASK_MODES)
                .projectCodes(List.of("demoApp", "testApp", "otherApp"))
                .build());
        registerEventIfMissing(app, EventDefinition.builder()
                .code("demo.dispatch.gb")
                .name("Demo Dispatch (GB)")
                .description("Dispatch a generic demo work item to the GB demo lane.")
                .payloadTypes(DEFAULT_PAYLOAD_TYPES)
                .taskModes(DEFAULT_TASK_MODES)
                .projectCodes(List.of("demoApp", "otherApp"))
                .build());
        registerEventIfMissing(app, EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Dispatch a crawler fetch request to an SDK-created pull worker.")
                .payloadTypes(DEFAULT_PAYLOAD_TYPES)
                .taskModes(DEFAULT_TASK_MODES)
                .projectCodes(List.of("crawlerApp"))
                .build());
        registerProjectIfMissing(app, ProjectDefinition.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Chaos test demo project.")
                .eventCodes(List.of("demo.dispatch", "demo.dispatch.gb"))
                .build());
        registerProjectIfMissing(app, ProjectDefinition.builder()
                .code("testApp")
                .name("Test App")
                .description("Chaos test regression project.")
                .eventCodes(List.of("demo.dispatch"))
                .build());
        registerProjectIfMissing(app, ProjectDefinition.builder()
                .code("otherApp")
                .name("Other App")
                .description("Chaos test secondary demo project.")
                .eventCodes(List.of("demo.dispatch", "demo.dispatch.gb"))
                .build());
        registerProjectIfMissing(app, ProjectDefinition.builder()
                .code("crawlerApp")
                .name("Crawler")
                .description("Chaos test crawler project.")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
    }

    private static void registerEventIfMissing(MassSdkApplication app, EventDefinition definition) {
        if (app.getEvent(definition.getCode()) == null) {
            app.registerEventDefinition(definition);
        }
    }

    private static void registerProjectIfMissing(MassSdkApplication app, ProjectDefinition definition) {
        if (app.getProject(definition.getCode()) == null) {
            app.registerProject(definition);
        }
    }

    public MassSdkApplication app() {
        return app;
    }

    public TaskWorkStats runtimeStats(String taskId) {
        return taskWorkRuntime.stats(taskId);
    }

    public List<ActiveLeaseRecord> activeLeases(String taskId) {
        return taskWorkRuntime.activeLeases(taskId);
    }

    public Optional<RecentFinalWorkReceipt> recentFinalReceipt(String taskId, String messageId) {
        return taskWorkRuntime.getRecentFinalReceipt(taskId, messageId);
    }

    public TaskWorkStats waitForRuntimeStats(String taskId,
                                             long totalCount,
                                             long successCount,
                                             long failedCount,
                                             long expiredCount,
                                             int timeoutSeconds,
                                             String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> {
            TaskWorkStats stats = taskWorkRuntime.stats(taskId);
            return stats.totalCount() == totalCount
                    && stats.successCount() == successCount
                    && stats.failedCount() == failedCount
                    && stats.expiredCount() == expiredCount
                    && stats.finalCount() == totalCount;
        }, timeoutSeconds, failureMessage);
        return taskWorkRuntime.stats(taskId);
    }

    public void start() {
        app.start();
    }

    public ChaosRuntimeHarness restartPollingRedisRuntime() {
        ChaosSupport.require(pollingRedisConfig != null, "harness is not backed by Redis polling runtime");
        close();
        return createPollingRedis(pollingRedisConfig, taskStorage, traceSink);
    }

    public void registerRealtimeWorker(String workerId,
                                       String workerGroupId,
                                       String projectCode,
                                       String routingCode) {
        ensureWorkerGroupBinding(workerGroupId, projectCode, "chaos-websocket-node", WorkerTransportHints.REALTIME);
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .transportHint(WorkerTransportHints.REALTIME)
                .attributes(Map.of("routingTags", routingCode, "country", routingCode))
                .build());
        workerGroupIdByWorkerId.put(workerId, workerGroupId);
    }

    public void registerPollingWorker(String workerId,
                                      String workerGroupId,
                                      String projectCode,
                                      String routingCode) {
        ensureWorkerGroupBinding(workerGroupId, projectCode, "chaos-polling-node", WorkerTransportHints.POLLING);
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .transportHint(WorkerTransportHints.POLLING)
                .attributes(Map.of("routingTags", routingCode, "country", routingCode))
                .build());
        workerGroupIdByWorkerId.put(workerId, workerGroupId);
    }

    private void ensureWorkerGroupBinding(String workerGroupId,
                                          String projectCode,
                                          String adapterNodeId,
                                          String adapterType) {
        app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId(workerGroupId)
                .eventBindings(List.of(WorkerEventBinding.builder()
                        .eventCode(defaultEventCode(projectCode))
                        .projectCodes(List.of(projectCode))
                        .build()))
                .build());
        WorkerRegistrationSpineSupport.registerAdapterNode(app, adapterNodeId, adapterType);
        WorkerRegistrationSpineSupport.bindNodeGroup(app, adapterNodeId, workerGroupId);
    }

    public PullWorkerSession pullWorker(String workerId) {
        return app.pullWorker(workerId);
    }

    public boolean pauseTask(String taskId) {
        return app.pauseTask(taskId);
    }

    public boolean resumeTask(String taskId) {
        return app.resumeTask(taskId);
    }

    public boolean cancelTask(String taskId) {
        return app.cancelTask(taskId);
    }

    public void waitForTaskStatus(String taskId,
                                  String expectedStatus,
                                  int timeoutSeconds,
                                  String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(
                () -> {
                    TaskStateSnapshot current = app.getTaskState(taskId);
                    return current != null && expectedStatus.equals(current.getStatus());
                },
                timeoutSeconds,
                failureMessage
        );
    }

    public TaskShellSnapshot createApprovedTask(TaskCreateSpec spec) {
        TaskExecutionOptions executionSpec = new TaskExecutionOptions();
        executionSpec.setBatchSize(spec.batchSize());
        executionSpec.setMaxRuntimeSeconds(spec.maxRuntimeSeconds());
        executionSpec.setDefaultMaxRetryCount(spec.defaultMaxRetryCount());
        TaskShellSnapshot task = createTask(
                MassTaskShellCreateRequest.builder()
                        .userId(spec.userId())
                        .project(spec.projectCode())
                        .sourceRef(spec.taskName())
                        .sharedConfig(spec.sharedConfig())
                        .executionSpec(executionSpec)
                        .build(),
                spec.eventCode(),
                new ArrayList<>(spec.inputs()),
                false
        );
        ChaosSupport.require(app.approveTask(task.getTaskId()), "task approval should succeed for " + task.getTaskId());
        return task;
    }

    private TaskShellSnapshot createTask(MassTaskShellCreateRequest request,
                                         String eventCode,
                                         List<Object> items,
                                         boolean keepIntakeOpen) {
        TaskShellSnapshot task = app.createTaskShell(request);
        if (items != null && !items.isEmpty()) {
            app.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                    .eventCode(eventCode)
                    .items(items)
                    .build());
        }
        if (!keepIntakeOpen) {
            ChaosSupport.require(app.sealTask(task.getTaskId()), "task seal should succeed for " + task.getTaskId());
        }
        return task;
    }

    public void waitForWorkerOnline(String workerId, int timeoutSeconds, String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> app.isWorkerReachable(workerId), timeoutSeconds, failureMessage);
    }

    public void waitForWorkerOffline(String workerId, int timeoutSeconds, String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> !app.isWorkerReachable(workerId), timeoutSeconds, failureMessage);
    }

    public void waitForActiveAttemptOnWorker(String taskId,
                                             String messageId,
                                             String workerId,
                                             int timeoutSeconds,
                                             String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> {
            return taskWorkRuntime.getActiveLease(taskId, messageId)
                    .map(lease -> workerId.equals(lease.workerId()))
                    .orElse(false);
        }, timeoutSeconds, failureMessage);
    }

    public void waitForAttemptCount(String taskId,
                                    String messageId,
                                    int minimumAttempts,
                                    int timeoutSeconds,
                                    String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(
                () -> {
                    boolean activeRetryReached = taskWorkRuntime.getActiveLease(taskId, messageId)
                            .map(lease -> lease.retryCount() + 1 >= minimumAttempts)
                            .orElse(false);
                    boolean finalRetryReached = taskWorkRuntime.getRecentFinalReceipt(taskId, messageId)
                            .map(receipt -> receipt.retryCount() + 1 >= minimumAttempts)
                            .orElse(false);
                    return activeRetryReached || finalRetryReached;
                },
                timeoutSeconds,
                failureMessage
        );
    }

    public TaskOutcomeSnapshot waitForTerminalTask(String taskId,
                                                   int messageLimit,
                                                   int timeoutSeconds,
                                                   String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(
                () -> {
                    TaskStateSnapshot current = app.getTaskState(taskId);
                    return current != null && "TERMINAL".equals(current.getStatus());
                },
                timeoutSeconds,
                failureMessage
        );
        return snapshotTaskOutcome(taskId, messageLimit);
    }

    public TaskOutcomeSnapshot snapshotTaskOutcome(String taskId, int messageLimit) {
        TaskStateSnapshot task = app.getTaskState(taskId);
        ChaosSupport.require(task != null, "task should exist: " + taskId);
        return new TaskOutcomeSnapshot(
                task.getTaskId(),
                task.getStatus(),
                task.getTerminalReason(),
                TaskOutcomeSnapshot.RuntimeWorkOutcomeSnapshot.from(
                        taskWorkRuntime.stats(taskId),
                        taskWorkRuntime.activeLeases(taskId)
                ),
                List.of()
        );
    }

    public URI serverUri(String workerId) {
        ChaosSupport.require(transportPort > 0, "websocket server port must be allocated");
        String workerGroupId = workerGroupIdByWorkerId.get(workerId);
        ChaosSupport.require(workerGroupId != null && !workerGroupId.isBlank(),
                "workerGroupId must be registered before creating websocket URI for " + workerId);
        String routeKey = CanonicalWorkerGroupRouteKeyCodec.encode(workerGroupId);
        return ChaosSupport.appendWorkerIdentity(
                URI.create("ws://127.0.0.1:" + transportPort + endpointPath),
                workerId,
                workerGroupId,
                routeKey
        );
    }

    @Override
    public void close() {
        app.stop();
    }

    public record WebSocketRuntimeConfig(String endpointPath,
                                         String queuePrefix,
                                         int workerThreads,
                                         long assignmentRetryDelayMillis,
                                         long leaseWatchdogIntervalSeconds,
                                         long taskMessageLeaseSeconds) {
    }

    public record PollingRuntimeConfig(String queuePrefix,
                                       int workerThreads,
                                       long assignmentRetryDelayMillis,
                                       long leaseWatchdogIntervalSeconds,
                                       long taskMessageLeaseSeconds) {
    }

    public record PollingRedisRuntimeConfig(String queuePrefix,
                                            int workerThreads,
                                            long assignmentRetryDelayMillis,
                                            long leaseWatchdogIntervalSeconds,
                                            long taskMessageLeaseSeconds,
                                            String redisUri,
                                            String redisNamespace,
                                            int maxQueuedItems) {
    }

    public record TaskCreateSpec(String userId,
                                 String projectCode,
                                 String taskName,
                                 String eventCode,
                                 Map<String, Object> sharedConfig,
                                 List<Map<String, Object>> inputs,
                                 int batchSize,
                                 int defaultMaxRetryCount,
                                 int maxRuntimeSeconds) {
        public static TaskCreateSpec multiMessage(String userId,
                                                  String projectCode,
                                                  String taskName,
                                                  String routingCode,
                                                  int messageCount,
                                                  int batchSize,
                                                  int defaultMaxRetryCount,
                                                  int maxRuntimeSeconds) {
            Map<String, Object> sharedConfig = new LinkedHashMap<>();
            sharedConfig.put(TaskSharedConfig.ROUTING_CODE, routingCode);
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (int i = 0; i < messageCount; i++) {
                inputs.add(Map.of(
                        "seq", i,
                        "taskName", taskName,
                        "target", taskName + "-target-" + i
                ));
            }
            return new TaskCreateSpec(
                    userId,
                    projectCode,
                    taskName,
                    defaultEventCode(projectCode),
                    Map.copyOf(sharedConfig),
                    List.copyOf(inputs),
                    batchSize,
                    defaultMaxRetryCount,
                    maxRuntimeSeconds
            );
        }

        public static TaskCreateSpec multiMessageWithFailFlags(String userId,
                                                               String projectCode,
                                                               String taskName,
                                                               String routingCode,
                                                               List<Boolean> failFlags,
                                                               int batchSize,
                                                               int defaultMaxRetryCount,
                                                               int maxRuntimeSeconds) {
            Map<String, Object> sharedConfig = new LinkedHashMap<>();
            sharedConfig.put(TaskSharedConfig.ROUTING_CODE, routingCode);
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (int i = 0; i < failFlags.size(); i++) {
                inputs.add(Map.of(
                        "seq", i,
                        "taskName", taskName,
                        "target", taskName + "-target-" + i,
                        "shouldFail", failFlags.get(i)
                ));
            }
            return new TaskCreateSpec(
                    userId,
                    projectCode,
                    taskName,
                    defaultEventCode(projectCode),
                    Map.copyOf(sharedConfig),
                    List.copyOf(inputs),
                    batchSize,
                    defaultMaxRetryCount,
                    maxRuntimeSeconds
            );
        }

        public static TaskCreateSpec singleMessage(String userId,
                                                   String projectCode,
                                                   String taskName,
                                                   String routingCode,
                                                   int defaultMaxRetryCount,
                                                   int maxRuntimeSeconds,
                                                   Map<String, Object> extraSharedConfig) {
            Map<String, Object> sharedConfig = new LinkedHashMap<>();
            sharedConfig.put(TaskSharedConfig.ROUTING_CODE, routingCode);
            if (extraSharedConfig != null && !extraSharedConfig.isEmpty()) {
                sharedConfig.putAll(extraSharedConfig);
            }
            return new TaskCreateSpec(
                    userId,
                    projectCode,
                    taskName,
                    defaultEventCode(projectCode),
                    Map.copyOf(sharedConfig),
                    List.of(Map.of(
                            "seq", 0,
                            "taskName", taskName,
                            "target", taskName + "-target-0"
                    )),
                    1,
                    defaultMaxRetryCount,
                    maxRuntimeSeconds
            );
        }
    }

    private static String defaultEventCode(String projectCode) {
        return switch (projectCode) {
            case "crawlerApp" -> "crawler.fetch-page";
            case "testApp", "otherApp", "demoApp" -> "demo.dispatch";
            default -> "demo.dispatch";
        };
    }
}
