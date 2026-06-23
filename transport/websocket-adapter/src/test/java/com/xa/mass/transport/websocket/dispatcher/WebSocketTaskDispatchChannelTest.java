package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTaskDispatchChannelTest {

    @Test
    void publishesDispatchItemsDirectlyToSelectedWorkerSession() {
        SessionFixture fixture = sessionWithWorker("worker-1");
        DispatchMessage item = request();

        WebSocketTaskDispatchChannel publisher =
                new WebSocketTaskDispatchChannel(fixture.registry());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(item));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());

        ArgumentCaptor<TextWebSocketFrame> captor = ArgumentCaptor.forClass(TextWebSocketFrame.class);
        verify(fixture.channel()).writeAndFlush(captor.capture());

        JsonObject frame = JsonParser.parseString(captor.getValue().text()).getAsJsonObject();
        assertEquals(WorkerChannelFrame.ACTION, frame.get("kind").getAsString());
        assertEquals(item.payload(), frame.get("body").getAsString());
        fixture.registry().shutdown();
    }

    @Test
    void returnsEndpointOfflineWhenRegistryHasNoWorker() {
        AdapterSessionEvidencePublisher sessionEvidencePublisher =
                AdapterSessionEvidencePublisher.noop("websocket", "websocket");
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        WebSocketTaskDispatchChannel publisher =
                new WebSocketTaskDispatchChannel(registry);

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
        registry.shutdown();
    }

    @Test
    void constructorRejectsMissingSessionStore() {
        assertThrows(NullPointerException.class,
                () -> new WebSocketTaskDispatchChannel(null));
    }

    private SessionFixture sessionWithWorker(String workerId) {
        AdapterSessionEvidencePublisher sessionEvidencePublisher =
                AdapterSessionEvidencePublisher.noop("websocket", "websocket");
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        Channel channel = mockActiveChannel(workerId);
        registry.addSession("bucket-1", workerId, channel);
        return new SessionFixture(registry, channel);
    }

    private record SessionFixture(WebSocketSessionRegistry registry, Channel channel) {
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }

    private DispatchMessage request() {
        return new DispatchMessage(
                "delivery-msg-1",
                "worker-1",
                """
                {
                  "messageId": "msg-1",
                  "workerId": "worker-1",
                  "taskId": "task-1",
                  "eventCode": "crawler.fetch-page",
                  "batchId": "batch-0",
                  "retryCount": 0,
                  "input": {"target": "target-1"},
                  "sharedConfig": {"textContent": "hello"}
                }
                """,
                "corr-msg-1",
                0L,
                1L
        );
    }
}
