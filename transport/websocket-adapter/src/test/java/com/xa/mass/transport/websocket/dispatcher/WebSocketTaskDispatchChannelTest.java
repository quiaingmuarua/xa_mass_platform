package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.websocket.session.WebSocketSessionStore;
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
        WebSocketSessionStore sessionStore = sessionStoreWithWorker("worker-1");
        DeliveryCommand command = request();

        WebSocketTaskDispatchChannel publisher =
                new WebSocketTaskDispatchChannel(sessionStore);

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(command));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());

        ArgumentCaptor<TextWebSocketFrame> captor = ArgumentCaptor.forClass(TextWebSocketFrame.class);
        verify(sessionStore.activeRecordForWorker("worker-1").channel()).writeAndFlush(captor.capture());

        assertEquals(command.getPayload(), captor.getValue().text());
    }

    @Test
    void returnsEndpointOfflineWhenSessionStoreHasNoWorker() {
        WebSocketTaskDispatchChannel publisher =
                new WebSocketTaskDispatchChannel(new WebSocketSessionStore("websocket"));

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void constructorRejectsMissingSessionStore() {
        assertThrows(NullPointerException.class,
                () -> new WebSocketTaskDispatchChannel(null));
    }

    private WebSocketSessionStore sessionStoreWithWorker(String workerId) {
        WebSocketSessionStore sessionStore = new WebSocketSessionStore("websocket");
        sessionStore.bind("bucket-1", "route-1", workerId, mockActiveChannel(workerId));
        return sessionStore;
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }

    private DeliveryCommand request() {
        return new DeliveryCommand(
                "delivery-msg-1",
                "bucket-1",
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

