package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.queue.Envelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatcherInboundHandlerTest {

    private DispatcherInboundHandler handler;
    private ChannelHandlerContext ctx;
    private Channel channel;
    private AtomicReference<String> sentFrame;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        sentFrame = new AtomicReference<>();

        channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asShortText()).thenReturn("test-ch");
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);

        ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        // sendError() calls ctx.writeAndFlush(), not channel.writeAndFlush()
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof TextWebSocketFrame) {
                sentFrame.set(((TextWebSocketFrame) arg).text());
            }
            return null;
        }).when(ctx).writeAndFlush(any());

        MessageTransporter<Envelope> transporter = mock(MessageTransporter.class);
        when(transporter.inputQueueSize()).thenReturn(0);

        ServerSessionManager sessionManager = new ServerSessionManager();
        GsonMessageCodec codec = new GsonMessageCodec();
        MessageHandlerRegistry registry = new MessageHandlerRegistry();
        registry.autoRegister();

        DispatchRuntimeContext context = new DispatcherContext(transporter, sessionManager, codec);
        ((DispatcherContext) context).setMessageHandlerRegistry(registry);

        handler = new DispatcherInboundHandler(context);
    }

    @Test
    void nonJsonMessageSendsInvalidFormatError() throws Exception {
        handler.channelRead0(ctx, frame("not-json"));

        String sent = sentFrame.get();
        assertNotNull(sent, "Should have sent an error frame");
        assertTrue(sent.contains("INVALID_FORMAT"), "Error code should be INVALID_FORMAT, got: " + sent);
    }

    @Test
    void missingContextFieldsSendsMissingFieldsError() throws Exception {
        // Valid JSON but missing required fields (workerId etc.)
        handler.channelRead0(ctx, frame("{\"msgType\":\"PING\"}"));

        String sent = sentFrame.get();
        assertNotNull(sent, "Should have sent an error frame");
        assertTrue(sent.contains("PARSE_FAILED") || sent.contains("MISSING_FIELDS") || sent.contains("MISSING_CONTEXT"),
                "Expected a validation error, got: " + sent);
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

        // No error frame should be sent for a valid message
        assertNull(sentFrame.get(), "Valid message should not trigger an error frame");
    }

    @Test
    void exceptionCaughtSendsChannelError() {
        handler.exceptionCaught(ctx, new RuntimeException("test error"));

        String sent = sentFrame.get();
        assertNotNull(sent, "exceptionCaught should send an error frame");
        assertTrue(sent.contains("CHANNEL_ERROR"), "Error code should be CHANNEL_ERROR, got: " + sent);
    }

    @Test
    void exceptionCaughtDoesNotSendWhenChannelInactive() {
        when(channel.isActive()).thenReturn(false);
        handler.exceptionCaught(ctx, new RuntimeException("test"));

        assertNull(sentFrame.get(), "Should not send to inactive channel");
    }

    // ---- helper ----

    private TextWebSocketFrame frame(String text) {
        return new TextWebSocketFrame(text);
    }
}

class WebSocketServerImplDisconnectTest {

    @Test
    void channelInactiveRemovesDisconnectedSessionFromSessionManager() throws Exception {
        WebSocketServerImpl server = new WebSocketServerImpl();
        ServerSessionManager sessionManager = spy(new ServerSessionManager());
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
