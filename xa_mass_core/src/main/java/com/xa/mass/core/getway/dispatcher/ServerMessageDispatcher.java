package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.ExceptionMiddleware;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;
import com.xa.mass.core.getway.queue.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class ServerMessageDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private final DispatcherContext context;
    private final ExecutorService inputExecutor;
    private final ExecutorService outputExecutor;


    public ServerMessageDispatcher(
            DispatcherContext context
    ) {

        this.context = context;

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
                    logger.debug("processInputQueueLoop receive envelope {}", envelope);
                    context.setDirection(DispatcherContext.MiddlewareDirection.INPUT);
                    runMiddlewareChain(MiddlewareRegistry.instance.getActiveInputMiddlewares(), envelope);
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
                logger.debug("processOutputQueueLoop receive envelope {}", envelope);
                if (envelope != null) {
                    context.setDirection(DispatcherContext.MiddlewareDirection.OUTPUT);
                    runMiddlewareChain(MiddlewareRegistry.instance.getActiveOutputMiddlewares(), envelope);
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
        for (ExceptionMiddleware middleware : MiddlewareRegistry.instance.getExceptionMiddlewareList()) {
            if (!middleware.handleException(envelope, context, e)) break;
        }
    }
}

