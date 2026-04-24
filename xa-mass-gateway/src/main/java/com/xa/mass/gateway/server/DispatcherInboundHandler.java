package com.xa.mass.gateway.server;

import com.google.gson.JsonObject;
import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherInboundHandler.class);
    private final ServerSessionManager sessionManager;
    private final WebSocketTransportFrameCodec frameCodec;
    private final Consumer<String> inboundMessageSink;

    public DispatcherInboundHandler(WebSocketTransportFrameCodec frameCodec,
                                    Consumer<String> inboundMessageSink,
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

            String workerId = firstNonBlank(
                    frameCodec.extractWorkerId(frame),
                    sessionManager.getWorkerId(ctx.channel())
            );
            String msgId = frameCodec.extractMessageId(frame);
            if (workerId == null || msgId == null) {
                sendError(ctx, "MISSING_FIELDS", "workerId/messageId are required");
                return;
            }
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", workerId);
            String eventCode = frameCodec.extractEventCode(frame);
            if (eventCode != null) {
                org.slf4j.MDC.put("eventCode", eventCode);
            }
            org.slf4j.MDC.put("traceId", msgId);
            String project = frameCodec.extractProject(frame);
            if (project != null) {
                org.slf4j.MDC.put("project", project);
            }
            try {
                logger.debug("channelRead0 raw frame");
            } finally {
                org.slf4j.MDC.clear();
            }
            registerSessionIfNeeded(workerId, ctx);
            inboundMessageSink.accept(raw);
        } catch (Exception e) {
            logger.error("Unexpected error in channelRead0", e);
            sendError(ctx, "INTERNAL_ERROR", "Internal server error");
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshakeComplete) {
            String workerId = extractWorkerIdFromRequestUri(handshakeComplete.requestUri());
            if (workerId == null) {
                logger.warn("WebSocket handshake completed without workerId query parameter");
            } else {
                registerSessionIfNeeded(workerId, ctx);
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
            String errorJson = new com.google.gson.Gson().toJson(
                    Map.of("code", code, "message", message, "type", "ERROR"));
            ctx.writeAndFlush(new TextWebSocketFrame(errorJson));
        }
    }

    private void registerSessionIfNeeded(String workerId, ChannelHandlerContext ctx) {
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        String existingWorkerId = sessionManager.getWorkerId(ctx.channel());
        if (workerId.equals(existingWorkerId) && sessionManager.getChannelContext(workerId) != null) {
            return;
        }
        sessionManager.addSession(workerId, ctx.channel(), ctx);
    }

    private String extractWorkerIdFromRequestUri(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }
        QueryStringDecoder decoder = new QueryStringDecoder(requestUri);
        return firstQueryValue(decoder, "workerId");
    }

    private String firstQueryValue(QueryStringDecoder decoder, String key) {
        if (decoder == null || key == null) {
            return null;
        }
        var values = decoder.parameters().get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return firstNonBlank(values.get(0));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
