package com.xa.mass.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.xa.mass.model.message.BaseMessage;
import com.xa.mass.model.message.MessageContext;
import com.xa.mass.model.message.MessageType;
import com.xa.mass.model.message.payload.TaskPayload;
import com.xa.mass.server.TaskResultHandler;
import com.xa.mass.server.manager.WebSocketSessionManager;
import com.xa.mass.server.queue.InMemoryMessageQueue; // 确保 InMemoryMessageQueue 导入
import com.xa.mass.server.queue.MessageQueue;
import com.xa.mass.server.queue.WebSocketMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit; // 新增导入

@Service
public class WebSocketMessageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageProcessor.class);
    private final Gson gson = new Gson();
    private final ExecutorService executorService = Executors.newFixedThreadPool(3); // 考虑根据CPU核数调整

    @Autowired
    private WebSocketSessionManager sessionManager;

    @Autowired
    @Qualifier("inputQueue")
    private MessageQueue<WebSocketMessage> inputQueue;

    @Autowired
    @Qualifier("outputQueue")
    private MessageQueue<WebSocketMessage> outputQueue;

    @PostConstruct
    public void init() {
        logger.info("WebSocketMessageProcessor init");
        startProcessing();
    }

    private void startProcessing() {
        logger.info("WebSocketMessageProcessor startProcessing");
        executorService.submit(this::processInputQueue);
        executorService.submit(this::processOutputQueue);
    }

    private void processInputQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 使用带超时的 poll，例如等待5秒
                WebSocketMessage message = inputQueue.poll(15, TimeUnit.SECONDS);
                if (message != null) {
                    processMessage(message);
                }
                // 如果 message 为 null，表示超时内没有消息，循环会继续，并再次尝试 poll
            } catch (InterruptedException e) {
                logger.info("Input queue processing thread interrupted.");
                Thread.currentThread().interrupt(); // 重新设置中断状态
                break; // 退出循环
            } catch (Exception e) {
                logger.error("Error processing input message", e);
                // 发生其他异常时，也考虑短暂休眠以避免在持续错误时快速循环
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processOutputQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 使用带超时的 poll，例如等待5秒
                WebSocketMessage message = outputQueue.poll(15, TimeUnit.SECONDS);
                if (message != null) {
                    sendMessage(message);
                }
                // 如果 message 为 null，表示超时内没有消息，循环会继续，并再次尝试 poll
            } catch (InterruptedException e) {
                logger.info("Output queue processing thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing output message", e);
                // 发生其他异常时，也考虑短暂休眠
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processMessage(WebSocketMessage wsMessage) {
        String message = wsMessage.getMessage();
        ChannelHandlerContext ctx = wsMessage.getCtx();

        try {
            BaseMessage<?> baseMessage = gson.fromJson(message, BaseMessage.class);
            if (baseMessage == null) {
                logger.warn("Failed to parse message to BaseMessage: {}", message);
                return;
            }

            MessageContext context = baseMessage.getContext();
            if (context == null || context.getDeviceId() == null) {
                logger.warn("Message context or deviceId is null. Message: {}", message);
                sendErrorResponse(ctx, "Invalid message: missing context or deviceId");
                return;
            }
            // 确保会话已注册或更新，这一步在 WebSocketMessageHandler 中已经做了，
            // 但在这里再次调用可以确保状态一致性，或者根据业务逻辑决定是否需要。
            // sessionManager.addSession(context.getDeviceId(), context.getConnRole(), ctx.channel());

            switch (baseMessage.getMsgType()) {
                case PING:
                    logger.debug("Processing PING from {}:{}", context.getDeviceId(), context.getConnRole());
                    // 可以考虑回复 PONG 消息到 outputQueue
                    break;
                case TASK:
                    TaskPayload taskPayload = gson.fromJson(gson.toJson(baseMessage.getPayload()), TaskPayload.class);
                    logger.info("Processing TASK from {}:{} steps={}", context.getDeviceId(), context.getConnRole(),
                            taskPayload.getSteps() != null ? taskPayload.getSteps().size() : 0);
                    // 假设 TaskResultHandler.onClientResponse 是处理任务并可能产生响应的地方
                    // 如果它直接发送响应，那么它应该使用 outputQueue
                    TaskResultHandler.onClientResponse(message); // 注意：此方法需要明确其行为
                    break;
                case REGISTER: // 假设 REGISTER 消息也由这里处理
                    logger.info("Processing REGISTER for device {} with role {}", context.getDeviceId(), context.getConnRole());
                    // 注册逻辑，可能需要发送确认消息到 outputQueue
                    break;
                case RESPONSE: // 服务端通常不处理来自客户端的 RESPONSE，除非有特定场景
                    logger.info("Received RESPONSE from {}:{} - usually client handles server's response.", context.getDeviceId(), context.getConnRole());
                    break;
                default:
                    logger.warn("Unsupported msgType in processMessage: {} from {}:{}", baseMessage.getMsgType(), context.getDeviceId(), context.getConnRole());
                    sendErrorResponse(ctx, "Unsupported message type: " + baseMessage.getMsgType());
            }
        } catch (JsonParseException e) {
            logger.error("JSON parsing error in processMessage: {}. Message: {}", e.getMessage(), message);
            sendErrorResponse(ctx, "Invalid JSON message format");
        } catch (Exception e) {
            logger.error("Unexpected error processing message: {}. Message: {}", e.getMessage(), message, e);
            sendErrorResponse(ctx, "Internal server error while processing message");
        }
    }

    private void sendMessage(WebSocketMessage wsMessage) {
        if (wsMessage == null || wsMessage.getCtx() == null || wsMessage.getFrame() == null) {
            logger.warn("Attempted to send a null message, context, or frame.");
            return;
        }
        if (!wsMessage.getCtx().channel().isActive()) {
            logger.warn("Attempted to send message on an inactive channel for deviceId (if available in wsMessage context)");
            return;
        }
        try {
            wsMessage.getCtx().channel().writeAndFlush(wsMessage.getFrame());
            logger.debug("Message sent to {}", wsMessage.getCtx().channel().remoteAddress());
        } catch (Exception e) {
            logger.error("Failed to send message to {}", wsMessage.getCtx().channel().remoteAddress(), e);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String errorMessage) {
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("Cannot send error response, context is null or channel is inactive.");
            return;
        }
        try {
            BaseMessage<Object> errorResponse = new BaseMessage<>(); // 使用 Object 或具体错误载体
            errorResponse.setMsgType(MessageType.RESPONSE); // 或者定义一个 ERROR MessageType
            // errorResponse.setMsgId(...); // 如果需要关联请求
            // errorResponse.setContext(...); // 如果需要透传部分上下文

            // 构建一个标准的错误 payload
            com.xa.mass.model.message.MessageResult result = new com.xa.mass.model.message.MessageResult();
            result.setCode(500); // 或者其他错误码
            result.setMessage(errorMessage);
            errorResponse.setResult(result);
            // errorResponse.setPayload(null); // 或者包含更详细的错误信息

            String errorJson = gson.toJson(errorResponse);
            TextWebSocketFrame frame = new TextWebSocketFrame(errorJson);
            // 直接发送错误响应，或者放入 outputQueue（如果 outputQueue 是用于所有出站消息）
            // 为简单起见，这里直接发送，但放入 outputQueue 更符合队列处理模式
            // outputQueue.offer(new WebSocketMessage(errorJson, ctx, frame));
            ctx.channel().writeAndFlush(frame);
            logger.debug("Error response sent to {}: {}", ctx.channel().remoteAddress(), errorMessage);
        } catch (Exception e) {
            logger.error("Failed to build or send error response to {}", ctx.channel().remoteAddress(), e);
        }
    }
}