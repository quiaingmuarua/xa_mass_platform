package com.xa.mass.transport.websocket.server;

import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.websocket.frame.WebSocketSessionOpenFrameReader;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.Attribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatcherInboundHandlerTest {

    private DispatcherInboundHandler handler;
    private ChannelHandlerContext ctx;
    private Channel channel;
    private AtomicReference<String> sentFrame;
    private Consumer<JsonObject> inboundFrameSink;
    private AtomicReference<JsonObject> acceptedFrame;
    private WebSocketSessionRegistry sessionRegistry;
    private TransportJsonFrameParser frameParser;
    private WebSocketSessionOpenFrameReader sessionOpenFrameReader;

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

        acceptedFrame = new AtomicReference<>();
        inboundFrameSink = acceptedFrame::set;
        sessionRegistry = newSessionRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID);
        frameParser = new TransportJsonFrameParser();
        sessionOpenFrameReader = new WebSocketSessionOpenFrameReader();
        handler = new DispatcherInboundHandler(frameParser, sessionOpenFrameReader, inboundFrameSink, sessionRegistry);
    }

    @Test
    void nonJsonMessageSendsInvalidFormatError() throws Exception {
        handler.channelRead0(ctx, frame("not-json"));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("INVALID_FORMAT"));
    }

    @Test
    void unboundSessionSendsSessionNotBoundErrorAndDoesNotRegisterFromFrameFields() throws Exception {
        handler.channelRead0(ctx, frame("""
                {
                  "workerId": "worker-1",
                  "workerGroupId": "bucket-1",
                  "routeKey": "ws-route-1",
                  "eventCode": "mock.state.get"
                }
                """));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("SESSION_NOT_BOUND"));
        assertEquals(0, sessionRegistry.activeConnectionCount());
        assertNull(acceptedFrame.get());
    }

    @Test
    void handshakeWithWorkerIdAndRouteKeyRegistersWorkerSessionBeforeTextFramesArrive() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1",
                new DefaultHttpHeaders(),
                null
        ));

        assertEquals(1, sessionRegistry.activeConnectionCount());
        assertEquals("worker-1", sessionRegistry.currentWorkerId(channel));
    }

    @Test
    void handshakeWithoutRouteKeyRegistersWorkerSession() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1",
                new DefaultHttpHeaders(),
                null
        ));

        assertEquals(1, sessionRegistry.activeConnectionCount());
        assertEquals("worker-1", sessionRegistry.currentWorkerId(channel));
    }

    @Test
    void messageWithoutInlineWorkerIdUsesHandshakeRegisteredWorkerId() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1",
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
        assertNotNull(acceptedFrame.get());
        assertEquals("mock.state.get", frameParser.readString(acceptedFrame.get(), "eventCode"));
        assertEquals(1, sessionRegistry.activeConnectionCount());
    }

    @Test
    void messageWithoutInlineRouteKeyStillUsesBoundWorkerId() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-11",
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
        assertNotNull(acceptedFrame.get());
        assertEquals("worker-1", sessionRegistry.currentWorkerId(channel));
    }

    @Test
    void handshakeRouteKeyDoesNotOverrideWorkerSessionLookup() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-9",
                new DefaultHttpHeaders(),
                null
        ));

        assertEquals(1, sessionRegistry.activeConnectionCount());
        assertEquals("worker-1", sessionRegistry.currentWorkerId(channel));
    }

    @Test
    void eventFirstControlFrameWithoutMsgTypeStillPassesParsedFrame() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1",
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

        JsonObject accepted = acceptedFrame.get();
        assertNotNull(accepted);
        assertEquals("mock.state.get", frameParser.readString(accepted, "eventCode"));
        assertEquals("worker-1", sessionRegistry.currentWorkerId(channel));
    }

    @Test
    void malformedJsonObjectSendsParseFailedError() throws Exception {
        handler.channelRead0(ctx, frame("{\"messageId\":\"broken\""));

        String sent = sentFrame.get();
        assertNotNull(sent);
        assertTrue(sent.contains("PARSE_FAILED"));
    }

    @Test
    void controlFrameWithoutMessageIdStillPassesParsedFrame() throws Exception {
        handler.userEventTriggered(ctx, new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws?workerId=worker-1&workerGroupId=bucket-1&routeKey=ws-route-1",
                new DefaultHttpHeaders(),
                null
        ));
        String controlJson = """
                {
                  "eventCode": "mock.state.get"
                }
                """;

        handler.channelRead0(ctx, frame(controlJson));

        assertNull(sentFrame.get());
        assertNotNull(acceptedFrame.get());
        assertEquals("mock.state.get", frameParser.readString(acceptedFrame.get(), "eventCode"));
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

    private WebSocketSessionRegistry newSessionRegistry(String adapterId) {
        AdapterSessionEvidencePublisher sessionEvidencePublisher =
                AdapterSessionEvidencePublisher.noop(adapterId, adapterId);
        return new WebSocketSessionRegistry(sessionEvidencePublisher);
    }
}

