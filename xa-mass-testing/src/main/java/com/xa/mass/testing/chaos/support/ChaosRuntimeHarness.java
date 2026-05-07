package com.xa.mass.testing.chaos.support;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
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
    private final int transportPort;
    private final String endpointPath;

    private ChaosRuntimeHarness(MassSdkApplication app, int transportPort, String endpointPath) {
        this.app = app;
        this.transportPort = transportPort;
        this.endpointPath = endpointPath;
    }

    public static ChaosRuntimeHarness createWebSocket(WebSocketRuntimeConfig config) {
        int transportPort = ChaosSupport.findFreePort();
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(transportPort, config.endpointPath())
                                .enabled(true)
                                .serverEnabled(true))
                        .inputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(config.workerThreads())
                        .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                        .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                        .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds()))
                .build();
        return new ChaosRuntimeHarness(app, transportPort, config.endpointPath());
    }

    public static ChaosRuntimeHarness createPolling(PollingRuntimeConfig config) {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .inputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>(config.queuePrefix() + "-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(config.workerThreads())
                        .assignmentRetryDelayMillis(config.assignmentRetryDelayMillis())
                        .leaseWatchdogIntervalSeconds(config.leaseWatchdogIntervalSeconds())
                        .taskMessageLeaseSeconds(config.taskMessageLeaseSeconds()))
                .build();
        return new ChaosRuntimeHarness(app, 0, "");
    }

    public MassSdkApplication app() {
        return app;
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

    public Task createApprovedTask(TaskCreateSpec spec) {
        Task task = app.createTask(MassTaskCreateRequest.builder()
                .userId(spec.userId())
                .project(spec.projectCode())
                .taskName(spec.taskName())
                .sharedConfig(spec.sharedConfig())
                .inputs(spec.inputs())
                .batchSize(spec.batchSize())
                .defaultMsgMaxRetryCount(spec.defaultMsgMaxRetryCount())
                .openEnded(false)
                .maxRuntimeSeconds(spec.maxRuntimeSeconds())
                .build());
        ChaosSupport.require(app.approveTask(task.getTid()), "task approval should succeed for " + task.getTid());
        return task;
    }

    public void waitForWorkerOnline(String workerId, int timeoutSeconds, String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> app.isWorkerOnline(workerId), timeoutSeconds, failureMessage);
    }

    public void waitForWorkerOffline(String workerId, int timeoutSeconds, String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> !app.isWorkerOnline(workerId), timeoutSeconds, failureMessage);
    }

    public TaskMsg waitForSingleMessage(String taskId, int timeoutSeconds) throws Exception {
        ChaosSupport.waitForCondition(
                () -> app.getTaskMessageSnapshot(taskId, 1).messages().size() == 1,
                timeoutSeconds,
                "task should materialize exactly one logical message"
        );
        return app.getTaskMessageSnapshot(taskId, 1).messages().get(0);
    }

    public TaskMsgAttempt waitForActiveAttemptOnWorker(String taskId,
                                                       String messageId,
                                                       String workerId,
                                                       int timeoutSeconds,
                                                       String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(() -> {
            TaskMsgAttempt attempt = app.getLatestActiveTaskMessageAttempt(taskId, messageId);
            return attempt != null
                    && workerId.equals(attempt.getWorkerId())
                    && attempt.getStatus() != null
                    && !attempt.getStatus().isFinal();
        }, timeoutSeconds, failureMessage);
        return app.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    public void waitForAttemptCount(String taskId,
                                    String messageId,
                                    int minimumAttempts,
                                    int timeoutSeconds,
                                    String failureMessage) throws Exception {
        ChaosSupport.waitForCondition(
                () -> app.getTaskMessageAttemptAuditTrail(taskId, messageId).size() >= minimumAttempts,
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
                    Task current = app.getTask(taskId);
                    return current != null && current.getStatus() == TaskStatus.TERMINAL;
                },
                timeoutSeconds,
                failureMessage
        );
        return snapshotTaskOutcome(taskId, messageLimit);
    }

    public TaskOutcomeSnapshot snapshotTaskOutcome(String taskId, int messageLimit) {
        Task task = app.getTask(taskId);
        ChaosSupport.require(task != null, "task should exist: " + taskId);
        TaskMessageSnapshot messageSnapshot = app.getTaskMessageSnapshot(taskId, messageLimit);
        List<TaskMsg> messages = messageSnapshot.messages();
        List<TaskOutcomeSnapshot.MessageOutcomeSnapshot> snapshots = new ArrayList<>(messages.size());
        for (TaskMsg message : messages) {
            List<TaskMsgAttempt> attempts = app.getTaskMessageAttemptAuditTrail(taskId, message.getMessageId());
            snapshots.add(new TaskOutcomeSnapshot.MessageOutcomeSnapshot(
                    message.getMessageId(),
                    ChaosSupport.enumName(message.getStatus()),
                    ChaosSupport.enumName(message.getFinalReason()),
                    message.getRetryCount(),
                    message.getLatestAttemptWorkerId(),
                    attempts.stream().map(TaskOutcomeSnapshot.AttemptOutcomeSnapshot::fromAttempt).toList()
            ));
        }
        return new TaskOutcomeSnapshot(
                task.getTid(),
                ChaosSupport.enumName(task.getStatus()),
                ChaosSupport.enumName(task.getTerminalReason()),
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
                                 Map<String, Object> sharedConfig,
                                 List<Map<String, Object>> inputs,
                                 int batchSize,
                                 int defaultMsgMaxRetryCount,
                                 int maxRuntimeSeconds) {
        public static TaskCreateSpec singleMessage(String userId,
                                                   String projectCode,
                                                   String taskName,
                                                   String routingCode,
                                                   int defaultMsgMaxRetryCount,
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
                    Map.copyOf(sharedConfig),
                    List.of(Map.of(
                            "seq", 0,
                            "taskName", taskName,
                            "target", taskName + "-target-0"
                    )),
                    1,
                    defaultMsgMaxRetryCount,
                    maxRuntimeSeconds
            );
        }
    }
}

