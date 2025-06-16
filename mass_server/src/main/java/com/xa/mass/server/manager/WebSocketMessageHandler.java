package com.xa.mass.server.manager;

import com.google.gson.Gson;
import com.xa.mass.model.message.BaseMessage;
import com.xa.mass.model.message.MessageContext;
import com.xa.mass.model.message.payload.TaskPayload;
import com.xa.mass.server.TaskResultHandler;
import io.netty.channel.ChannelHandler; // 导入 @Sharable
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable // <--- 添加此注解
public class WebSocketMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageHandler.class);
    private final Gson gson = new Gson();

    @Autowired
    private WebSocketSessionManager sessionManager;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String message = msgFrame.text();
        try {
            // 尝试先解析出 MessageContext 来获取 deviceId 和 connRole
            // 这一步是为了在 sessionManager.addSession 之前就能拿到关键信息
            // 注意：如果 BaseMessage 结构固定，可以直接解析。
            // 如果 payload 可能非常大或结构复杂，先解析外层获取 context 可能是个好主意。
            BaseMessage<?> preParseForContext = gson.fromJson(message, BaseMessage.class);
            MessageContext context = preParseForContext.getContext();

            if (context == null || context.getDeviceId() == null || context.getConnRole() == null) {
                logger.warn("Missing deviceId or connRole in context: {}", message);
                // 可以选择关闭连接或发送错误信息给客户端
                // ctx.close();
                return;
            }

            // 确保在处理消息前，会话已注册或更新
            sessionManager.addSession(context.getDeviceId(), context.getConnRole(), ctx.channel());

            // 完整解析消息
            BaseMessage<?> baseMessage = preParseForContext; // 如果上面已经完整解析，可以直接用

            // 处理不同类型的消息
            switch (baseMessage.getMsgType()) {
                case PING:
                    logger.debug("Received ping from {}:{}", context.getDeviceId(), context.getConnRole());
                    // 可以考虑回复 PONG
                    break;
                case TASK:
                    // 将 payload 强转为 TaskPayload
                    // 确保 baseMessage.getPayload() 返回的是可以被 GSON 正确转换为 TaskPayload 的结构
                    // 通常 GSON 在反序列化 BaseMessage<?> 时，payload 会是 JsonElement 或 LinkedTreeMap
                    TaskPayload taskPayload = gson.fromJson(gson.toJson(baseMessage.getPayload()), TaskPayload.class);
                    logger.info("Received task from {}:{} steps={}", context.getDeviceId(), context.getConnRole(),
                            taskPayload.getSteps() != null ? taskPayload.getSteps().size() : 0);
                    // 调用任务处理器
                    // TODO: 确认 TaskResultHandler.onClientResponse 的参数和处理逻辑是否适配
                    TaskResultHandler.onClientResponse(message);
                    break;
                case REGISTER: // 假设有 REGISTER 类型的消息
                    logger.info("Device {} registered for role {} via REGISTER message", context.getDeviceId(), context.getConnRole());
                    break;
                // case RESPONSE: // 如果服务端也需要处理客户端的 RESPONSE 消息
                //    logger.info("Received response from {}:{}", context.getDeviceId(), context.getConnRole());
                //    break;
                default:
                    logger.warn("Unsupported msgType: {} from {}:{}", baseMessage.getMsgType(), context.getDeviceId(), context.getConnRole());
            }

        } catch (com.google.gson.JsonSyntaxException e) {
            logger.error("JSON syntax error processing message: {}", message, e);
            // 可以向客户端发送错误提示
        }
        catch (Exception e) {
            logger.error("Error processing message: {}", message, e);
            // 考虑是否需要关闭连接
            // ctx.close();
        }
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
        // 注意：根据你之前的逻辑 "不注册 session，等收到 register/ping/task 再注册"
        // 所以这里通常不需要立即 addSession，而是在 channelRead0 中根据消息内容注册。
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket handler error for channel {}:", ctx.channel().remoteAddress(), cause);
        // 发生异常时，也需要确保会话被清理
        sessionManager.removeSession(ctx.channel());
        ctx.close(); // 关闭连接
    }
}