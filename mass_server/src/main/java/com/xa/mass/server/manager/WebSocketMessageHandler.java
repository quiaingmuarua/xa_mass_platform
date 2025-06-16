package com.xa.mass.server.manager;

import com.google.gson.Gson;
import com.xa.mass.server.queue.MessageQueue;
import com.xa.mass.server.queue.WebSocketMessage;
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
public class WebSocketMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageHandler.class);
    private final Gson gson = new Gson();

    @Autowired
    private WebSocketSessionManager sessionManager;

    @Autowired
    @Qualifier("inputQueue") // 确保注入的是名为 inputQueue 的bean
    private MessageQueue<WebSocketMessage> inputQueue;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String message = msgFrame.text();

        // 1. 首先验证消息不为空
        if (message == null || message.trim().isEmpty()) {
            logger.warn("Received empty message");
            return;
        }

        // 2. 验证消息是否为合法的 JSON 格式
        if (!message.trim().startsWith("{")) {
            logger.warn("Invalid JSON format - message must be a JSON object: {}", message);
            return;
        }

        // 3. 将消息放入输入队列
        inputQueue.offer(new WebSocketMessage(message, ctx, msgFrame));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // 当连接断开时，从 sessionManager 中移除会话
        sessionManager.removeSession(ctx.channel());
        logger.info("WebSocket connection closed, session removed: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接建立时调用
        logger.info("New WebSocket connection active: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket handler error for channel {}:", ctx.channel().remoteAddress(), cause);
        // 发生异常时，也需要确保会话被清理
        sessionManager.removeSession(ctx.channel());
        ctx.close(); // 关闭连接
    }
}