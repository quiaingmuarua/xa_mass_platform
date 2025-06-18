package com.xa.mass.core.dispatcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.queue.Envelope;
import com.xa.mass.core.queue.MessageQueue;
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
public class ServerMessageDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private static final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private ExecutorService inputExecutor;
    private ExecutorService outputExecutor;

    @Autowired
    private ServerSessionManager sessionManager;

    @Autowired
    @Qualifier("inputQueue")
    private MessageQueue<Envelope> inputQueue;

    @Autowired
    @Qualifier("outputQueue")
    private MessageQueue<Envelope> outputQueue;

    @PostConstruct
    public void init() {
        logger.info("ServerMessageDispatcher init");
        inputExecutor = Executors.newFixedThreadPool(8);
        outputExecutor = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            inputExecutor.submit(this::processInputQueueLoop);
            outputExecutor.submit(this::processOutputQueueLoop);
        }
    }

    private void processInputQueueLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Envelope envelope = inputQueue.poll(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    logger.debug("Polled input: {}", envelope);
                    processStoredMessage(envelope);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing input", e);
                sleepSilently(1000);
            }
        }
    }

    private void processOutputQueueLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Envelope envelope = outputQueue.poll(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    logger.debug("Polled output: {}", envelope);
                    sendStoredMessage(envelope);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing output", e);
                sleepSilently(1000);
            }
        }
    }

    private void processStoredMessage(Envelope envelope) {
        String raw = envelope.getRawJson();
        String deviceId = envelope.getDeviceId();
        String connRole = envelope.getConnRole();

        if (deviceId == null || connRole == null) {
            logger.warn("Envelope missing routing info: {}", envelope);
            return;
        }

        ChannelHandlerContext ctx = sessionManager.getChannelContext(deviceId, connRole);
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("Inactive or missing channel for {}:{}", deviceId, connRole);
            return;
        }

        try {
            MassMessage<?> message = gson.fromJson(raw, MassMessage.class);
            if (message == null) {
                logger.warn("Failed to parse message: {}", raw);
                return;
            }

            MessageContext parsedCtx = message.getContext();
            if (parsedCtx == null || !deviceId.equals(parsedCtx.getDeviceId()) || !connRole.equals(parsedCtx.getConnRole())) {
                logger.warn("Context mismatch in message: {}", message);
                sendErrorResponse(ctx, "Message context mismatch");
                return;
            }

            Optional<MessageHandler> handlerOpt = MessageHandlerRegistry.resolve(message);
            if (handlerOpt.isPresent()) {
                List<MassMessage<?>> resultList = handlerOpt.get().handle(message);
                if (resultList != null) {
                    for (MassMessage<?> result : resultList) {
                        outputQueue.offer(Envelope.builder()
                                .rawJson(gson.toJson(result))
                                .deviceId(deviceId)
                                .connRole(connRole)
                                .traceId(envelope.getTraceId())
                                .receivedAt(System.currentTimeMillis())
                                .build());
                    }
                }
            }

        } catch (JsonParseException e) {
            logger.error("JSON parse error for device {}:{}", deviceId, connRole, e);
            sendErrorResponse(ctx, "Invalid message format");
        } catch (Exception e) {
            logger.error("Unexpected error processing message", e);
            sendErrorResponse(ctx, "Internal error");
        }
    }

    private void sendStoredMessage(Envelope envelope) {
        ChannelHandlerContext ctx = sessionManager.getChannelContext(envelope.getDeviceId(), envelope.getConnRole());
        if (ctx == null || !ctx.channel().isActive()) {
            logger.warn("Channel not found for sending: {}", envelope);
            return;
        }
        try {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(envelope.getRawJson()));
            logger.debug("Message sent to {}:{}", envelope.getDeviceId(), envelope.getConnRole());
        } catch (Exception e) {
            logger.error("Send failed for {}:{}", envelope.getDeviceId(), envelope.getConnRole(), e);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String errorMsg) {
        try {
            MassMessage<Object> response = new MassMessage<>();
            response.setMsgType(MessageType.TASK); // 保持通用消息类型，或单独定义 ERROR
            MessageResult result = new MessageResult();
            result.setCode(500);
            result.setMessage(errorMsg);
            response.setPayload(result);

            ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
        } catch (Exception e) {
            logger.error("Failed to send error response", e);
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
        logger.info("Shutting down ServerMessageDispatcher");
        shutdownExecutor(inputExecutor);
        shutdownExecutor(outputExecutor);
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
