package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing pull worker session for crawlers, queue consumers, and other
 * executors that receive work by polling instead of server push.
 */
public class PullWorkerSession {

    private final String workerId;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final String transportHint;

    public PullWorkerSession(String workerId,
                             TaskPullChannel taskPullChannel,
                             TaskResultIngestChannel taskResultIngestChannel,
                             WorkerSystemEventChannel systemEventChannel,
                             String transportHint) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId;
        this.taskPullChannel = Objects.requireNonNull(taskPullChannel, "taskPullChannel");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.transportHint = transportHint;
    }

    public String workerId() {
        return workerId;
    }

    public String transportHint() {
        return transportHint;
    }

    public void connect() {
        systemEventChannel.publishWorkerOnline(workerId, "pull-session-connect", workerId);
    }

    public void disconnect() {
        systemEventChannel.publishWorkerOffline(workerId, "pull-session-disconnect", workerId);
    }

    public void heartbeat() {
        systemEventChannel.publishWorkerHeartbeat(workerId, "pull-session-heartbeat", workerId);
    }

    public List<TaskDispatchItem> poll(int maxMessages) {
        return taskPullChannel.pollTaskMessages(workerId, maxMessages);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem, boolean success, String detail) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem.getTaskId(), dispatchItem.getMessageId(), success, detail, null, Map.of());
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem.getTaskId(), dispatchItem.getMessageId(), success, detail, null, output);
    }

    public boolean submitResult(String taskId,
                                String messageId,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        return taskResultIngestChannel.ingest(new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output
        ));
    }
}
