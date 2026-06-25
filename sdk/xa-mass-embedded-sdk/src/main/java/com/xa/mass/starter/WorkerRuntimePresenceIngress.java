package com.xa.mass.starter;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import com.xa.mass.worker.runtime.control.WorkerDispatchRecoveryRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.presence.InMemoryWorkerPresenceRuntime;
import com.xa.mass.worker.runtime.presence.WorkerPresenceChange;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects transport session-presence evidence into the worker-runtime
 * embedded reachability and delivery-target projection.
 */
final class WorkerRuntimePresenceIngress implements WorkerPresenceIngress {

    private final InMemoryWorkerPresenceRuntime workerPresenceRuntime;
    private final WorkerDispatchBlockRuntime workerDispatchBlockRuntime;
    private final WorkerDispatchRecoveryRuntime workerDispatchRecoveryRuntime;
    private final WorkerHeartbeatRuntime workerHeartbeatRuntime;
    private final ExecutionEventSink traceSink;

    WorkerRuntimePresenceIngress(InMemoryWorkerPresenceRuntime workerPresenceRuntime,
                                 WorkerDispatchBlockRuntime workerDispatchBlockRuntime,
                                 WorkerDispatchRecoveryRuntime workerDispatchRecoveryRuntime,
                                 WorkerHeartbeatRuntime workerHeartbeatRuntime,
                                 ExecutionEventSink traceSink) {
        this.workerPresenceRuntime = Objects.requireNonNull(workerPresenceRuntime, "workerPresenceRuntime");
        this.workerDispatchBlockRuntime = workerDispatchBlockRuntime;
        this.workerDispatchRecoveryRuntime = workerDispatchRecoveryRuntime;
        this.workerHeartbeatRuntime = workerHeartbeatRuntime;
        this.traceSink = traceSink == null ? new NoopExecutionEventSink() : traceSink;
    }

    @Override
    public void sessionConnected(WorkerSessionPresenceEvent event) {
        WorkerSessionPresenceEvent normalized = Objects.requireNonNull(event, "event");
        WorkerPresenceChange change = workerPresenceRuntime.sessionConnected(
                normalized.workerId(),
                normalized.adapterId(),
                normalized.adapterMailboxKey(),
                normalized.routeKey(),
                normalized.sessionToken(),
                normalized.observedAtMillis(),
                normalized.reason()
        );
        refreshWorkerRuntimeWhenCurrent(change, normalized);
        emitReachabilityTrace(change, normalized);
    }

    @Override
    public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
        WorkerSessionPresenceEvent normalized = Objects.requireNonNull(event, "event");
        WorkerPresenceChange change = workerPresenceRuntime.sessionHeartbeat(
                normalized.workerId(),
                normalized.adapterId(),
                normalized.adapterMailboxKey(),
                normalized.routeKey(),
                normalized.sessionToken(),
                normalized.observedAtMillis(),
                normalized.reason()
        );
        refreshWorkerRuntimeWhenCurrent(change, normalized);
        emitReachabilityTrace(change, normalized);
    }

    @Override
    public void sessionDisconnected(WorkerSessionPresenceEvent event) {
        WorkerSessionPresenceEvent normalized = Objects.requireNonNull(event, "event");
        WorkerPresenceChange change = workerPresenceRuntime.sessionDisconnected(
                normalized.workerId(),
                normalized.adapterId(),
                normalized.adapterMailboxKey(),
                normalized.routeKey(),
                normalized.sessionToken(),
                normalized.observedAtMillis(),
                normalized.reason()
        );
        blockDispatchWhenCurrentSessionGone(change, normalized);
        emitReachabilityTrace(change, normalized);
    }

    private void refreshWorkerRuntimeWhenCurrent(WorkerPresenceChange change, WorkerSessionPresenceEvent event) {
        if (change == null
                || !change.observationAccepted()
                || change.currentState() != WorkerReachabilityState.ONLINE) {
            return;
        }
        if (workerHeartbeatRuntime != null) {
            workerHeartbeatRuntime.refreshWorkerHeartbeat(event.workerId(), event.observedAtMillis());
        }
        if (workerDispatchRecoveryRuntime != null) {
            workerDispatchRecoveryRuntime.recoverWorkerDispatch(
                    event.workerId(),
                    WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED.gateSource(),
                    firstNonBlank(event.reason(), "transport session reachable")
            );
        }
    }

    private void blockDispatchWhenCurrentSessionGone(WorkerPresenceChange change, WorkerSessionPresenceEvent event) {
        if (workerDispatchBlockRuntime == null || change == null
                || !change.observationAccepted() || !change.becameUnreachable()) {
            return;
        }
        workerDispatchBlockRuntime.blockWorkerDispatch(
                event.workerId(),
                new WorkerDispatchBlockSignal(
                        WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED,
                        firstNonBlank(event.reason(), "transport session disconnected"),
                        event.observedAtMillis(),
                        0L
                )
        );
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
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
