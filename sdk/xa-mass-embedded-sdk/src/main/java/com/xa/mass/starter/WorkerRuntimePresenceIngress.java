package com.xa.mass.starter;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.worker.runtime.resource.WorkerResourceRuntime;
import com.xa.mass.worker.runtime.presence.WorkerPresenceChange;
import com.xa.mass.worker.runtime.presence.WorkerPresenceRuntime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects transport session-presence evidence into the worker-runtime
 * reachability owner.
 */
final class WorkerRuntimePresenceIngress implements WorkerPresenceIngress {

    private final WorkerPresenceRuntime workerPresenceRuntime;
    private final WorkerResourceRuntime workerResourceRuntime;
    private final ExecutionEventSink traceSink;

    WorkerRuntimePresenceIngress(WorkerPresenceRuntime workerPresenceRuntime,
                                 WorkerResourceRuntime workerResourceRuntime,
                                 ExecutionEventSink traceSink) {
        this.workerPresenceRuntime = Objects.requireNonNull(workerPresenceRuntime, "workerPresenceRuntime");
        this.workerResourceRuntime = Objects.requireNonNull(workerResourceRuntime, "workerResourceRuntime");
        this.traceSink = traceSink == null ? new NoopExecutionEventSink() : traceSink;
    }

    @Override
    public void sessionConnected(WorkerSessionPresenceEvent event) {
        WorkerSessionPresenceEvent normalized = Objects.requireNonNull(event, "event");
        WorkerPresenceChange change = workerPresenceRuntime.sessionConnected(
                normalized.workerId(),
                normalized.adapterId(),
                normalized.routeKey(),
                normalized.sessionToken(),
                normalized.observedAtMillis(),
                normalized.reason()
        );
        refreshSlotHeartbeat(normalized);
        emitReachabilityTrace(change, normalized);
    }

    @Override
    public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
        WorkerSessionPresenceEvent normalized = Objects.requireNonNull(event, "event");
        WorkerPresenceChange change = workerPresenceRuntime.sessionHeartbeat(
                normalized.workerId(),
                normalized.adapterId(),
                normalized.routeKey(),
                normalized.sessionToken(),
                normalized.observedAtMillis(),
                normalized.reason()
        );
        if (change.observationAccepted()) {
            refreshSlotHeartbeat(normalized);
        }
        emitReachabilityTrace(change, normalized);
    }

    @Override
    public void sessionDisconnected(WorkerSessionPresenceEvent event) {
        WorkerSessionPresenceEvent normalized = Objects.requireNonNull(event, "event");
        WorkerPresenceChange change = workerPresenceRuntime.sessionDisconnected(
                normalized.workerId(),
                normalized.adapterId(),
                normalized.routeKey(),
                normalized.sessionToken(),
                normalized.observedAtMillis(),
                normalized.reason()
        );
        emitReachabilityTrace(change, normalized);
    }

    private void refreshSlotHeartbeat(WorkerSessionPresenceEvent event) {
        workerResourceRuntime.refreshWorkerHeartbeat(event.workerId(), event.observedAtMillis());
    }

    private void emitReachabilityTrace(WorkerPresenceChange change, WorkerSessionPresenceEvent event) {
        if (change == null || !change.changed()) {
            return;
        }
        ExecutionEventType eventType;
        if (change.becameReachable()) {
            eventType = ExecutionEventType.WORKER_ONLINE;
        } else if (change.becameUnreachable()) {
            eventType = ExecutionEventType.WORKER_OFFLINE;
        } else {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source", "WorkerPresenceIngress");
        attrs.put("adapterId", event.adapterId());
        attrs.put("routeKey", event.routeKey());
        attrs.put("eventType", event.eventType().name());
        attrs.put("reason", event.reason());
        attrs.put("result", "SUCCESS");

        ExecutionEvent.Builder builder = ExecutionEvent.builder()
                .eventType(eventType)
                .identity(identity -> identity.workerId(event.workerId()))
                .attrs(attrs);
        if (event.traceId() != null && !event.traceId().isBlank()) {
            builder.traceId(event.traceId());
        }
        traceSink.emit(builder.build());
    }
}
