package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.middleware.EnvelopeMiddleware;
import com.xa.mass.gateway.dispatcher.middleware.ExceptionMiddleware;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.queue.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ServerMessageDispatcher.class);
    private static final int INPUT_LOOP_THREADS = 8;
    private static final int OUTPUT_LANE_THREADS = 8;
    private final DispatchRuntimeContext context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService inputExecutor;
    private ExecutorService outputPollerExecutor;
    private ExecutorService[] outputLaneExecutors;

    public ServerMessageDispatcher(DispatchRuntimeContext context) {
        logger.debug("ServerMessageDispatcher context={}", context);
        this.context = context;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting ServerMessageDispatcher...");

            inputExecutor = Executors.newFixedThreadPool(INPUT_LOOP_THREADS);
            outputPollerExecutor = Executors.newSingleThreadExecutor();
            outputLaneExecutors = new ExecutorService[OUTPUT_LANE_THREADS];

            for (int i = 0; i < INPUT_LOOP_THREADS; i++) {
                inputExecutor.submit(this::processInputQueueLoop);
            }
            for (int i = 0; i < OUTPUT_LANE_THREADS; i++) {
                outputLaneExecutors[i] = Executors.newSingleThreadExecutor();
            }
            outputPollerExecutor.submit(this::processOutputQueueLoop);

            logger.info("ServerMessageDispatcher started successfully");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping ServerMessageDispatcher...");

            try {
                shutdownExecutor(inputExecutor, "input");
                shutdownExecutor(outputPollerExecutor, "output-poller");
                shutdownExecutors(outputLaneExecutors, "output-lane");
                logger.info("ServerMessageDispatcher stopped successfully");
            } catch (InterruptedException e) {
                logger.warn("Stopping ServerMessageDispatcher was interrupted");
                if (inputExecutor != null) {
                    inputExecutor.shutdownNow();
                }
                if (outputPollerExecutor != null) {
                    outputPollerExecutor.shutdownNow();
                }
                if (outputLaneExecutors != null) {
                    for (ExecutorService executor : outputLaneExecutors) {
                        if (executor != null) {
                            executor.shutdownNow();
                        }
                    }
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
                    runMiddlewareChain(MiddlewareRegistry.instance.getInputMiddlewares(), envelope);
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
                    submitOutputEnvelope(envelope);
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

    private void submitOutputEnvelope(Envelope envelope) {
        ExecutorService laneExecutor = resolveOutputLaneExecutor(envelope);
        laneExecutor.submit(() -> {
            try {
                context.setDirection(DispatcherContext.MiddlewareDirection.OUTPUT);
                runMiddlewareChain(MiddlewareRegistry.instance.getOutputMiddlewares(), envelope);
            } catch (Exception e) {
                runExceptionMiddlewareChain(envelope, e);
            }
        });
    }

    private ExecutorService resolveOutputLaneExecutor(Envelope envelope) {
        ExecutorService[] laneExecutors = outputLaneExecutors;
        if (laneExecutors == null || laneExecutors.length == 0) {
            throw new IllegalStateException("Output lane executors are not initialized");
        }
        int index = Math.floorMod(outputLaneKey(envelope).hashCode(), laneExecutors.length);
        return laneExecutors[index];
    }

    private String outputLaneKey(Envelope envelope) {
        // Preserve per-endpoint ordering on the current adapter. eventCode may be
        // present on the envelope as capability metadata, but connection routing
        // and lane partitioning still key off workerId + connRole.
        String workerId = envelope != null ? envelope.getWorkerId() : null;
        String connRole = envelope != null ? envelope.getConnRole() : null;
        return Objects.toString(workerId, "_") + "::" + Objects.toString(connRole, "_");
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

    private void shutdownExecutors(ExecutorService[] executors, String namePrefix) throws InterruptedException {
        if (executors == null) {
            return;
        }
        for (int i = 0; i < executors.length; i++) {
            shutdownExecutor(executors[i], namePrefix + "-" + i);
        }
    }
}
