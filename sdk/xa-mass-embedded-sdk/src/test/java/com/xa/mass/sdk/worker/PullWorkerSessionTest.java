package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullWorkerSessionTest {

    @Test
    void pollResultDelegatesToTaskPullChannelWithRegisteredWorkerId() {
        TaskPullChannel taskPullChannel = mock(TaskPullChannel.class);
        TaskPullResult expected = TaskPullResult.delivered(List.of(item("msg-1")));
        when(taskPullChannel.pollTaskMessagesResult("worker-1", 5, 250L)).thenReturn(expected);

        PullWorkerSession session = session(taskPullChannel, mock(TaskResultIngestChannel.class),
                new InMemoryTransportRouteOwnerStore());

        TaskPullResult result = session.pollResult(5, 250L);

        assertEquals(TaskPullStatus.DELIVERED, result.getStatus());
        assertEquals(routeKey(), session.routeKey());
        assertEquals(List.of("msg-1"), result.getItems().stream().map(PulledTaskDispatch::getMessageId).toList());
    }

    @Test
    void pollReturnsPulledTaskItemsFromExplicitPullResult() {
        TaskPullChannel taskPullChannel = mock(TaskPullChannel.class);
        when(taskPullChannel.pollTaskMessagesResult("worker-1", 3, 100L))
                .thenReturn(TaskPullResult.delivered(List.of(item("msg-1"), item("msg-2"))));

        PullWorkerSession session = session(taskPullChannel, mock(TaskResultIngestChannel.class),
                new InMemoryTransportRouteOwnerStore());

        List<PulledTaskDispatch> items = session.poll(3, 100L);

        assertEquals(List.of("msg-1", "msg-2"), items.stream().map(PulledTaskDispatch::getMessageId).toList());
    }

    @Test
    void submitResultUsesSessionRouteKeyAndPulledAttemptContext() {
        TaskResultIngestChannel resultIngestChannel = mock(TaskResultIngestChannel.class);
        when(resultIngestChannel.ingest(any(TransportResultEnvelope.class))).thenReturn(true);

        PullWorkerSession session = session(mock(TaskPullChannel.class), resultIngestChannel,
                new InMemoryTransportRouteOwnerStore());

        PulledTaskDispatch item = new PulledTaskDispatch(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                Map.of("target", "target-1"),
                Map.of(),
                "attempt-1",
                1,
                0,
                "batch-1"
        );

        session.submitResult(item, true, "ok");

        var captured = org.mockito.ArgumentCaptor.forClass(TransportResultEnvelope.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertEquals(routeKey(), captured.getValue().getRouteKey());
        assertEquals("attempt-1", captured.getValue().getAttemptId());
    }

    @Test
    void submitResultWithoutDispatchRouteKeyUsesCanonicalRouteKey() {
        TaskResultIngestChannel resultIngestChannel = mock(TaskResultIngestChannel.class);
        when(resultIngestChannel.ingest(any(TransportResultEnvelope.class))).thenReturn(true);

        PullWorkerSession session = session(mock(TaskPullChannel.class), resultIngestChannel,
                new InMemoryTransportRouteOwnerStore());

        session.submitResult("task-1", "msg-1", true, "ok", null, Map.of());

        var captured = org.mockito.ArgumentCaptor.forClass(TransportResultEnvelope.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertEquals(routeKey(), captured.getValue().getRouteKey());
    }

    @Test
    void connectHeartbeatDisconnectWritePresenceWithCanonicalRouteAndSessionToken() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        PullWorkerSession session = session(mock(TaskPullChannel.class), mock(TaskResultIngestChannel.class),
                routeOwnerStore, presenceIngress);

        assertTrue(session.connectAndClaim("connected"));
        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertTrue(routeOwnerStore.hasActiveRouteOwner("polling", routeKey()));
        assertEquals("conn-1", routeOwnerStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .connectionId());
        assertEquals(List.of("CONNECTED:worker-1:polling:" + routeKey() + ":conn-1:connected:conn-1"),
                presenceIngress.events);

        assertFalse(staleSession("stale-conn", routeOwnerStore, new RecordingWorkerPresenceIngress())
                .disconnectIfCurrent("stale-disconnect"));
        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));

        assertFalse(staleSession("stale-conn", routeOwnerStore, new RecordingWorkerPresenceIngress())
                .refreshHeartbeatIfCurrent("stale-heartbeat"));
        assertTrue(session.refreshHeartbeatIfCurrent("heartbeat"));
        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling:" + routeKey() + ":conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling:" + routeKey() + ":conn-1:heartbeat:conn-1"
                ),
                presenceIngress.events);

        assertTrue(session.disconnectIfCurrent("disconnect"));
        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("polling", "worker-1").isEmpty());
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling:" + routeKey() + ":conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling:" + routeKey() + ":conn-1:heartbeat:conn-1",
                        "DISCONNECTED:worker-1:polling:" + routeKey() + ":conn-1:disconnect:conn-1"
                ),
                presenceIngress.events);
    }

    private static PullWorkerSession session(TaskPullChannel taskPullChannel,
                                             TaskResultIngestChannel resultIngestChannel,
                                             InMemoryTransportRouteOwnerStore routeOwnerStore) {
        return session("conn-1", taskPullChannel, resultIngestChannel, routeOwnerStore,
                new RecordingWorkerPresenceIngress());
    }

    private static PullWorkerSession staleSession(String connectionId,
                                                  InMemoryTransportRouteOwnerStore routeOwnerStore,
                                                  WorkerPresenceIngress presenceIngress) {
        return session(connectionId, mock(TaskPullChannel.class), mock(TaskResultIngestChannel.class), routeOwnerStore,
                presenceIngress);
    }

    private static PullWorkerSession session(TaskPullChannel taskPullChannel,
                                             TaskResultIngestChannel resultIngestChannel,
                                             InMemoryTransportRouteOwnerStore routeOwnerStore,
                                             WorkerPresenceIngress presenceIngress) {
        return session("conn-1", taskPullChannel, resultIngestChannel, routeOwnerStore, presenceIngress);
    }

    private static PullWorkerSession session(String connectionId,
                                             TaskPullChannel taskPullChannel,
                                             TaskResultIngestChannel resultIngestChannel,
                                             InMemoryTransportRouteOwnerStore routeOwnerStore,
                                             WorkerPresenceIngress presenceIngress) {
        return new PullWorkerSession(
                "worker-1",
                "group-1",
                "polling",
                connectionId,
                taskPullChannel,
                resultIngestChannel,
                routeOwnerStore,
                presenceIngress,
                "polling"
        );
    }

    private static final class RecordingWorkerPresenceIngress implements WorkerPresenceIngress {
        private final List<String> events = new ArrayList<>();

        @Override
        public void sessionConnected(WorkerSessionPresenceEvent event) {
            events.add(describe(event));
        }

        @Override
        public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
            events.add(describe(event));
        }

        @Override
        public void sessionDisconnected(WorkerSessionPresenceEvent event) {
            events.add(describe(event));
        }

        private String describe(WorkerSessionPresenceEvent event) {
            return event.eventType().name() + ":"
                    + event.workerId() + ":"
                    + event.adapterId() + ":"
                    + event.routeKey() + ":"
                    + event.sessionToken() + ":"
                    + event.reason() + ":"
                    + event.traceId();
        }
    }

    private static String routeKey() {
        return CanonicalWorkerGroupRouteKeyCodec.encode("group-1");
    }

    private static PulledTaskDispatch item(String messageId) {
        return new PulledTaskDispatch(
                "task-1",
                messageId,
                "crawler.fetch-page",
                Map.of("target", "target-1"),
                Map.of(),
                "attempt-" + messageId,
                1,
                0,
                "batch-1"
        );
    }
}
