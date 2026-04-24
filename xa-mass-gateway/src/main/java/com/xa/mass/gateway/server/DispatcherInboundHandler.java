package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.queue.Envelope;
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
        this.messageParser = dispatcherContext.getMessageParser();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        try {
            if (raw == null || !raw.trim().startsWith("{")) {
                sendError(ctx, "INVALID_FORMAT", "Message must be a JSON object");
                return;
            }
            MassMessage massMessage = messageParser.tryDecode(raw);
            if (massMessage == null || !messageParser.isValid(massMessage)) {
                logger.error("Message parse/validate failed");
                sendError(ctx, "PARSE_FAILED", "Message missing context info");
                return;
            }
            if (massMessage.getContext() == null) {
                logger.error("Parsed context is null");
                sendError(ctx, "MISSING_CONTEXT", "Message context is null");
                return;
            }

            String workerId = massMessage.getContext().getWorkerId();
            String msgId = massMessage.getMsgId();
            if (workerId == null || msgId == null) {
                logger.error("workerId/msgId is null: workerId={}, msgId={}", workerId, msgId);
                sendError(ctx, "MISSING_FIELDS", "workerId/msgId are required");
                return;
            }
            Envelope envelope = messageParser.toStoredMessage(raw, massMessage);
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", envelope.getWorkerId());
            org.slf4j.MDC.put("connRole", envelope.getConnRole());
            if (envelope.getEventCode() != null) {
                org.slf4j.MDC.put("eventCode", envelope.getEventCode());
            }
            org.slf4j.MDC.put("traceId", envelope.getTraceId());
            org.slf4j.MDC.put("project", envelope.getProject());
            org.slf4j.MDC.put("receivedAt", String.valueOf(envelope.getReceivedAt()));
            try {
                logger.debug("channelRead0 envelope");
            } finally {
                org.slf4j.MDC.clear();
            }

            dispatcherContext.getMessageTransporter().sendInput(envelope);
            if (dispatcherContext.getSessionManager() instanceof ServerSessionManager sessionManager) {
                sessionManager.addSession(workerId, envelope.getConnRole(), ctx.channel(), ctx);
            }
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
