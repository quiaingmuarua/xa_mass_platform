package com.xa.mass.gateway.server;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.GatewayFrameRouter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.gateway.session.SessionRoles;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatcherInboundHandlerTest {

    private DispatcherInboundHandler handler;
    private ChannelHandlerContext ctx;
    private Channel channel;
    private AtomicReference<String> sentFrame;
    private MessageTransporter<String, OutboundDelivery> transporter;
    private ServerSessionManager sessionManager;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        sentFrame = new AtomicReference<>();

        channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asShortText()).thenReturn("test-ch");
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);

        ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof TextWebSocketFrame frame) {
                sentFrame.set(frame.text());
            }
            return null;
        }).when(ctx).writeAndFlush(any());

        transporter = mock(MessageTransporter.class);
        when(transporter.inputQueueSize()).thenReturn(0);

        sessionManager = new ServerSessionManager();
        GsonMessageCodec codec = new GsonMessageCodec();
        GatewayFrameRouter frameRouter = new GatewayFrameRouter(codec);

        DispatchRuntimeContext context = new DispatcherContext(
                transporter,
                sessionManager,
                codec,
                frameRouter,
                null,
                null,
                null
        );

        handler = new DispatcherInboundHandler(context, sessionManager);
    }

    @Test
    void nonJsonMessageSendsInvalidFormatError() throws Exception {
        handler.channelRead0(ctx, frame("not-json"));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("INVALID_FORMAT"));
    }

    @Test
    void missingContextFieldsSendsParseFailedError() throws Exception {
        handler.channelRead0(ctx, frame("{\"msgType\":\"PING\"}"));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("PARSE_FAILED") || sent.contains("MISSING_FIELDS") || sent.contains("MISSING_CONTEXT"));
    }

    @Test
    void validMessageIsEnqueuedWithoutError() throws Exception {
        String validJson = """
                {
                  "msgId": "msg-001",
                  "msgType": "PING",
                  "subMsgType": "heartbeat",
                  "project": "demoApp",
                  "context": {
                    "workerId": "worker-1",
                    "connRole": "%s"
                  }
                }
                """.formatted(SessionRoles.TASK_MESSAGES);
        handler.channelRead0(ctx, frame(validJson));

        assertNull(sentFrame.get());
        verify(transporter).sendInput(validJson);
    }

    @Test
    void heartbeatWithoutProjectStillRegistersSession() throws Exception {
        String heartbeatJson = """
                {
                  "msgId": "ping-001",
                  "msgType": "PING",
                  "subMsgType": "heartbeat",
                  "context": {
                    "workerId": "worker-1",
                    "connRole": "%s"
                  }
                }
                """.formatted(SessionRoles.TASK_MESSAGES);

        handler.channelRead0(ctx, frame(heartbeatJson));

        assertNull(sentFrame.get());
        verify(transporter).sendInput(heartbeatJson);
        assertEquals(1, sessionManager.getWorkerConnectionCount());
        assertNotNull(sessionManager.getChannelContext("worker-1", SessionRoles.TASK_MESSAGES));
    }

    @Test
    void missingConnRoleDefaultsToTaskDispatchLane() throws Exception {
        String heartbeatJson = """
                {
                  "msgId": "ping-002",
                  "msgType": "PING",
                  "subMsgType": "heartbeat",
                  "context": {
                    "workerId": "worker-2"
                  }
                }
                """;

        handler.channelRead0(ctx, frame(heartbeatJson));

        assertNull(sentFrame.get());
        verify(transporter).sendInput(heartbeatJson);
        assertNotNull(sessionManager.getChannelContext("worker-2", SessionRoles.TASK_MESSAGES));
    }

    @Test
    void taskStepWithoutProjectStillEnqueuesRawJsonForDownstreamValidation() throws Exception {
        String taskJson = """
                {
                  "msgId": "task-001",
                  "msgType": "TASK",
                  "subMsgType": "step",
                  "context": {
                    "workerId": "worker-1",
                    "connRole": "%s",
                    "taskId": "task-123"
                  },
                  "payload": {
                    "status": "SUCCESS"
                  }
                }
                """.formatted(SessionRoles.TASK_MESSAGES);

        handler.channelRead0(ctx, frame(taskJson));

        assertNull(sentFrame.get());
        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(transporter).sendInput(rawCaptor.capture());
        assertTrue(rawCaptor.getValue().contains("\"msgId\": \"task-001\"") || rawCaptor.getValue().contains("\"msgId\":\"task-001\""));
    }

    @Test
    void controlEventInboundEnqueuesRawJsonWithExplicitEventField() throws Exception {
        String controlJson = """
                {
                  "msgId": "ctrl-001",
                  "msgType": "CONTROL",
                  "subMsgType": "event",
                  "project": "demoApp",
                  "context": {
                    "workerId": "worker-1",
                    "connRole": "%s"
                  },
                  "payload": {
                    "event": "mock.state.get",
                    "requestId": "req-1",
                    "payload": {
                      "verbose": true
                    }
                  }
                }
                """.formatted(SessionRoles.TASK_MESSAGES);

        handler.channelRead0(ctx, frame(controlJson));

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(transporter).sendInput(rawCaptor.capture());
        assertTrue(rawCaptor.getValue().contains("\"event\": \"mock.state.get\"")
                || rawCaptor.getValue().contains("\"event\":\"mock.state.get\""));
    }

    @Test
    void exceptionCaughtSendsChannelError() {
        handler.exceptionCaught(ctx, new RuntimeException("test error"));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("CHANNEL_ERROR"));
    }

    @Test
    void exceptionCaughtDoesNotSendWhenChannelInactive() {
        when(channel.isActive()).thenReturn(false);
        handler.exceptionCaught(ctx, new RuntimeException("test"));

        assertNull(sentFrame.get());
    }

    private TextWebSocketFrame frame(String text) {
        return new TextWebSocketFrame(text);
    }
}

