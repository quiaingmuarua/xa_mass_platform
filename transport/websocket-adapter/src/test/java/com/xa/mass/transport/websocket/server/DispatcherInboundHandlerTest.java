package com.xa.mass.transport.websocket.server;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.transport.websocket.queue.OutboundDelivery;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        WebSocketTransportFrameCodec codec = new WebSocketTransportFrameCodec();
        handler = new DispatcherInboundHandler(codec, transporter::sendInput, sessionManager);
    }

    @Test
    void nonJsonMessageSendsInvalidFormatError() throws Exception {
        handler.channelRead0(ctx, frame("not-json"));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("INVALID_FORMAT"));
    }

    @Test
    void missingWorkerIdOrMessageIdSendsMissingFieldsError() throws Exception {
        handler.channelRead0(ctx, frame("{\"eventCode\":\"mock.state.get\"}"));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("MISSING_FIELDS"));
    }

    @Test
    void handshakeWithWorkerIdRegistersSessionBeforeTextFramesArrive() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1",
                new DefaultHttpHeaders(),
                null
        ));

        assertEquals(1, sessionManager.getWorkerConnectionCount());
        assertNotNull(sessionManager.getChannelContext("worker-1"));
    }

    @Test
    void messageWithoutInlineWorkerIdUsesHandshakeRegisteredWorkerId() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1",
                new DefaultHttpHeaders(),
                null
        ));
        String controlJson = """
                {
                  "messageId": "ctrl-001",
                  "response": false,
                  "project": "demoApp",
                  "eventCode": "mock.state.get",
                  "requestId": "req-1",
                  "payload": {
                    "verbose": true
                  }
                }
                """;
        handler.channelRead0(ctx, frame(controlJson));

        assertNull(sentFrame.get());
        verify(transporter).sendInput(controlJson);
        assertEquals(1, sessionManager.getWorkerConnectionCount());
        assertNotNull(sessionManager.getChannelContext("worker-1"));
    }

    @Test
    void eventFirstControlFrameWithoutMsgTypeStillEnqueuesRawJson() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1",
                new DefaultHttpHeaders(),
                null
        ));
        String controlJson = """
                {
                  "messageId": "ctrl-001",
                  "response": false,
                  "project": "demoApp",
                  "eventCode": "mock.state.get",
                  "requestId": "req-1",
                  "payload": {
                    "verbose": true
                  }
                }
                """;

        handler.channelRead0(ctx, frame(controlJson));

        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);
        verify(transporter).sendInput(rawCaptor.capture());
        assertTrue(rawCaptor.getValue().contains("\"eventCode\": \"mock.state.get\"")
                || rawCaptor.getValue().contains("\"eventCode\":\"mock.state.get\""));
        assertNotNull(sessionManager.getChannelContext("worker-1"));
    }

    @Test
    void malformedJsonObjectSendsParseFailedError() throws Exception {
        handler.channelRead0(ctx, frame("{\"messageId\":\"broken\""));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("PARSE_FAILED"));
    }

    @Test
    void missingMessageIdSendsMissingFieldsError() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1",
                new DefaultHttpHeaders(),
                null
        ));
        String controlJson = """
                {
                  "eventCode": "mock.state.get"
                }
                """;

        handler.channelRead0(ctx, frame(controlJson));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("MISSING_FIELDS"));
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
        ServerSessionManager sessionManager = org.mockito.Mockito.spy(new ServerSessionManager());
        WebSocketServerImpl server = new WebSocketServerImpl(
                "/ws",
                new WebSocketTransportFrameCodec(),
                raw -> { },
                sessionManager
        );

        Channel channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asShortText()).thenReturn("disconnect-ch");
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.fireChannelActive()).thenReturn(ctx);
        when(ctx.fireChannelInactive()).thenReturn(ctx);

        sessionManager.addSession("worker-1", channel, ctx);

        ChannelInboundHandlerAdapter handler = newConnectionStatsHandler(server);
        handler.channelActive(ctx);
        assertEquals(1L, server.getActiveConnectionCount());
        assertEquals(1, sessionManager.getWorkerConnectionCount());

        handler.channelInactive(ctx);

        verify(sessionManager).removeSession(channel);
        assertEquals(0L, server.getActiveConnectionCount());
        assertEquals(0, sessionManager.getWorkerConnectionCount());
        assertNull(sessionManager.getChannel("worker-1"));
        assertNull(sessionManager.getWorkerId(channel));
    }

    @Test
    void startFailsFastWhenRequiredWiringIsMissing() {
        WebSocketServerImpl server = new WebSocketServerImpl(null, null, null, null);

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
