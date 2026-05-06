package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing pull worker session for crawlers, queue consumers, and other
 * executors that receive work by polling instead of server push.
 */
public class PullWorkerSession {

    private final String workerId;
    private final String adapterId;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final String transportHint;

    public PullWorkerSession(String workerId,
                             String adapterId,
                             TaskPullChannel taskPullChannel,
                             TaskResultIngestChannel taskResultIngestChannel,
                             WorkerSystemEventChannel systemEventChannel,
                             String transportHint) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId;
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.taskPullChannel = Objects.requireNonNull(taskPullChannel, "taskPullChannel");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.transportHint = transportHint;
    }

    public String workerId() {
        return workerId;
    }

    public String adapterId() {
        return adapterId;
    }

    public String transportHint() {
        return transportHint;
    }

    public void connect() {
        connect("pull-session-connect");
    }

    public void connect(String reason) {
        systemEventChannel.publishWorkerOnline(workerId, normalizeReason(reason, "pull-session-connect"), workerId);
    }

    public void disconnect() {
        disconnect("pull-session-disconnect");
    }

    public void disconnect(String reason) {
        systemEventChannel.publishWorkerOffline(workerId, normalizeReason(reason, "pull-session-disconnect"), workerId);
    }

    public void heartbeat() {
        heartbeat("pull-session-heartbeat");
    }

    public void heartbeat(String reason) {
        systemEventChannel.publishWorkerHeartbeat(workerId, normalizeReason(reason, "pull-session-heartbeat"), workerId);
    }

    public List<TaskDispatchItem> poll(int maxMessages) {
        return poll(maxMessages, 0L);
    }

    public List<TaskDispatchItem> poll(int maxMessages, long timeoutMillis) {
        return taskPullChannel.pollTaskMessages(workerId, maxMessages, timeoutMillis);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem, boolean success, String detail) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem, success, detail, null, Map.of());
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem, success, detail, null, output);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        TaskResultReport report = new TaskResultReport(
                dispatchItem.getTaskId(),
                dispatchItem.getMessageId(),
                success,
                detail,
                errorCode,
                output
        );
        return taskResultIngestChannel.ingest(TransportResultEnvelope.fromDispatchContext(
                adapterId,
                dispatchItem.getWorkerId(),
                workerId,
                dispatchItem.attemptId(),
                report
        ));
    }

    public boolean submitResult(String taskId,
                                String messageId,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        TaskResultReport report = new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output
        );
        return taskResultIngestChannel.ingest(TransportResultEnvelope.fromReport(
                adapterId,
                workerId,
                workerId,
                report
        ));
    }

    private String normalizeReason(String reason, String defaultReason) {
        return reason == null || reason.isBlank() ? defaultReason : reason.trim();
    }
}
