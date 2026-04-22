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

    private final PullWorkerSession delegate;

    public PollingWorkerSession(String workerId, PollingWorkerAdapter adapter) {
        this(createDelegate(workerId, adapter));
    }

    public PollingWorkerSession(PullWorkerSession delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    private static PullWorkerSession createDelegate(String workerId, PollingWorkerAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        return new PullWorkerSession(
                workerId,
                adapter,
                adapter,
                new com.xa.mass.transport.channel.WorkerSystemEventChannel() {
                    @Override
                    public void publishWorkerOnline(String workerId, String reason, String traceId) {
                        adapter.announceWorkerOnline(workerId, reason);
                    }

                    @Override
                    public void publishWorkerOffline(String workerId, String reason, String traceId) {
                        adapter.announceWorkerOffline(workerId, reason);
                    }

                    @Override
                    public void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
                        adapter.publishWorkerHeartbeat(workerId, reason);
                    }
                },
                PollingWorkerAdapter.PROTOCOL
        );
    }

    public String workerId() {
        return delegate.workerId();
    }

    public void connect() {
        delegate.connect();
    }

    public void disconnect() {
        delegate.disconnect();
    }

    public void heartbeat() {
        delegate.heartbeat();
    }

    public List<TaskDispatchItem> poll(int maxMessages) {
        return delegate.poll(maxMessages);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem, boolean success, String detail) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return delegate.submitResult(dispatchItem, success, detail);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return delegate.submitResult(dispatchItem, success, detail, output);
    }

    public boolean submitResult(String taskId,
                                String msgId,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        return delegate.submitResult(taskId, msgId, success, detail, errorCode, output);
    }
}
