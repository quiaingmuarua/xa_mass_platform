package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.ExceptionMiddleware;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.getway.queue.Envelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

public class ServerMessageDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private final List<EnvelopeMiddleware> inputMiddlewareList;
    private final List<EnvelopeMiddleware> outputMiddlewareList;
    private final List<ExceptionMiddleware> exceptionMiddlewareList;
    private final DispatcherContext context;
    private final ExecutorService inputExecutor;
    private final ExecutorService outputExecutor;

    public ServerMessageDispatcher(
            List<EnvelopeMiddleware> inputMiddlewareList,
            List<EnvelopeMiddleware> outputMiddlewareList,
            List<ExceptionMiddleware> exceptionMiddlewareList,
            DispatcherContext context
    ) {
        this.inputMiddlewareList = new ArrayList<>(inputMiddlewareList);
        this.outputMiddlewareList = new ArrayList<>(outputMiddlewareList);
        this.exceptionMiddlewareList = exceptionMiddlewareList;
        this.context = context;
        // 自动注册主流程 middleware
        this.inputMiddlewareList.add(processEnvelopeMiddleware());
        this.outputMiddlewareList.add(sendEnvelopeMiddleware());
        inputExecutor = Executors.newFixedThreadPool(8);
        outputExecutor = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            inputExecutor.submit(this::processInputQueueLoop);
            outputExecutor.submit(this::processOutputQueueLoop);
        }
    }

    private void processInputQueueLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Envelope envelope = null;
            try {
                envelope = context.getInputQueue().poll(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    runMiddlewareChain(inputMiddlewareList, envelope);
                }
            } catch (Exception e) {
                runExceptionMiddlewareChain(envelope, e);
            }
        }
    }

    private void processOutputQueueLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Envelope envelope = null;
            try {
                envelope = context.getOutputQueue().poll(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    runMiddlewareChain(outputMiddlewareList, envelope);
                }
            } catch (Exception e) {
                runExceptionMiddlewareChain(envelope, e);
            }
        }
    }

    private void runMiddlewareChain(List<EnvelopeMiddleware> chain, Envelope envelope) {
        for (EnvelopeMiddleware middleware : chain) {
            if (!middleware.handle(envelope, context)) break;
        }
    }

    private void runExceptionMiddlewareChain(Envelope envelope, Exception e) {
        for (ExceptionMiddleware middleware : exceptionMiddlewareList) {
            if (!middleware.handleException(envelope, context, e)) break;
        }
    }

    // 可作为 input/output middleware 链的最后一环
    public static EnvelopeMiddleware processEnvelopeMiddleware() {
        return (envelope, context) -> {
            try {
                MassMessage msg = context.getGson().fromJson(envelope.getRawJson(), MassMessage.class);
                if (msg == null || msg.getContext() == null) return true;
                MessageContext ctx = msg.getContext();
                Optional<com.xa.mass.core.getway.dispatcher.MessageHandler> handler = MessageHandlerRegistry.resolve(msg);
                if (handler.isPresent()) {
                    List<MassMessage> responses = handler.get().handle(msg);
                    if (responses != null) {
                        for (MassMessage resp : responses) {
                            String json = context.getGson().toJson(resp);
                            context.getOutputQueue().offer(Envelope.builder()
                                    .deviceId(ctx.getDeviceId())
                                    .connRole(ctx.getConnRole())
                                    .rawJson(json)
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error in processEnvelopeMiddleware", e);
                return false;
            }
            return true;
        };
    }

    public static EnvelopeMiddleware sendEnvelopeMiddleware() {
        return (envelope, context) -> {
            try {
                ChannelHandlerContext ctx = context.getSessionManager().getChannelContext(envelope.getDeviceId(), envelope.getConnRole());
                if (ctx != null && ctx.channel().isActive()) {
                    ctx.writeAndFlush(new TextWebSocketFrame(envelope.getRawJson()));
                }
            } catch (Exception e) {
                logger.error("Error in sendEnvelopeMiddleware", e);
                return false;
            }
            return true;
        };
    }

    @PreDestroy
    public void shutdown() {
        inputExecutor.shutdownNow();
        outputExecutor.shutdownNow();
    }
}

