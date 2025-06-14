package com.xa.mass.server;

import com.google.gson.Gson;
import com.xa.mass.model.message.TaskMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebSocketMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageHandler.class);
    private final Gson gson = new Gson();

    @Autowired
    private WebSocketSessionManager sessionManager;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        String message = msg.text();
        try {
            // 尝试解析消息，获取设备ID
            TaskMessage taskMessage = gson.fromJson(message, TaskMessage.class);
            String deviceId = taskMessage.getMsgId(); // 使用msgId作为设备ID，实际项目中可能需要从消息中提取真实的设备ID

            // 处理消息
            TaskResultHandler.onClientResponse(message);
            
            // 记录设备活动
            logger.info("Received message from device {}: {}", deviceId, message);
        } catch (Exception e) {
            logger.error("Error processing message: {}", message, e);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        String deviceId = sessionManager.getDeviceId(ctx.channel());
        if (deviceId != null) {
            sessionManager.removeSession(ctx.channel());
            logger.info("Device disconnected: {}", deviceId);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 生成临时设备ID，实际项目中应该从认证信息中获取
        String deviceId = "device_" + ctx.channel().id().asShortText();
        sessionManager.addSession(deviceId, ctx.channel());
        logger.info("New device connected: {}", deviceId);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket error", cause);
        ctx.close();
    }
}