package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.middleware.EnvelopeMiddleware;
import com.xa.mass.gateway.dispatcher.middleware.ExceptionMiddleware;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.queue.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private final DispatchRuntimeContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService inputExecutor;
    private ExecutorService outputExecutor;

    public ServerMessageDispatcher(DispatchRuntimeContext context) {
        logger.debug("ServerMessageDispatcher context={}", context);
        this.context = context;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting ServerMessageDispatcher...");

            inputExecutor = Executors.newFixedThreadPool(8);
            outputExecutor = Executors.newFixedThreadPool(8);

            for (int i = 0; i < 8; i++) {
                inputExecutor.submit(this::processInputQueueLoop);
                outputExecutor.submit(this::processOutputQueueLoop);
            }

            logger.info("ServerMessageDispatcher started successfully");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping ServerMessageDispatcher...");

            try {
                shutdownExecutor(inputExecutor, "input");
                shutdownExecutor(outputExecutor, "output");
                logger.info("ServerMessageDispatcher stopped successfully");
            } catch (InterruptedException e) {
                logger.warn("Stopping ServerMessageDispatcher was interrupted");
                if (inputExecutor != null) {
                    inputExecutor.shutdownNow();
                }
                if (outputExecutor != null) {
                    outputExecutor.shutdownNow();
                }
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void processInputQueueLoop() {
        logger.debug("processInputQueueLoop start");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Envelope envelope = null;
            try {
                envelope = context.getMessageTransporter().receiveInput(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    logger.debug("processInputQueueLoop receive envelope {}", envelope);
                    context.setDirection(DispatcherContext.MiddlewareDirection.INPUT);
                    runMiddlewareChain(MiddlewareRegistry.instance.getActiveInputMiddlewares(), envelope);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (running.get()) {
                    logger.warn("processInputQueueLoop interrupted while dispatcher is still marked running");
                }
                break;
            } catch (Exception e) {
                runExceptionMiddlewareChain(envelope, e);
            }
        }
        logger.debug("processInputQueueLoop stopped");
    }

    private void processOutputQueueLoop() {
        logger.debug("processOutputQueueLoop start");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Envelope envelope = null;
            try {
                envelope = context.getMessageTransporter().receiveOutput(15, TimeUnit.SECONDS);
                if (envelope != null) {
                    logger.debug("processOutputQueueLoop receive envelope {}", envelope);
                    context.setDirection(DispatcherContext.MiddlewareDirection.OUTPUT);
                    runMiddlewareChain(MiddlewareRegistry.instance.getActiveOutputMiddlewares(), envelope);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (running.get()) {
                    logger.warn("processOutputQueueLoop interrupted while dispatcher is still marked running");
                }
                break;
            } catch (Exception e) {
                runExceptionMiddlewareChain(envelope, e);
            }
        }
        logger.debug("processOutputQueueLoop stopped");
    }

    private void runMiddlewareChain(List<EnvelopeMiddleware> chain, Envelope envelope) {
        for (EnvelopeMiddleware middleware : chain) {
            if (!middleware.handle(envelope, context)) {
                break;
            }
        }
    }

    private void runExceptionMiddlewareChain(Envelope envelope, Exception e) {
        for (ExceptionMiddleware middleware : MiddlewareRegistry.instance.getExceptionMiddlewareList()) {
            if (!middleware.handleException(envelope, context, e)) {
                break;
            }
        }
    }

    private void shutdownExecutor(ExecutorService executor, String name) throws InterruptedException {
        if (executor == null) {
            return;
        }
        List<Runnable> queuedTasks = executor.shutdownNow();
        logger.info("Requested {} dispatcher executor shutdown, cancelled {} queued tasks", name, queuedTasks.size());
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            logger.warn("{} dispatcher executor did not terminate within timeout", name);
        }
    }
}
