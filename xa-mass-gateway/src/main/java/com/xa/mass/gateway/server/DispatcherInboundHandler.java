package com.xa.mass.gateway.server;

import com.google.gson.JsonObject;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherInboundHandler.class);
    private final ServerSessionManager sessionManager;
    private final MessageCodec messageCodec;
    private final Consumer<String> inboundMessageSink;

    public DispatcherInboundHandler(MessageCodec messageCodec,
                                    Consumer<String> inboundMessageSink,
                                    ServerSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
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
            JsonObject frame = messageCodec.parseObject(raw);
            if (frame == null) {
                sendError(ctx, "PARSE_FAILED", "Message must be a valid JSON object");
                return;
            }

            String workerId = messageCodec.extractWorkerId(frame);
            String msgId = messageCodec.extractMessageId(frame);
            if (workerId == null || msgId == null) {
                sendError(ctx, "MISSING_FIELDS", "workerId/messageId are required");
                return;
            }
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", workerId);
            String eventCode = messageCodec.extractEventCode(frame);
            if (eventCode != null) {
                org.slf4j.MDC.put("eventCode", eventCode);
            }
            org.slf4j.MDC.put("traceId", msgId);
            String project = messageCodec.extractProject(frame);
            if (project != null) {
                org.slf4j.MDC.put("project", project);
            }
            try {
                logger.debug("channelRead0 raw frame");
            } finally {
                org.slf4j.MDC.clear();
            }

            sessionManager.addSession(workerId, ctx.channel(), ctx);
            inboundMessageSink.accept(raw);
        } catch (Exception e) {
            logger.error("Unexpected error in channelRead0", e);
            sendError(ctx, "INTERNAL_ERROR", "Internal server error");
        }
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
}
