package com.xa.mass.transport.runtime;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingWorkerSystemEventChannelTest {

    @Test
    void workerOnlineDelegatesAndEmitsCanonicalTraceEvent() {
        RecordingWorkerSystemEventChannel delegate = new RecordingWorkerSystemEventChannel();
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        TracingWorkerSystemEventChannel channel = new TracingWorkerSystemEventChannel(delegate, sink);

        channel.publishWorkerOnline("worker-1", "websocket connected", "trace-1");

        assertEquals(List.of("online:worker-1:websocket connected:trace-1"), delegate.events);
        assertEquals(1, sink.events.size());
        ExecutionEvent event = sink.events.get(0);
        assertEquals(ExecutionEventType.WORKER_ONLINE, event.getEventType());
        assertEquals("worker-1", event.getIdentity().workerId());
        assertEquals("trace-1", event.getTraceId());
        assertEquals("WorkerSystemEventChannel", event.getAttrs().get("source"));
        assertEquals("websocket connected", event.getAttrs().get("reason"));
        assertEquals("SUCCESS", event.getAttrs().get("result"));
    }

    @Test
    void workerOfflineDelegatesAndEmitsCanonicalTraceEventWithoutTraceId() {
        RecordingWorkerSystemEventChannel delegate = new RecordingWorkerSystemEventChannel();
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        TracingWorkerSystemEventChannel channel = new TracingWorkerSystemEventChannel(delegate, sink);

        channel.publishWorkerOffline("worker-2", "socket disconnected", null);

        assertEquals(List.of("offline:worker-2:socket disconnected:null"), delegate.events);
        assertEquals(1, sink.events.size());
        ExecutionEvent event = sink.events.get(0);
        assertEquals(ExecutionEventType.WORKER_OFFLINE, event.getEventType());
        assertEquals("worker-2", event.getIdentity().workerId());
        assertNull(event.getTraceId());
    }

    @Test
    void workerHeartbeatOnlyDelegates() {
        RecordingWorkerSystemEventChannel delegate = new RecordingWorkerSystemEventChannel();
        RecordingExecutionEventSink sink = new RecordingExecutionEventSink();
        TracingWorkerSystemEventChannel channel = new TracingWorkerSystemEventChannel(delegate, sink);

        channel.publishWorkerHeartbeat("worker-3", "poll heartbeat", "trace-3");

        assertEquals(List.of("heartbeat:worker-3:poll heartbeat:trace-3"), delegate.events);
        assertTrue(sink.events.isEmpty());
    }

    private static final class RecordingWorkerSystemEventChannel implements WorkerSystemEventChannel {
        private final List<String> events = new ArrayList<>();

        @Override
        public void publishWorkerOnline(String workerId, String reason, String traceId) {
            events.add("online:" + workerId + ":" + reason + ":" + traceId);
        }

        @Override
        public void publishWorkerOffline(String workerId, String reason, String traceId) {
            events.add("offline:" + workerId + ":" + reason + ":" + traceId);
        }

        @Override
        public void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
            events.add("heartbeat:" + workerId + ":" + reason + ":" + traceId);
        }
    }

    private static final class RecordingExecutionEventSink implements ExecutionEventSink {
        private final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public void emit(ExecutionEvent event) {
            events.add(event);
        }
    }
}
