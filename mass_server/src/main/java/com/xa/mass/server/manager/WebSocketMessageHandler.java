package com.xa.mass.server.manager;

import com.google.gson.Gson;
import com.xa.mass.model.message.BaseMessage;
import com.xa.mass.model.message.MessageContext;
import com.xa.mass.model.message.payload.TaskPayload;
import com.xa.mass.server.TaskResultHandler;
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
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String message = msgFrame.text();
        try {
            BaseMessage<?> baseMessage = gson.fromJson(message, BaseMessage.class);
            MessageContext context = baseMessage.getContext();
            if (context == null || context.getDeviceId() == null || context.getConnRole() == null) {
                logger.warn("Missing deviceId or connRole in context: {}", message);
                return;
            }

            // 注册连接
            sessionManager.addSession(context.getDeviceId(), context.getConnRole(), ctx.channel());

            // 处理不同类型的消息
            switch (baseMessage.getMsgType()) {
                case PING:
                    logger.debug("Received ping from {}:{}", context.getDeviceId(), context.getConnRole());
                    break;
                case TASK:
                    // 将 payload 强转为 TaskPayload
                    TaskPayload taskPayload = gson.fromJson(gson.toJson(baseMessage.getPayload()), TaskPayload.class);
                    logger.info("Received task from {}:{} steps={}", context.getDeviceId(), context.getConnRole(),
                            taskPayload.getSteps().size());
                    // 调用任务处理器
                    TaskResultHandler.onClientResponse(message);  // 此处你可能也要改为支持新的结构
                    break;
                case REGISTER:
                    logger.info("Device {} registered for role {}", context.getDeviceId(), context.getConnRole());
                    break;
                default:
                    logger.warn("Unsupported msgType: {}", baseMessage.getMsgType());
            }

        } catch (Exception e) {
            logger.error("Error processing message: {}", message, e);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        sessionManager.removeSession(ctx.channel());

    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.info("New WebSocket connection: {}", ctx.channel().remoteAddress());
        // 不注册 session，等收到 register/ping/task 再注册
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket error", cause);
        ctx.close();
    }
}
