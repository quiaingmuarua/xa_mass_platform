package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.core.getway.exception.ValidationException;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageParser;
import com.xa.mass.core.model.message.MassMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final DispatchRuntimeContext dispatcherContext;
    private static final Logger logger = LoggerFactory.getLogger(DispatcherInboundHandler.class);
    public DispatcherInboundHandler(DispatchRuntimeContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        if (raw == null || !raw.trim().startsWith("{")) {
            throw new ValidationException("Dropped invalid message: " + raw);
        }
        MassMessage parsed = MessageParser.INSTANCE.tryDecode(raw);
        if (parsed == null || !MessageParser.INSTANCE.isValid(parsed)) {
            logger.error("Message parse/validate failed. Raw: {}", raw);
            throw new ValidationException("Message missing context info: " + raw);
        }
        if (parsed.getContext() == null) {
            logger.error("Parsed context is null. Raw: {}", raw);
            throw new ValidationException("Message context is null.");
        }

        String deviceId = parsed.getContext().getDeviceId();
        String connRole = parsed.getContext().getConnRole();
        String project = parsed.getProject();
        String msgId = parsed.getMsgId();
        if (deviceId == null || connRole == null || project == null || msgId == null) {
            logger.error("deviceId/connRole/project/msgId is null: deviceId={}, connRole={}, project={}, msgId={}, raw={}",
                    deviceId, connRole, project, msgId, raw);
            throw new ValidationException("message 基本字段不全");
        }
        try {
            Envelope envelope = Envelope.builder()
                    .rawJson(raw)
                    .deviceId(deviceId)
                    .connRole(connRole)
                    .project(project)
                    .receivedAt(1111L)
                    .traceId("111")
                    .build();
            logger.info(" channelRead0 envelope = {}", envelope);

            dispatcherContext.getMessageTransporter().sendInput(envelope);
            dispatcherContext.getSessionManager().addSession(deviceId, connRole, ctx.channel(), ctx);
            logger.info("queue size {}", dispatcherContext.getMessageTransporter().inputQueueSize());
        } catch (Exception e) {
          logger.error("Error in channelRead0", e);
        }

    }
} 