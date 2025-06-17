package com.xa.mass.core.server;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.core.session.ServerSessionManager;
import com.xa.mass.core.model.message.BaseMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.queue.StoredMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class ServerMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(ServerMessageHandler.class);
    private final Gson gson = new Gson();

    @Autowired
    private ServerSessionManager sessionManager;

    @Autowired
    @Qualifier("inputQueue")
    private MessageQueue<StoredMessage> inputQueue; // 泛型改为 StoredMessage

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String message = msgFrame.text();
        if (message == null || message.trim().isEmpty()) {
            logger.warn("Received empty message from {}", ctx.channel().remoteAddress());
            return;
        }

        if (!message.trim().startsWith("{")) {
            logger.warn("Invalid JSON format from {}: {}", ctx.channel().remoteAddress(), message);
            return;
        }

        try {
            BaseMessage<?> preParseForContext = gson.fromJson(message, BaseMessage.class);
            if (preParseForContext == null) {
                logger.warn("Failed to parse message to BaseMessage from {}: {}", ctx.channel().remoteAddress(), message);
                return;
            }

            MessageContext context = preParseForContext.getContext();
            if (context == null || context.getDeviceId() == null || context.getConnRole() == null) {
                logger.warn("Message from {} is missing deviceId or connRole in context: {}", ctx.channel().remoteAddress(), message);
                return;
            }

            // 仍然需要将 Channel Context 存入 SessionManager
            sessionManager.addSession(context.getDeviceId(), context.getConnRole(), ctx.channel(), ctx);

            // 创建 StoredMessage 并放入队
            StoredMessage storedMessage = new StoredMessage(message, context.getDeviceId(), context.getConnRole());
            inputQueue.offer(storedMessage);
            logger.debug("Offered to inputQueue: {}", storedMessage);

        } catch (JsonSyntaxException e) {
            logger.error("JSON syntax error processing message from {}: {}", ctx.channel().remoteAddress(), message, e);
        } catch (Exception e) {
            logger.error("Unexpected error in channelRead0 from {}: {}", ctx.channel().remoteAddress(), message, e);
        }
    }

    // handlerRemoved, channelActive, exceptionCaught 保持不变
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        sessionManager.removeSession(ctx.channel());
        logger.info("WebSocket connection closed, session removed: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.info("New WebSocket connection active: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket handler error for channel {}:", ctx.channel().remoteAddress(), cause);
        sessionManager.removeSession(ctx.channel());
        ctx.close();
    }
}
