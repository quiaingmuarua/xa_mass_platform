package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.model.WorkerTransportMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebSocketMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageDispatcher.class);
    private static final int INPUT_LOOP_THREADS = 8;
    private static final int OUTPUT_LANE_THREADS = 8;
    private final WebSocketDispatchRuntimeContext context;
    private final WebSocketInputProcessor inputProcessor;
    private final WebSocketOutputProcessor outputProcessor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService inputExecutor;
    private ExecutorService outputPollerExecutor;
    private ExecutorService[] outputLaneExecutors;

    public WebSocketMessageDispatcher(WebSocketDispatchRuntimeContext context) {
        this.context = context;
        this.inputProcessor = new WebSocketInputProcessor(context);
        this.outputProcessor = new WebSocketOutputProcessor(context);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
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
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                shutdownExecutor(inputExecutor);
                shutdownExecutor(outputPollerExecutor);
                if (outputLaneExecutors != null) {
                    for (ExecutorService executor : outputLaneExecutors) {
                        shutdownExecutor(executor);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void processInputQueueLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            String rawJson = null;
            try {
                rawJson = context.getMessageTransporter().receiveInput(15, TimeUnit.SECONDS);
                if (rawJson != null) {
                    inputProcessor.process(rawJson);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logLoopFailure("input", rawJson, null, e);
            }
        }
    }

    private void processOutputQueueLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            WorkerTransportMessage delivery = null;
            try {
                delivery = context.getMessageTransporter().receiveOutput(15, TimeUnit.SECONDS);
                if (delivery != null) {
                    submitOutputDelivery(delivery);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logLoopFailure("output", null, delivery, e);
            }
        }
    }

    private void submitOutputDelivery(WorkerTransportMessage delivery) {
        ExecutorService laneExecutor = resolveOutputLaneExecutor(delivery);
        laneExecutor.submit(() -> {
            try {
                outputProcessor.process(delivery);
            } catch (Exception e) {
                logLoopFailure("output-lane", null, delivery, e);
            }
        });
    }

    private ExecutorService resolveOutputLaneExecutor(WorkerTransportMessage delivery) {
        ExecutorService[] laneExecutors = outputLaneExecutors;
        if (laneExecutors == null || laneExecutors.length == 0) {
            throw new IllegalStateException("Output lane executors are not initialized");
        }
        int index = Math.floorMod(outputLaneKey(delivery).hashCode(), laneExecutors.length);
        return laneExecutors[index];
    }

    private String outputLaneKey(WorkerTransportMessage delivery) {
        String workerId = delivery != null ? delivery.getWorkerId() : null;
        return Objects.toString(workerId, "_");
    }

    private void logLoopFailure(String loopName, String rawJson, WorkerTransportMessage delivery, Exception e) {
        logger.error("WebSocket dispatcher {} loop failed: rawJsonPresent={}, workerId={}",
                loopName,
                rawJson != null,
                delivery != null ? delivery.getWorkerId() : null,
                e);
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