class WebSocketServerImplDisconnectTest {

    private WebSocketSessionRegistry sessionRegistry;

    @Test
    void channelInactiveRemovesDisconnectedSessionFromSessionManager() throws Exception {
        WebSocketSessionRegistry sessionRegistry = newSessionRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID);
        WebSocketServerImpl server = new WebSocketServerImpl(
                18088,
                10,
                "/ws",
                new TransportJsonFrameParser(),
                new WebSocketSessionOpenFrameReader(),
                raw -> { },
                sessionRegistry
        );

        Channel channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        Attribute<Boolean> countedAttribute = mockBooleanAttribute();
        when(channelId.asShortText()).thenReturn("disconnect-ch");
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);
        when(channel.attr(any())).thenAnswer(invocation -> countedAttribute);

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.fireChannelActive()).thenReturn(ctx);
        when(ctx.fireChannelInactive()).thenReturn(ctx);

        sessionRegistry.addSession("bucket-1", "worker-1", channel);

        ChannelInboundHandlerAdapter handler = newConnectionStatsHandler(server);
        handler.channelActive(ctx);
        assertEquals(1L, server.getActiveConnectionCount());
        assertEquals(1, sessionRegistry.activeConnectionCount());

        handler.channelInactive(ctx);

        assertEquals(0L, server.getActiveConnectionCount());
        assertEquals(0, sessionRegistry.activeConnectionCount());
        assertNull(sessionRegistry.currentWorkerId(channel));
    }

    @Test
    void startFailsFastWhenRequiredWiringIsMissing() {
        WebSocketServerImpl server = new WebSocketServerImpl(-1, 0, null, null, null, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class, server::start);

        assertTrue(error.getMessage().contains("non-negative port"));
    }

    @Test
    void channelActiveRejectsConnectionsBeyondConfiguredMax() throws Exception {
        WebSocketSessionRegistry sessionRegistry = newSessionRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID);
        WebSocketServerImpl server = new WebSocketServerImpl(
                18088,
                1,
                "/ws",
                new TransportJsonFrameParser(),
                new WebSocketSessionOpenFrameReader(),
                raw -> { },
                sessionRegistry
        );

        Channel countedChannel = mock(Channel.class);
        ChannelId countedChannelId = mock(ChannelId.class);
        Attribute<Boolean> countedAttribute = mockBooleanAttribute();
        when(countedChannel.id()).thenReturn(countedChannelId);
        when(countedChannelId.asShortText()).thenReturn("counted-ch");
        when(countedChannel.attr(any())).thenAnswer(invocation -> countedAttribute);

        ChannelHandlerContext countedCtx = mock(ChannelHandlerContext.class);
        when(countedCtx.channel()).thenReturn(countedChannel);
        when(countedCtx.fireChannelActive()).thenReturn(countedCtx);

        Channel rejectedChannel = mock(Channel.class);
        ChannelId rejectedChannelId = mock(ChannelId.class);
        Attribute<Boolean> rejectedAttribute = mockBooleanAttribute();
        when(rejectedChannel.id()).thenReturn(rejectedChannelId);
        when(rejectedChannelId.asShortText()).thenReturn("rejected-ch");
        when(rejectedChannel.attr(any())).thenAnswer(invocation -> rejectedAttribute);

        ChannelHandlerContext rejectedCtx = mock(ChannelHandlerContext.class);
        when(rejectedCtx.channel()).thenReturn(rejectedChannel);

        ChannelInboundHandlerAdapter handler = newConnectionStatsHandler(server);
        handler.channelActive(countedCtx);
        handler.channelActive(rejectedCtx);

        assertEquals(1L, server.getActiveConnectionCount());
        verify(rejectedCtx).close();
        assertEquals(0, sessionRegistry.activeConnectionCount());
    }

    private WebSocketSessionRegistry newSessionRegistry(String adapterId) {
        AdapterSessionEvidencePublisher sessionEvidencePublisher =
                AdapterSessionEvidencePublisher.noop(adapterId, adapterId);
        this.sessionRegistry = new WebSocketSessionRegistry(sessionEvidencePublisher);
        return this.sessionRegistry;
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

    @SuppressWarnings("unchecked")
    private Attribute<Boolean> mockBooleanAttribute() {
        Attribute<Boolean> attribute = mock(Attribute.class);
        AtomicReference<Boolean> value = new AtomicReference<>();
        doAnswer(invocation -> {
            value.set(invocation.getArgument(0));
            return null;
        }).when(attribute).set(any());
        when(attribute.getAndSet(any())).thenAnswer(invocation -> value.getAndSet(invocation.getArgument(0)));
        when(attribute.get()).thenAnswer(invocation -> value.get());
        return attribute;
    }
}
