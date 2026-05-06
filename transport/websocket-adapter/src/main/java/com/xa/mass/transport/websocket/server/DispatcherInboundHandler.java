package com.xa.mass.transport.websocket.server;

import com.google.gson.JsonObject;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessage;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessageSink;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherInboundHandler.class);
    private final ServerSessionManager sessionManager;
    private final WebSocketTransportFrameCodec frameCodec;
    private final WebSocketInboundMessageSink inboundMessageSink;

    public DispatcherInboundHandler(WebSocketTransportFrameCodec frameCodec,
                                    WebSocketInboundMessageSink inboundMessageSink,
                                    ServerSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.inboundMessageSink = Objects.requireNonNull(inboundMessageSink, "inboundMessageSink");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        try {
            if (raw == null || !raw.trim().startsWith("{")) {
                sendError(ctx, "INVALID_FORMAT", "Message must be a JSON object");
                return;
            }
            JsonObject frame = frameCodec.parseObject(raw);
            if (frame == null) {
                sendError(ctx, "PARSE_FAILED", "Message must be a valid JSON object");
                return;
            }

            String workerId = resolveWorkerId(frame, ctx);
            String routeKey = resolveRouteKey(frame, workerId, ctx);
            String messageId = frameCodec.extractMessageId(frame);
            if (workerId == null || routeKey == null || messageId == null) {
                sendError(ctx, "MISSING_FIELDS", "workerId/routeKey/messageId are required");
                return;
            }
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", workerId);
            String eventCode = frameCodec.extractEventCode(frame);
            if (eventCode != null) {
                org.slf4j.MDC.put("eventCode", eventCode);
            }
            org.slf4j.MDC.put("traceId", messageId);
            String project = frameCodec.extractProject(frame);
            if (project != null) {
                org.slf4j.MDC.put("project", project);
            }
            try {
                logger.debug("channelRead0 raw frame");
            } finally {
                org.slf4j.MDC.clear();
            }
            registerSessionIfNeeded(routeKey, workerId, ctx);
            inboundMessageSink.accept(WebSocketInboundMessage.of(
                    raw,
                    workerId,
                    ctx.channel().id().asShortText(),
                    frame
            ));
        } catch (Exception e) {
            logger.error("Unexpected error in channelRead0", e);
            sendError(ctx, "INTERNAL_ERROR", "Internal server error");
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshakeComplete) {
            String workerId = extractWorkerIdFromRequestUri(handshakeComplete.requestUri());
            String routeKey = extractRouteKeyFromRequestUri(handshakeComplete.requestUri());
            if (workerId == null) {
                logger.warn("WebSocket handshake completed without workerId query parameter");
            } else {
                registerSessionIfNeeded(
                        WebSocketStringValues.firstNonBlank(routeKey, workerId),
                        workerId,
                        ctx
                );
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel exception: {}", cause.getMessage(), cause);
        if (ctx.channel().isActive()) {
            sendError(ctx, "CHANNEL_ERROR", cause.getMessage());
        }
    }

    private void sendError(ChannelHandlerContext ctx, String code, String message) {
        if (ctx.channel().isActive()) {
            String errorJson = frameCodec.getGson().toJson(
                    Map.of("code", code, "message", message, "type", "ERROR"));
            ctx.writeAndFlush(new TextWebSocketFrame(errorJson));
        }
    }

    private void registerSessionIfNeeded(String routeKey, String workerId, ChannelHandlerContext ctx) {
        if (routeKey == null || routeKey.isBlank() || workerId == null || workerId.isBlank()) {
            return;
        }
        String existingWorkerId = sessionManager.getWorkerId(ctx.channel());
        String existingRouteKey = sessionManager.getRouteKey(ctx.channel());
        if (workerId.equals(existingWorkerId)
                && routeKey.equals(existingRouteKey)
                && sessionManager.getChannelContext(routeKey) != null) {
            return;
        }
        sessionManager.addSession(routeKey, workerId, ctx.channel(), ctx);
    }

    private String extractWorkerIdFromRequestUri(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }
        QueryStringDecoder decoder = new QueryStringDecoder(requestUri);
        return firstQueryValue(decoder, "workerId");
    }

    private String extractRouteKeyFromRequestUri(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }
        QueryStringDecoder decoder = new QueryStringDecoder(requestUri);
        return firstQueryValue(decoder, "routeKey");
    }

    private String resolveWorkerId(JsonObject frame, ChannelHandlerContext ctx) {
        return WebSocketStringValues.firstNonBlank(
                frameCodec.extractWorkerId(frame),
                sessionManager.getWorkerId(ctx.channel())
        );
    }

    private String resolveRouteKey(JsonObject frame, String workerId, ChannelHandlerContext ctx) {
        return WebSocketStringValues.firstNonBlank(
                frameCodec.extractRouteKey(frame),
                sessionManager.getRouteKey(ctx.channel()),
                workerId
        );
    }

    private String firstQueryValue(QueryStringDecoder decoder, String key) {
        if (decoder == null || key == null) {
            return null;
        }
        var values = decoder.parameters().get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return WebSocketStringValues.firstNonBlank(values.get(0));
    }
}
