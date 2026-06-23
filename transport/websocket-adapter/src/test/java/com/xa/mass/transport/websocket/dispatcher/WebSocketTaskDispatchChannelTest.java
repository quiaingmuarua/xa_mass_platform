package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.websocket.session.WebSocketSessionController;
import com.xa.mass.transport.websocket.session.WebSocketSessionEvidenceDriver;
import com.xa.mass.transport.websocket.session.WebSocketSessionRefreshLoop;
import com.xa.mass.transport.websocket.session.WebSocketSessionStore;
import com.xa.mass.transport.websocket.frame.WebSocketWorkerChannelFrameCodec;
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
    void publishesDispatchItemsDirectlyToSessionStoreEndpoint() {
        SessionFixture fixture = sessionWithWorker("worker-1");
        DispatchMessage item = request();

        WebSocketTaskDispatchChannel publisher =
                new WebSocketTaskDispatchChannel(fixture.controller());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(item));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());

        ArgumentCaptor<TextWebSocketFrame> captor = ArgumentCaptor.forClass(TextWebSocketFrame.class);
        verify(fixture.channel()).writeAndFlush(captor.capture());

        JsonObject frame = JsonParser.parseString(captor.getValue().text()).getAsJsonObject();
        assertEquals(WebSocketWorkerChannelFrameCodec.ACTION, frame.get("kind").getAsString());
        assertEquals(item.payload(), frame.get("body").getAsString());
        fixture.controller().shutdown();
    }

    @Test
    void returnsEndpointOfflineWhenSessionStoreHasNoWorker() {
        WebSocketSessionStore sessionStore = new WebSocketSessionStore("websocket");
        WebSocketSessionEvidenceDriver evidenceDriver = new WebSocketSessionEvidenceDriver(
                AdapterSessionEvidencePublisher.noop("websocket", "websocket"));
        WebSocketSessionController controller = new WebSocketSessionController(
                sessionStore,
                evidenceDriver,
                new WebSocketSessionRefreshLoop("websocket", sessionStore, evidenceDriver)
        );
        WebSocketTaskDispatchChannel publisher =
                new WebSocketTaskDispatchChannel(controller);

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
        controller.shutdown();
    }

    @Test
    void constructorRejectsMissingSessionStore() {
        assertThrows(NullPointerException.class,
                () -> new WebSocketTaskDispatchChannel(null));
    }

    private SessionFixture sessionWithWorker(String workerId) {
        WebSocketSessionStore sessionStore = new WebSocketSessionStore("websocket");
        WebSocketSessionEvidenceDriver evidenceDriver = new WebSocketSessionEvidenceDriver(
                AdapterSessionEvidencePublisher.noop("websocket", "websocket"));
        WebSocketSessionController controller = new WebSocketSessionController(
                sessionStore,
                evidenceDriver,
                new WebSocketSessionRefreshLoop("websocket", sessionStore, evidenceDriver)
        );
        Channel channel = mockActiveChannel(workerId);
        controller.addSession("bucket-1", "route-1", workerId, channel);
        return new SessionFixture(controller, channel);
    }

    private record SessionFixture(WebSocketSessionController controller, Channel channel) {
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
