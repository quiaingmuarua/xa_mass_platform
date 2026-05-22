package com.xa.mass.transport.runtime;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decorates worker system events with canonical execution trace events while
 * preserving the existing system-event channel behavior.
 */
public final class TracingWorkerSystemEventChannel implements WorkerSystemEventChannel {

    private final WorkerSystemEventChannel delegate;
    private final ExecutionEventSink traceSink;

    public TracingWorkerSystemEventChannel(WorkerSystemEventChannel delegate, ExecutionEventSink traceSink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.traceSink = traceSink == null ? new NoopExecutionEventSink() : traceSink;
    }

    @Override
    public void publishWorkerOnline(String workerId, String reason, String traceId) {
        delegate.publishWorkerOnline(workerId, reason, traceId);
        emitWorkerReachabilityEvent(ExecutionEventType.WORKER_ONLINE, workerId, reason, traceId);
    }

    @Override
    public void publishWorkerOffline(String workerId, String reason, String traceId) {
        delegate.publishWorkerOffline(workerId, reason, traceId);
        emitWorkerReachabilityEvent(ExecutionEventType.WORKER_OFFLINE, workerId, reason, traceId);
    }

    @Override
    public void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
        delegate.publishWorkerHeartbeat(workerId, reason, traceId);
    }

    private void emitWorkerReachabilityEvent(ExecutionEventType eventType,
                                             String workerId,
                                             String reason,
                                             String traceId) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source", "WorkerSystemEventChannel");
        attrs.put("reason", reason);
        attrs.put("result", "SUCCESS");

        ExecutionEvent.Builder builder = ExecutionEvent.builder()
                .eventType(eventType)
                .identity(identity -> identity.workerId(workerId))
                .attrs(attrs);
        if (traceId != null && !traceId.isBlank()) {
            builder.traceId(traceId);
        }
        traceSink.emit(builder.build());
    }
}