class WebSocketServerImplDisconnectTest {

    @Test
    void channelInactiveRemovesDisconnectedSessionFromSessionManager() throws Exception {
        WebSocketServerImpl server = new WebSocketServerImpl();
        ServerSessionManager sessionManager = org.mockito.Mockito.spy(new ServerSessionManager());
        server.setSessionManager(sessionManager);

        Channel channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asShortText()).thenReturn("disconnect-ch");
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.fireChannelActive()).thenReturn(ctx);
        when(ctx.fireChannelInactive()).thenReturn(ctx);

        sessionManager.addSession("worker-1", SessionRoles.TASK_MESSAGES, channel, ctx);

        ChannelInboundHandlerAdapter handler = newConnectionStatsHandler(server);
        handler.channelActive(ctx);
        assertEquals(1L, server.getActiveConnectionCount());
        assertEquals(1, sessionManager.getWorkerConnectionCount());

        handler.channelInactive(ctx);

        verify(sessionManager).removeSession(channel);
        assertEquals(0L, server.getActiveConnectionCount());
        assertTrue(sessionManager.getAllWorkerChannels().isEmpty());
        assertNull(sessionManager.getChannel("worker-1", SessionRoles.TASK_MESSAGES));
        assertNull(sessionManager.getWorkerConnKey(channel));
    }

    @Test
    void startFailsFastWhenRequiredWiringIsMissing() {
        WebSocketServerImpl server = new WebSocketServerImpl();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> server.start(18088));

        assertTrue(error.getMessage().contains("websocketPath"));
    }

    private ChannelInboundHandlerAdapter newConnectionStatsHandler(WebSocketServerImpl server) throws Exception {
        Class<?> handlerClass = Arrays.stream(WebSocketServerImpl.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals("ConnectionStatsHandler"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = handlerClass.getDeclaredConstructor(WebSocketServerImpl.class);
        constructor.setAccessible(true);
        return (ChannelInboundHandlerAdapter) constructor.newInstance(server);
    }
}
