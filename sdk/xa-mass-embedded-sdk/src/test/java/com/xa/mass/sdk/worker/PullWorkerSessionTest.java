package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(List.of("msg-1"), result.getDispatchViews().stream().map(TaskDispatchItem::getMessageId).toList());
    }

    @Test
    void pollKeepsLegacyListViewOnTopOfExplicitPullResult() {
        TaskPullChannel taskPullChannel = mock(TaskPullChannel.class);
        when(taskPullChannel.pollTaskMessagesResult("worker-1", 3, 100L))
                .thenReturn(TaskPullResult.delivered(List.of(item("msg-1"), item("msg-2"))));

        PullWorkerSession session = session(taskPullChannel, mock(TaskResultIngestChannel.class),
                new InMemoryTransportRouteOwnerStore());

        List<TaskDispatchItem> items = session.poll(3, 100L);

        assertEquals(List.of("msg-1", "msg-2"), items.stream().map(TaskDispatchItem::getMessageId).toList());
    }

    @Test
    void submitResultUsesDispatchRouteKeyInsteadOfAssumingWorkerId() {
        TaskResultIngestChannel resultIngestChannel = mock(TaskResultIngestChannel.class);
        when(resultIngestChannel.ingest(any(TransportResultEnvelope.class))).thenReturn(true);

        PullWorkerSession session = session(mock(TaskPullChannel.class), resultIngestChannel,
                new InMemoryTransportRouteOwnerStore());

        TaskDispatchItem dispatchItem = new TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-1",
                "route-9",
                "worker-1",
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );

        session.submitResult(dispatchItem, true, "ok");

        var captured = org.mockito.ArgumentCaptor.forClass(TransportResultEnvelope.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertEquals("route-9", captured.getValue().getRouteKey());
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
        InMemoryTransportRouteOwnerStore presenceStore = new InMemoryTransportRouteOwnerStore();
        PullWorkerSession session = session(mock(TaskPullChannel.class), mock(TaskResultIngestChannel.class),
                presenceStore);

        session.connect("connected");
        assertTrue(presenceStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertTrue(presenceStore.hasActiveRouteOwner("polling", routeKey()));
        assertEquals("conn-1", presenceStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .connectionId());

        presenceStore.releaseRouteOwner("worker-1", "polling", routeKey(), "stale-conn", "stale-disconnect");
        assertTrue(presenceStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));

        session.heartbeat("heartbeat");
        assertTrue(presenceStore.activeOwnerForSelectedWorker("polling", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));

        session.disconnect("disconnect");
        assertTrue(presenceStore.activeOwnerForSelectedWorker("polling", "worker-1").isEmpty());
    }

    private static PullWorkerSession session(TaskPullChannel taskPullChannel,
                                             TaskResultIngestChannel resultIngestChannel,
                                             InMemoryTransportRouteOwnerStore presenceStore) {
        return new PullWorkerSession(
                "worker-1",
                "group-1",
                "polling",
                "conn-1",
                taskPullChannel,
                resultIngestChannel,
                presenceStore,
                "polling"
        );
    }

    private static String routeKey() {
        return CanonicalWorkerGroupRouteKeyCodec.encode("group-1");
    }

    private static TaskDispatchItem item(String messageId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-" + messageId,
                "worker-1",
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }
}
