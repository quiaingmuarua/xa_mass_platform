package com.xa.mass.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.xa.mass.model.message.BaseMessage;
import com.xa.mass.model.message.MessageContext;
import com.xa.mass.model.message.MessageType;
import com.xa.mass.model.message.payload.TaskPayload;
import com.xa.mass.server.TaskResultHandler;
import com.xa.mass.server.manager.WebSocketSessionManager;
// import com.xa.mass.server.queue.InMemoryMessageQueue; // 不再直接依赖具体实现
import com.xa.mass.server.queue.MessageQueue;
import com.xa.mass.server.queue.WebSocketMessage;
import io.netty.channel.Channel;
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
import java.util.concurrent.TimeUnit;

@Service
public class WebSocketMessageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageProcessor.class);
    private final Gson gson = new Gson();
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);

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
                WebSocketMessage wsMessage = inputQueue.poll(15, TimeUnit.SECONDS);
                if (wsMessage != null) {
                    processMessage(wsMessage);
                }
            } catch (InterruptedException e) {
                logger.info("Input queue processing thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing input message", e);
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
                WebSocketMessage wsMessage = outputQueue.poll(15, TimeUnit.SECONDS);
                if (wsMessage != null) {
                    sendMessage(wsMessage);
                }
            } catch (InterruptedException e) {
                logger.info("Output queue processing thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing output message", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void processMessage(WebSocketMessage wsMessage) {
        String messageContent = wsMessage.getMessage();
        MessageContext msgContext = wsMessage.getMessageContext(); // 获取 MessageContext

        if (msgContext == null || msgContext.getDeviceId() == null || msgContext.getConnRole() == null) {
            logger.warn("MessageContext, deviceId, or connRole is null in WebSocketMessage. Cannot process. Message: {}", messageContent);
            return;
        }

        // 从 sessionManager 获取 ChannelHandlerContext
        ChannelHandlerContext ctx = sessionManager.getChannelContext(msgContext.getDeviceId(), msgContext.getConnRole());
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("ChannelHandlerContext not found or channel inactive for deviceId={}, connRole={}. Message dropped: {}",
                    msgContext.getDeviceId(), msgContext.getConnRole(), messageContent);
            return;
        }

        try {
            BaseMessage<?> baseMessage = gson.fromJson(messageContent, BaseMessage.class);
            if (baseMessage == null) {
                logger.warn("Failed to parse message to BaseMessage: {}", messageContent);
                return;
            }

            // baseMessage.getContext() 应该与 wsMessage.getMessageContext() 包含相同的信息
            // 这里可以根据需要选择使用哪个，或者进行校验
            MessageContext parsedContext = baseMessage.getContext();
            if (parsedContext == null || parsedContext.getDeviceId() == null) {
                logger.warn("Message context or deviceId is null after parsing. Message: {}", messageContent);
                sendErrorResponse(ctx, "Invalid message: missing context or deviceId after parsing");
                return;
            }

            switch (baseMessage.getMsgType()) {
                case PING:
                    logger.debug("Processing PING from {}:{}", parsedContext.getDeviceId(), parsedContext.getConnRole());
                    // 可以考虑回复 PONG 消息到 outputQueue
                    // Pong 消息也需要 MessageContext 来定位目标
                    // BaseMessage<Void> pongResponse = new BaseMessage<>();
                    // ... setup pongResponse ...
                    // outputQueue.offer(new WebSocketMessage(gson.toJson(pongResponse), parsedContext));
                    break;
                case TASK:
                    TaskPayload taskPayload = gson.fromJson(gson.toJson(baseMessage.getPayload()), TaskPayload.class);
                    logger.info("Processing TASK from {}:{} steps={}", parsedContext.getDeviceId(), parsedContext.getConnRole(),
                            taskPayload.getSteps() != null ? taskPayload.getSteps().size() : 0);
                    TaskResultHandler.onClientResponse(messageContent); // 注意：此方法需要明确其行为，可能也需要 MessageContext
                    break;
                case REGISTER:
                    logger.info("Processing REGISTER for device {} with role {}", parsedContext.getDeviceId(), parsedContext.getConnRole());
                    break;
                case RESPONSE:
                    logger.info("Received RESPONSE from {}:{} - usually client handles server's response.", parsedContext.getDeviceId(), parsedContext.getConnRole());
                    break;
                default:
                    logger.warn("Unsupported msgType in processMessage: {} from {}:{}", baseMessage.getMsgType(), parsedContext.getDeviceId(), parsedContext.getConnRole());
                    sendErrorResponse(ctx, "Unsupported message type: " + baseMessage.getMsgType());
            }
        } catch (JsonParseException e) {
            logger.error("JSON parsing error in processMessage: {}. Message: {}", e.getMessage(), messageContent);
            sendErrorResponse(ctx, "Invalid JSON message format");
        } catch (Exception e) {
            logger.error("Unexpected error processing message: {}. Message: {}", e.getMessage(), messageContent, e);
            sendErrorResponse(ctx, "Internal server error while processing message");
        }
    }

    private void sendMessage(WebSocketMessage wsMessage) {
        String messageContent = wsMessage.getMessage();
        MessageContext msgContext = wsMessage.getMessageContext();

        if (msgContext == null || msgContext.getDeviceId() == null || msgContext.getConnRole() == null) {
            logger.warn("MessageContext, deviceId, or connRole is null in WebSocketMessage. Cannot send. Message: {}", messageContent);
            return;
        }

        ChannelHandlerContext ctx = sessionManager.getChannelContext(msgContext.getDeviceId(), msgContext.getConnRole());

        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("ChannelHandlerContext not found or channel inactive for deviceId={}, connRole={}. Cannot send message: {}",
                    msgContext.getDeviceId(), msgContext.getConnRole(), messageContent);
            return;
        }

        try {
            // WebSocketMessage 现在不直接持有 TextWebSocketFrame，需要在这里创建
            TextWebSocketFrame frame = new TextWebSocketFrame(messageContent);
            ctx.channel().writeAndFlush(frame);
            logger.debug("Message sent to {} via deviceId={}, connRole={}",
                    ctx.channel().remoteAddress(), msgContext.getDeviceId(), msgContext.getConnRole());
        } catch (Exception e) {
            logger.error("Failed to send message to {} (deviceId={}, connRole={})",
                    ctx.channel().remoteAddress(), msgContext.getDeviceId(), msgContext.getConnRole(), e);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String errorMessage) {
        // 这个方法已经使用 ctx，所以不需要大改，只需确保调用时传入的 ctx 是有效的
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("Cannot send error response, context is null or channel is inactive.");
            return;
        }
        try {
            BaseMessage<Object> errorResponse = new BaseMessage<>();
            errorResponse.setMsgType(MessageType.RESPONSE);
            com.xa.mass.model.message.MessageResult result = new com.xa.mass.model.message.MessageResult();
            result.setCode(500);
            result.setMessage(errorMessage);
            errorResponse.setResult(result);

            String errorJson = gson.toJson(errorResponse);
            TextWebSocketFrame frame = new TextWebSocketFrame(errorJson);
            ctx.channel().writeAndFlush(frame);
            logger.debug("Error response sent to {}: {}", ctx.channel().remoteAddress(), errorMessage);
        } catch (Exception e) {
            logger.error("Failed to build or send error response to {}", ctx.channel().remoteAddress(), e);
        }
    }
}