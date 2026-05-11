package com.xa.mass.testing.chaos.support;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TransportOutboundMessage;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChaosRuntimeHarness implements AutoCloseable {

    private final MassSdkApplication app;
    private final TaskDetailStore taskDetailStore;
    private final int transportPort;
    private final String endpointPath;

    private ChaosRuntimeHarness(MassSdkApplication app,
                                TaskDetailStore taskDetailStore,
                                int transportPort,
                                String endpointPath) {
        this.app = app;
        this.taskDetailStore = taskDetailStore;
        this.transportPort = transportPort;
        this.endpointPath = endpointPath;
    }

    public static ChaosRuntimeHarness createWebSocket(WebSocketRuntimeConfig config) {
        return createWebSocket(config, null);
    }

    public static ChaosRuntimeHarness createWebSocket(WebSocketRuntimeConfig config,
                                                      ExecutionEventSink traceSink) {
        int transportPort = ChaosSupport.findFreePort();
        InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
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
                            .taskStorage(taskStorage)
                            .taskDetailStore(taskStorage);
                    if (traceSink != null) {
                        engine.executionEventSink(traceSink);
                    }
                });
        return new ChaosRuntimeHarness(builder.build(), taskStorage, transportPort, config.endpointPath());
    }

    public static ChaosRuntimeHarness createPolling(PollingRuntimeConfig config) {
        return createPolling(config, null);
    }

    public static ChaosRuntimeHarness createPolling(PollingRuntimeConfig config,
                                                    ExecutionEventSink traceSink) {
        InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
        MassSdk.Builder builder = MassSdk.builder()
                .transport(transport -> transport
                        .inputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> {
                    engine.enabled(true)
                            .workerThreads(config.workerThreads())
                            .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                            .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                            .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds())
                            .taskStorage(taskStorage)
                            .taskDetailStore(taskStorage);
                    if (traceSink != null) {
                        engine.executionEventSink(traceSink);
                    }
                });
        return new ChaosRuntimeHarness(builder.build(), taskStorage, 0, "");
    }

    public MassSdkApplication app() {
        return app;
    }

    public TaskDetailStore taskDetailStore() {
        return taskDetailStore;
    }

    public void start() {
        app.start();
    }

    public void registerRealtimeWorker(String workerId,
                                       String workerGroupId,
                                       String projectCode,
                                       String routingCode) {
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .supportedProjects(List.of(projectCode))
                .eventBindings(List.of(
                        WorkerEventBinding.builder()
                                .eventCode(defaultEventCode(projectCode))
                                .projectCodes(List.of(projectCode))
                                .build()
                ))
                .transportHint(WorkerTransportHints.REALTIME)
                .build());
        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId(workerId + "-context")
                .workerId(workerId)
                .project(projectCode)
                .routingTags(Set.of(routingCode))
                .build());
    }

    public void registerPollingWorker(String workerId,
                                      String workerGroupId,
                                      String projectCode,
                                      String routingCode) {
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .supportedProjects(List.of(projectCode))
                .eventBindings(List.of(
                        WorkerEventBinding.builder()
                                .eventCode(defaultEventCode(projectCode))
                                .projectCodes(List.of(projectCode))
                                .build()
                ))
                .transportHint(WorkerTransportHints.POLLING)
                .adapterId("polling")
                .build());
        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId(workerId + "-context")
                .workerId(workerId)
                .project(projectCode)
                .routingTags(Set.of(routingCode))
                .build());
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
        ChaosSupport.waitForCondition(() -> app.isWorkerOnline(workerId), timeoutSeconds, failureMessage);
    }

    public void waitForWorkerOffline(String workerId, int timeoutSeconds, String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> !app.isWorkerOnline(workerId), timeoutSeconds, failureMessage);
    }

    public CompatibilityMessageView waitForSingleMessage(String taskId, int timeoutSeconds) throws Exception {
        ChaosSupport.waitForCondition(
                () -> ProjectionTestViews.snapshot(taskDetailStore, taskId, 1).messages().size() == 1,
                timeoutSeconds,
                "task should materialize exactly one logical message"
        );
        return ProjectionTestViews.snapshot(taskDetailStore, taskId, 1).messages().get(0);
    }

    public CompatibilityAttemptView waitForActiveAttemptOnWorker(String taskId,
                                                                 String messageId,
                                                                 String workerId,
                                                                 int timeoutSeconds,
                                                                 String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> {
            CompatibilityAttemptView attempt = ProjectionTestViews.latestActiveAttempt(taskDetailStore, taskId, messageId);
            return attempt != null
                    && workerId.equals(attempt.workerId())
                    && attempt.status() != null
                    && !attempt.status().equals("SUCCEEDED")
                    && !attempt.status().equals("FAILED")
                    && !attempt.status().equals("EXPIRED")
                    && !attempt.status().equals("REVOKED");
        }, timeoutSeconds, failureMessage);
        return ProjectionTestViews.latestActiveAttempt(taskDetailStore, taskId, messageId);
    }

    public void waitForAttemptCount(String taskId,
                                    String messageId,
                                    int minimumAttempts,
                                    int timeoutSeconds,
                                    String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(
                () -> ProjectionTestViews.attempts(taskDetailStore, taskId, messageId).size() >= minimumAttempts,
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
        CompatibilityMessageSnapshot messageSnapshot = ProjectionTestViews.snapshot(taskDetailStore, taskId, messageLimit);
        List<CompatibilityMessageView> messages = messageSnapshot.messages();
        List<TaskOutcomeSnapshot.MessageOutcomeSnapshot> snapshots = new ArrayList<>(messages.size());
        for (CompatibilityMessageView message : messages) {
            List<CompatibilityAttemptView> attempts = ProjectionTestViews.attempts(taskDetailStore, taskId, message.messageId());
            snapshots.add(new TaskOutcomeSnapshot.MessageOutcomeSnapshot(
                    message.messageId(),
                    message.status(),
                    message.finalReason(),
                    message.retryCount(),
                    message.latestAttemptWorkerId(),
                    attempts.stream().map(TaskOutcomeSnapshot.AttemptOutcomeSnapshot::fromAttempt).toList()
            ));
        }
        return new TaskOutcomeSnapshot(
                task.getTaskId(),
                task.getStatus(),
                task.getTerminalReason(),
                snapshots
        );
    }

    public URI serverUri(String workerId) {
        ChaosSupport.require(transportPort > 0, "websocket server port must be allocated");
        return URI.create("ws://127.0.0.1:" + transportPort + endpointPath + "?workerId=" + workerId);
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
