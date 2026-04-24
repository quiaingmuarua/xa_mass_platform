package com.xa.mass.gateway.server;

import com.google.gson.JsonObject;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.MessageParser;
import com.xa.mass.gateway.session.ServerSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherInboundHandler.class);
    private final DispatchRuntimeContext dispatcherContext;
    private final MessageParser messageParser;

    public DispatcherInboundHandler(DispatchRuntimeContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
        this.messageParser = new MessageParser(dispatcherContext.getMessageCodec());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        try {
            if (raw == null || !raw.trim().startsWith("{")) {
                sendError(ctx, "INVALID_FORMAT", "Message must be a JSON object");
                return;
            }
            JsonObject frame = messageParser.tryDecode(raw);
            if (frame == null || !messageParser.isValid(frame)) {
                sendError(ctx, "PARSE_FAILED", "Message missing context info");
                return;
            }

            String workerId = messageParser.extractWorkerId(frame);
            String msgId = messageParser.extractMessageId(frame);
            String connRole = messageParser.extractConnRole(frame);
            if (workerId == null || msgId == null) {
                sendError(ctx, "MISSING_FIELDS", "workerId/msgId are required");
                return;
            }
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", workerId);
            org.slf4j.MDC.put("connRole", connRole);
            String eventCode = messageParser.extractEventCode(frame);
            if (eventCode != null) {
                org.slf4j.MDC.put("eventCode", eventCode);
            }
            org.slf4j.MDC.put("traceId", msgId);
            org.slf4j.MDC.put("project", messageParser.extractProject(frame));
            try {
                logger.debug("channelRead0 raw frame");
            } finally {
                org.slf4j.MDC.clear();
            }

            if (dispatcherContext.getSessionManager() instanceof ServerSessionManager sessionManager) {
                sessionManager.addSession(workerId, connRole, ctx.channel(), ctx);
            }
            dispatcherContext.getMessageTransporter().sendInput(raw);
            logger.debug("Input queue size={}", dispatcherContext.getMessageTransporter().inputQueueSize());
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
