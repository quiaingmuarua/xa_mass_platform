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
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private final DispatcherContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService inputExecutor;
    private ExecutorService outputExecutor;

    public ServerMessageDispatcher(DispatcherContext context) {
        logger.info("ServerMessageDispatcher DispatcherContext={}", context);
        this.context = context;
    }

    public void start(){
        if (running.compareAndSet(false, true)) {
            logger.info("🚀 Starting ServerMessageDispatcher...");
            
            // 创建线程池
            inputExecutor = Executors.newFixedThreadPool(8);
            outputExecutor = Executors.newFixedThreadPool(8);
            
            // 启动处理线程
            for (int i = 0; i < 8; i++) {
                inputExecutor.submit(this::processInputQueueLoop);
                outputExecutor.submit(this::processOutputQueueLoop);
            }
            
            logger.info("✅ ServerMessageDispatcher started successfully");
        }
    }
    
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("🛑 Stopping ServerMessageDispatcher...");
            
            try {
                // 关闭线程池
                if (inputExecutor != null) {
                    inputExecutor.shutdown();
                    if (!inputExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        inputExecutor.shutdownNow();
                    }
                }
                
                if (outputExecutor != null) {
                    outputExecutor.shutdown();
                    if (!outputExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        outputExecutor.shutdownNow();
                    }
                }
                
                logger.info("✅ ServerMessageDispatcher stopped successfully");
                
            } catch (InterruptedException e) {
                logger.error("❌ Error stopping ServerMessageDispatcher", e);
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public boolean isRunning() {
        return running.get();
    }

    private void processInputQueueLoop() {
        logger.info("processInputQueueLoop start");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
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
        logger.info("processInputQueueLoop stopped");
    }

    private void processOutputQueueLoop() {
        logger.info("processOutputQueueLoop start");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
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
        logger.info("processOutputQueueLoop stopped");
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

