package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.getway.middleware.EnvelopeMiddleware;
import com.xa.mass.core.getway.middleware.ExceptionMiddleware;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;
import com.xa.mass.core.getway.queue.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class ServerMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private final DispatcherContext context;



    public ServerMessageDispatcher(DispatcherContext context) {
        logger.info("ServerMessageDispatcher DispatcherContext={}", context);
        this.context = context;
    }

    public void start(){
        for (int i = 0; i < 8; i++) {
            Executors.newFixedThreadPool(8).submit(this::processInputQueueLoop);
            Executors.newFixedThreadPool(8).submit(this::processOutputQueueLoop);
        }
    }

    private void processInputQueueLoop() {
        logger.info("processInputQueueLoop start");
        while (!Thread.currentThread().isInterrupted()) {
            Envelope envelope = null;
            try {
                envelope = context.getMessageTransporter().receiveInput(15, TimeUnit.SECONDS);
                logger.info("processInputQueueLoop receive envelope {}", envelope);
                if (envelope != null) {
                    context.setDirection(DispatcherContext.MiddlewareDirection.INPUT);
                    runMiddlewareChain(MiddlewareRegistry.instance.getActiveInputMiddlewares(), envelope);
                }
            } catch (Exception e) {
                runExceptionMiddlewareChain(envelope, e);
            }
        }
    }

    private void processOutputQueueLoop() {
        logger.info("processOutputQueueLoop start");
        while (!Thread.currentThread().isInterrupted()) {
            Envelope envelope = null;
            try {
                envelope = context.getMessageTransporter().receiveOutput(15, TimeUnit.SECONDS);
                logger.info("processOutputQueueLoop receive envelope {}", envelope);
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

