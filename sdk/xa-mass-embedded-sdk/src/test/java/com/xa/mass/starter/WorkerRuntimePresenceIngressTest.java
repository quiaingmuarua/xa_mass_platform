package com.xa.mass.starter;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.transport.channel.WorkerPresenceEventType;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockRuntime;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.presence.InMemoryWorkerPresenceRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkerRuntimePresenceIngressTest {

    @Test
    void connectedSessionProjectsReachabilityAndOnlineTrace() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "session-1", 1_000L, "connected", "trace-1"
        ));

        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, sink.events.size());
        ExecutionEvent event = sink.events.get(0);
        assertEquals(ExecutionEventType.WORKER_ONLINE, event.getEventType());
        assertEquals("worker-1", event.getIdentity().workerId());
        assertEquals("trace-1", event.getTraceId());
        assertEquals("WorkerPresenceIngress", event.getAttrs().get("source"));
        assertEquals("websocket", event.getAttrs().get("adapterId"));
        assertEquals("route-1", event.getAttrs().get("routeKey"));
        assertEquals("CONNECTED", event.getAttrs().get("eventType"));
        assertEquals("connected", event.getAttrs().get("reason"));
        assertEquals("SUCCESS", event.getAttrs().get("result"));
    }

    @Test
    void connectedSessionPreservesAdapterMailboxKeyForDeliveryTargetEvidence() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "mailbox-a", "route-1", "session-1",
                1_000L, "connected", "trace-1"
        ));

        var target = runtime.resolveDeliveryTarget("worker-1").orElseThrow();
        assertEquals("worker-1", target.workerId());
        assertEquals("mailbox-a", target.adapterMailboxKey());
    }

    @Test
    void heartbeatDoesNotCreatePresenceWhenSessionWasNotConnected() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionHeartbeat(presenceEvent(WorkerPresenceEventType.HEARTBEAT,
                "worker-1", "polling", "route-1", "session-1", 1_000L, "heartbeat", "trace-1"
        ));

        assertEquals(WorkerReachabilityState.UNKNOWN, runtime.getWorkerReachability("worker-1"));
        assertEquals(0, sink.events.size());
    }

    @Test
    void heartbeatRefreshesConnectedPresenceWithoutAdditionalTrace() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "polling", "route-1", "session-1", 1_000L, "connected", "trace-1"
        ));
        ingress.sessionHeartbeat(presenceEvent(WorkerPresenceEventType.HEARTBEAT,
                "worker-1", "polling", "route-1", "session-1", 1_001L, "heartbeat", "trace-2"
        ));

        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, sink.events.size());
        assertEquals(ExecutionEventType.WORKER_ONLINE, sink.events.get(0).getEventType());
        assertEquals("CONNECTED", sink.events.get(0).getAttrs().get("eventType"));
    }

    @Test
    void staleDisconnectDoesNotEmitOfflineUntilLastSessionEnds() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "old-session", 1_000L, "connected", "old"
        ));
        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "new-session", 1_001L, "connected", "new"
        ));
        ingress.sessionDisconnected(presenceEvent(WorkerPresenceEventType.DISCONNECTED,
                "worker-1", "websocket", "route-1", "old-session", 1_002L, "stale-disconnect", "old"
        ));

        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, sink.events.size());
        assertEquals(ExecutionEventType.WORKER_ONLINE, sink.events.get(0).getEventType());

        ingress.sessionDisconnected(presenceEvent(WorkerPresenceEventType.DISCONNECTED,
                "worker-1", "websocket", "route-1", "new-session", 1_003L, "disconnected", null
        ));

        assertEquals(WorkerReachabilityState.OFFLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(2, sink.events.size());
        ExecutionEvent offline = sink.events.get(1);
        assertEquals(ExecutionEventType.WORKER_OFFLINE, offline.getEventType());
        assertNull(offline.getTraceId());
        assertEquals("DISCONNECTED", offline.getAttrs().get("eventType"));
    }

    @Test
    void staleHeartbeatForReplacedSessionDoesNotRefreshPresence() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "old-session", 1_000L, "connected", null
        ));
        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "new-session", 1_001L, "connected", null
        ));
        ingress.sessionDisconnected(presenceEvent(WorkerPresenceEventType.DISCONNECTED,
                "worker-1", "websocket", "route-1", "old-session", 1_002L, "replaced", null
        ));
        ingress.sessionHeartbeat(presenceEvent(WorkerPresenceEventType.HEARTBEAT,
                "worker-1", "websocket", "route-1", "old-session", 1_003L, "stale-heartbeat", null
        ));

        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, sink.events.size());
        assertEquals(ExecutionEventType.WORKER_ONLINE, sink.events.get(0).getEventType());
    }

    @Test
    void disconnectingOneOfMultipleAdaptersKeepsWorkerReachable() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(runtime, null, sink);

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "ws", 1_000L, "connected", null
        ));
        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "polling", "route-1", "poll", 1_001L, "connected", null
        ));
        ingress.sessionDisconnected(presenceEvent(WorkerPresenceEventType.DISCONNECTED,
                "worker-1", "websocket", "route-1", "ws", 1_002L, "disconnected", null
        ));

        assertEquals(WorkerReachabilityState.ONLINE, runtime.getWorkerReachability("worker-1"));
        assertEquals(1, sink.events.size());
        assertTrue(sink.events.stream().allMatch(event -> event.getEventType() == ExecutionEventType.WORKER_ONLINE));
    }

    @Test
    void currentSessionDisconnectEmitsNegativeDispatchBlock() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        WorkerDispatchBlockRuntime blockRuntime = mock(WorkerDispatchBlockRuntime.class);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(
                runtime,
                blockRuntime,
                sink
        );

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "session-1", 1_000L, "connected", null
        ));
        ingress.sessionDisconnected(presenceEvent(WorkerPresenceEventType.DISCONNECTED,
                "worker-1", "websocket", "route-1", "session-1", 1_001L, "closed", null
        ));

        verify(blockRuntime).blockWorkerDispatch(eq("worker-1"), argThat(signal ->
                signal.source() == WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED
                        && signal.observedAtMillis() == 1_001L
                        && "closed".equals(signal.reason())
        ));
    }

    @Test
    void staleSessionDisconnectDoesNotEmitNegativeDispatchBlock() {
        InMemoryWorkerPresenceRuntime runtime = new InMemoryWorkerPresenceRuntime(Long.MAX_VALUE);
        WorkerDispatchBlockRuntime blockRuntime = mock(WorkerDispatchBlockRuntime.class);
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        WorkerRuntimePresenceIngress ingress = new WorkerRuntimePresenceIngress(
                runtime,
                blockRuntime,
                sink
        );

        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "old-session", 1_000L, "connected", null
        ));
        ingress.sessionConnected(presenceEvent(WorkerPresenceEventType.CONNECTED,
                "worker-1", "websocket", "route-1", "new-session", 1_001L, "connected", null
        ));
        ingress.sessionDisconnected(presenceEvent(WorkerPresenceEventType.DISCONNECTED,
                "worker-1", "websocket", "route-1", "old-session", 1_002L, "stale", null
        ));

        verify(blockRuntime, never()).blockWorkerDispatch(eq("worker-1"), any(WorkerDispatchBlockSignal.class));
    }

    private static final class RecordingExecutionEventSink implements ExecutionEventSink {
        private final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public void emit(ExecutionEvent event) {
            events.add(event);
        }
    }

    private static WorkerSessionPresenceEvent presenceEvent(WorkerPresenceEventType eventType,
                                                           String workerId,
                                                           String adapterId,
                                                           String routeKey,
                                                           String sessionToken,
                                                           long observedAtMillis,
                                                           String reason,
                                                           String traceId) {
        return presenceEvent(
                eventType,
                workerId,
                adapterId,
                adapterId,
                routeKey,
                sessionToken,
                observedAtMillis,
                reason,
                traceId
        );
    }

    private static WorkerSessionPresenceEvent presenceEvent(WorkerPresenceEventType eventType,
                                                           String workerId,
                                                           String adapterId,
                                                           String adapterMailboxKey,
                                                           String routeKey,
                                                           String sessionToken,
                                                           long observedAtMillis,
                                                           String reason,
                                                           String traceId) {
        return new WorkerSessionPresenceEvent(
                workerId,
                adapterId,
                adapterMailboxKey,
                routeKey,
                sessionToken,
                eventType,
                observedAtMillis,
                reason,
                traceId
        );
    }
}
