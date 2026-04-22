package com.xa.mass.sdk.worker;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.starter.worker.PollingWorkerAdapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing pull worker session for crawlers and other polling executors.
 */
public final class PollingWorkerSession {

    private final String workerId;
    private final PollingWorkerAdapter adapter;

    public PollingWorkerSession(String workerId, PollingWorkerAdapter adapter) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId;
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public String workerId() {
        return workerId;
    }

    public void connect() {
        adapter.announceWorkerOnline(workerId, "polling-session-connect");
    }

    public void disconnect() {
        adapter.announceWorkerOffline(workerId, "polling-session-disconnect");
    }

    public void heartbeat() {
        adapter.publishWorkerHeartbeat(workerId, "polling-session-heartbeat");
    }

    public List<TaskDispatchItem> poll(int maxMessages) {
        return adapter.pollTaskMessages(workerId, maxMessages);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem, boolean success, String detail) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem.getTaskId(), dispatchItem.getMsgId(), success, detail, null, Map.of());
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem.getTaskId(), dispatchItem.getMsgId(), success, detail, null, output);
    }

    public boolean submitResult(String taskId,
                                String msgId,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        return adapter.ingestTaskResult(taskId, msgId, success, detail, errorCode, output);
    }
}
