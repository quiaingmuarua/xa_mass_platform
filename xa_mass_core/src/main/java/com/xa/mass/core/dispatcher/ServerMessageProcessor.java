package com.xa.mass.core.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.queue.MessageQueue;
import com.xa.mass.core.queue.StoredMessage;
import com.xa.mass.core.session.ServerSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ServerMessageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ServerMessageProcessor.class);
    private final Gson gson = new Gson();
    private ExecutorService executorService; // init 中初始化

    @Autowired
    private ServerSessionManager sessionManager;

    @Autowired
    @Qualifier("inputQueue")
    private MessageQueue<StoredMessage> inputQueue; // 修改泛型

    @Autowired
    @Qualifier("outputQueue")
    private MessageQueue<StoredMessage> outputQueue; // 修改泛型

    @PostConstruct
    public void init() {
        logger.info("ServerMessageProcessor init");
        int threadPoolSize = 2; // 一个用于输入，一个用于输
        executorService = Executors.newFixedThreadPool(threadPoolSize);
        startProcessing();
    }

    private void startProcessing() {
        logger.info("ServerMessageProcessor startProcessing");
        executorService.submit(this::processInputQueueLoop);
        executorService.submit(this::processOutputQueueLoop);
    }

    private void processInputQueueLoop() {
        while (!Thread.currentThread().isInterrupted() && executorService != null && !executorService.isShutdown()) {
            try {

                StoredMessage storedMessage = inputQueue.poll(15, TimeUnit.SECONDS); // 轮询 StoredMessage
                logger.info("Polling from inputQueue..." +inputQueue.getName() +" storedMessage= "+storedMessage);
                if (storedMessage != null) {
                    logger.debug("Polled from inputQueue: {}", storedMessage);
                    processStoredMessage(storedMessage); // 调用处理 StoredMessage 的方
                }
            } catch (InterruptedException e) {
                logger.info("Input queue processing thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing input message from queue", e);
                sleepSilently(1000);
            }
        }
        logger.info("Input queue processing loop finished.");
    }

    private void processOutputQueueLoop() {
        while (!Thread.currentThread().isInterrupted() && executorService != null && !executorService.isShutdown()) {
            try {
                StoredMessage storedMessage = outputQueue.poll(15, TimeUnit.SECONDS); // 轮询 StoredMessage
                logger.info("processOutputQueueLoop poll from outputQueue..." +outputQueue.getName() +" storedMessage= "+storedMessage);
                if (storedMessage != null) {
                    logger.debug("Polled from outputQueue: {}", storedMessage);
                    sendStoredMessage(storedMessage); // 调用发StoredMessage 的方
                }
            } catch (InterruptedException e) {
                logger.info("Output queue processing thread interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing output message from queue", e);
                sleepSilently(1000);
            }
        }
        logger.info("Output queue processing loop finished.");
    }

    // 方法名和参数类型修改为处StoredMessage
    private void processStoredMessage(StoredMessage storedMessage) {
        String messageContent = storedMessage.getMessageContent();
        String deviceId = storedMessage.getDeviceId();
        String connRole = storedMessage.getConnRole();

        if (deviceId == null || connRole == null) {
            logger.warn("DeviceId or ConnRole is null in StoredMessage. Cannot process. Message: {}", messageContent);
            return;
        }

        ChannelHandlerContext ctx = sessionManager.getChannelContext(deviceId, connRole);
        logger.info("processStoredMessage ChannelHandlerContext ctx {}", ctx);
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("ChannelHandlerContext not found or channel inactive for deviceId={}, connRole={}. Message dropped: {}",
                    deviceId, connRole, messageContent);
            return;
        }

        try {
            MassMessage<?> massMessage = gson.fromJson(messageContent, MassMessage.class);
            if (massMessage == null) {
                logger.warn("Failed to parse StoredMessage content to MassMessage: {}", messageContent);
                return;
            }

            MessageContext parsedContext = massMessage.getContext();
            // 校验从消息体解析出的 context 是否StoredMessage 中的路由信息一
            if (parsedContext == null || !deviceId.equals(parsedContext.getDeviceId()) || !connRole.equals(parsedContext.getConnRole())) {
                logger.warn("Mismatch or missing context in parsed MassMessage. Expected deviceId={}, connRole={}. Got: {}. Message: {}",
                        deviceId, connRole, parsedContext, messageContent);
                sendErrorResponse(ctx, "Message context mismatch or missing after parsing.");
                return;
            }
            logger.info("processStoredMessage start handle message {}", massMessage);
            Optional<MessageHandler> functionHandle = MessageHandlerRegistry.resolve(massMessage);
            if (functionHandle.isPresent()) {
                List<MassMessage<?>> resultMessages = functionHandle.get().handle(massMessage);
                if (resultMessages != null) {
                    for (MassMessage<?> resultMessage : resultMessages) {
                        String resultJson = gson.toJson(resultMessage);
                        outputQueue.offer(new StoredMessage(resultJson, deviceId, connRole));
                    }
                }

            }

        } catch (JsonParseException e) {
            logger.error("JSON parsing error in processStoredMessage for {}:{}. Message: {}", deviceId, connRole, messageContent, e);
            sendErrorResponse(ctx, "Invalid JSON message format");
        } catch (Exception e) {
            logger.error("Unexpected error processing StoredMessage for {}:{}. Message: {}", deviceId, connRole, messageContent, e);
            sendErrorResponse(ctx, "Internal server error while processing message");
        }
    }

    // 方法名和参数类型修改为处StoredMessage
    private void sendStoredMessage(StoredMessage storedMessage) {
        String messageContent = storedMessage.getMessageContent();
        String deviceId = storedMessage.getDeviceId();
        String connRole = storedMessage.getConnRole();

        if (deviceId == null || connRole == null) {
            logger.warn("DeviceId or ConnRole is null in StoredMessage. Cannot send. Message: {}", messageContent);
            return;
        }

        ChannelHandlerContext ctx = sessionManager.getChannelContext(deviceId, connRole);
        logger.info("sendStoredMessage ChannelHandlerContext ctx{}", ctx);
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("ChannelHandlerContext not found or channel inactive for deviceId={}, connRole={}. Cannot send message: {}",
                    deviceId, connRole, messageContent);
            return;
        }

        try {
            TextWebSocketFrame frame = new TextWebSocketFrame(messageContent);
            ctx.channel().writeAndFlush(frame);
            logger.info("sendStoredMessage Message sent to {} via deviceId={}, connRole={}",
                    ctx.channel().remoteAddress(), deviceId, connRole);
        } catch (Exception e) {
            logger.error("Failed to send message to {} (deviceId={}, connRole={})",
                    ctx.channel().remoteAddress(), deviceId, connRole, e);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String errorMessage) {
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("Cannot send error response, context is null or channel is inactive.");
            return;
        }
        try {
            MassMessage<Object> errorResponse = new MassMessage<>();
            errorResponse.setMsgType(MessageType.TASK);
            MessageResult result = new MessageResult();
            result.setCode(500);
            result.setMessage(errorMessage);
            errorResponse.setPayload(result);
            String errorJson = gson.toJson(errorResponse);
            TextWebSocketFrame frame = new TextWebSocketFrame(errorJson);
            ctx.channel().writeAndFlush(frame);
            logger.debug("Error response sent to {}: {}", ctx.channel().remoteAddress(), errorMessage);
        } catch (Exception e) {
            logger.error("Failed to build or send error response to {}", ctx.channel().remoteAddress(), e);
        }
    }

    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down ServerMessageProcessor executor service.");
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("ServerMessageProcessor executor service shut down.");
    }
}
