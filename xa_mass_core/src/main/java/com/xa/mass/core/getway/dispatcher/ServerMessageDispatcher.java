package com.xa.mass.core.getway.dispatcher;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.xa.mass.core.getway.middleware.MessageMiddleware;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;
import com.xa.mass.core.getway.middleware.OutputMessageMiddleware;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.session.ServerSessionManager;
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
    private final Gson gson = new Gson();
    private ExecutorService inputExecutor;
    private ExecutorService outputExecutor;

    @Autowired private ServerSessionManager sessionManager;
    @Autowired @Qualifier("inputQueue") private MessageQueue<Envelope> inputQueue;
    @Autowired @Qualifier("outputQueue") private MessageQueue<Envelope> outputQueue;

    @PostConstruct
    public void init() {
        inputExecutor = Executors.newFixedThreadPool(8);
        outputExecutor = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            inputExecutor.submit(this::processInputQueueLoop);
            outputExecutor.submit(this::processOutputQueueLoop);
        }
    }

    private void processInputQueueLoop() {
        logger.info("Starting input queue loop...");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Envelope envelope = inputQueue.poll(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    for (MessageMiddleware middleware : MiddlewareRegistry.getInputMiddlewares()) {
                        if (!middleware.handle(envelope)) return;
                    }
                    processEnvelope(envelope);
                }
            } catch (Exception e) {
                logger.error("Error processing input", e);
            }
        }
    }

    private void processOutputQueueLoop() {
        logger.info("Starting output queue loop...");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Envelope envelope = outputQueue.poll(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    for (OutputMessageMiddleware middleware : MiddlewareRegistry.getOutputMiddlewares()) {
                        if (!middleware.handle(envelope)) return;
                    }
                    sendEnvelope(envelope);
                }
            } catch (Exception e) {
                logger.error("Error processing output", e);
            }
        }
    }

    private void processEnvelope(Envelope envelope) {
        try {
            MassMessage msg = gson.fromJson(envelope.getRawJson(), MassMessage.class);
            if (msg == null || msg.getContext() == null) return;

            MessageContext context = msg.getContext();
            Optional<MessageHandler> handler = MessageHandlerRegistry.resolve(msg);
            if (handler.isPresent()) {
                List<MassMessage> responses = handler.get().handle(msg);
                if (responses != null) {
                    for (MassMessage resp : responses) {
                        String json = gson.toJson(resp);
                        outputQueue.offer(Envelope.builder()
                                .deviceId(context.getDeviceId())
                                .connRole(context.getConnRole())
                                .rawJson(json)
                                .build());
                    }
                }
            }
        } catch (JsonParseException e) {
            logger.warn("Failed to parse input: {}", envelope.getRawJson());
        } catch (Exception e) {
            logger.error("Error in processEnvelope", e);
        }
    }

    private void sendEnvelope(Envelope envelope) {
        ChannelHandlerContext ctx = sessionManager.getChannelContext(envelope.getDeviceId(), envelope.getConnRole());
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(envelope.getRawJson()));
        }
    }

    @PreDestroy
    public void shutdown() {
        inputExecutor.shutdownNow();
        outputExecutor.shutdownNow();
    }
}
