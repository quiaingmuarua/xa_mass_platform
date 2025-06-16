package com.xa.mass.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.xa.mass.model.message.BaseMessage;
import com.xa.mass.model.message.MessageContext;
import com.xa.mass.model.message.MessageType;
import com.xa.mass.model.message.payload.TaskPayload;
import com.xa.mass.server.TaskResultHandler;
import com.xa.mass.server.manager.WebSocketSessionManager;
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
        // Start input queue processor
        executorService.submit(this::processInputQueue);
        // Start output queue processor
        executorService.submit(this::processOutputQueue);
    }

    private void processInputQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WebSocketMessage message = inputQueue.poll();
                if (message != null) {
                    processMessage(message);
                } else {
                    Thread.sleep(10000); // Avoid busy waiting
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing input message", e);
            }
        }
    }

    private void processOutputQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {

                WebSocketMessage message = outputQueue.poll();
                if (message != null) {
                    sendMessage(message);
                } else {
                    Thread.sleep(10000); // Avoid busy waiting
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing output message", e);
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
            sessionManager.addSession(context.getDeviceId(), context.getConnRole(), ctx.channel());

            switch (baseMessage.getMsgType()) {
                case PING:
                    logger.debug("Received ping from {}:{}", context.getDeviceId(), context.getConnRole());
                    break;
                case TASK:
                    TaskPayload taskPayload = gson.fromJson(gson.toJson(baseMessage.getPayload()), TaskPayload.class);
                    logger.info("Received task from {}:{} steps={}", context.getDeviceId(), context.getConnRole(),
                            taskPayload.getSteps() != null ? taskPayload.getSteps().size() : 0);
                    TaskResultHandler.onClientResponse(message);
                    break;
                case REGISTER:
                    logger.info("Device {} registered for role {} via REGISTER message", context.getDeviceId(), context.getConnRole());
                    break;
                case RESPONSE:
                    logger.info("Received response from {}:{}", context.getDeviceId(), context.getConnRole());
                    break;
                default:
                    logger.warn("Unsupported msgType: {} from {}:{}", baseMessage.getMsgType(), context.getDeviceId(), context.getConnRole());
            }
        } catch (JsonParseException e) {
            logger.error("JSON parsing error: {}", e.getMessage());
            sendErrorResponse(ctx, "Invalid message format");
        } catch (Exception e) {
            logger.error("Unexpected error processing message: {}", e.getMessage());
            sendErrorResponse(ctx, "Internal server error");
        }
    }

    private void sendMessage(WebSocketMessage wsMessage) {
        try {
            wsMessage.getCtx().channel().writeAndFlush(wsMessage.getFrame());
        } catch (Exception e) {
            logger.error("Failed to send message", e);
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, String errorMessage) {
        try {
            BaseMessage<String> errorResponse = new BaseMessage<>();
            errorResponse.setMsgType(MessageType.RESPONSE);
            errorResponse.setPayload(errorMessage);

            TextWebSocketFrame frame = new TextWebSocketFrame(gson.toJson(errorResponse));
            outputQueue.offer(new WebSocketMessage(gson.toJson(errorResponse), ctx, frame));
        } catch (Exception e) {
            logger.error("Failed to send error response", e);
        }
    }
} 