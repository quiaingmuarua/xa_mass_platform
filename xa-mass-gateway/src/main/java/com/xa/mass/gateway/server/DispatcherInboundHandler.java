package com.xa.mass.gateway.server;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.enums.MessageType;
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

    public DispatcherInboundHandler(DispatchRuntimeContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        try {
            if (raw == null || !raw.trim().startsWith("{")) {
                sendError(ctx, "INVALID_FORMAT", "Message must be a JSON object");
                return;
            }
            MassMessage massMessage = MessageParser.INSTANCE.tryDecode(raw);
            if (massMessage == null || !MessageParser.INSTANCE.isValid(massMessage)) {
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
            String connRole = massMessage.getContext().getConnRole();
            String project = massMessage.getProject();
            String msgId = massMessage.getMsgId();
            if (workerId == null || connRole == null || msgId == null) {
                logger.error("workerId/connRole/msgId is null: workerId={}, connRole={}, msgId={}",
                        workerId, connRole, msgId);
                sendError(ctx, "MISSING_FIELDS", "workerId/connRole/msgId are required");
                return;
            }
            if (project == null && !allowsMissingProject(massMessage)) {
                logger.error("project is null for non-heartbeat message: workerId={}, connRole={}, msgId={}, msgType={}",
                        workerId, connRole, msgId, massMessage.getMsgType());
                sendError(ctx, "MISSING_FIELDS", "project is required for non-heartbeat messages");
                return;
            }

            Envelope envelope = Envelope.builder()
                    .rawJson(raw)
                    .workerId(workerId)
                    .connRole(connRole)
                    .project(project)
                    .receivedAt(System.currentTimeMillis())
                    .traceId(massMessage.getMsgId())
                    .build();
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", envelope.getWorkerId());
            org.slf4j.MDC.put("connRole", envelope.getConnRole());
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
                sessionManager.addSession(workerId, connRole, ctx.channel(), ctx);
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

    private boolean allowsMissingProject(MassMessage message) {
        return message.getMsgType() == MessageType.PING || message.getMsgType() == MessageType.PONG;
    }
}
