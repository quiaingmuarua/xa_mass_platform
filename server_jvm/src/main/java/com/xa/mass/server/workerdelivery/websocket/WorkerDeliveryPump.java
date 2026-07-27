package com.xa.mass.server.workerdelivery.websocket;

import com.xa.mass.server.workerdelivery.WorkerDeliveryService;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.websocket.WorkerSessionRegistry.DeliveryAttempt;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

@Component
@ConditionalOnProperty(
        prefix = "xa.mass.worker-delivery.websocket",
        name = "enabled",
        havingValue = "true"
)
public final class WorkerDeliveryPump implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WorkerDeliveryPump.class
    );
    private static final String UNAVAILABLE_WORKER_OUTCOME_CODE = "3001";
    private static final CloseStatus RESULT_BUFFER_FULL = new CloseStatus(
            1013,
            "Worker result buffer is full"
    );
    private final WorkerDeliveryService service;
    private final WorkerDeliveryCodec codec;
    private final WorkerSessionRegistry sessions;
    private final WorkerWebSocketProperties properties;
    private final LongSupplier nowMillis;
    private final ArrayBlockingQueue<SeedResult> resultBuffer;
    private volatile boolean running;
    private ScheduledExecutorService executor;
    private String cursor;
    private PendingResultBatch pendingResults;

    @Autowired
    public WorkerDeliveryPump(
            WorkerDeliveryService service,
            WorkerDeliveryCodec codec,
            WorkerSessionRegistry sessions,
            WorkerWebSocketProperties properties
    ) {
        this(
                service,
                codec,
                sessions,
                properties,
                System::currentTimeMillis
        );
    }

    WorkerDeliveryPump(
            WorkerDeliveryService service,
            WorkerDeliveryCodec codec,
            WorkerSessionRegistry sessions,
            WorkerWebSocketProperties properties,
            LongSupplier nowMillis
    ) {
        this.service = service;
        this.codec = codec;
        this.sessions = sessions;
        this.properties = properties;
        this.nowMillis = nowMillis;
        resultBuffer = new ArrayBlockingQueue<>(
                properties.resultBufferCapacity()
        );
    }

    public boolean acceptWorkerResult(SeedResult result) {
        return resultBuffer.offer(result);
    }

    public void closeForResultOverflow(String workerId, long generation) {
        sessions.close(workerId, generation, RESULT_BUFFER_FULL);
    }

    synchronized void runOnce() {
        if (!flushPendingResults() || !flushBufferedResults()) {
            return;
        }

        var page = service.consumeWorkerCommands(
                properties.endpointManagerId(),
                cursor,
                properties.scanCount()
        );
        cursor = page.nextCursor();
        Map<String, WorkerCommandEnvelope> rejected = new LinkedHashMap<>();
        long currentTimeMillis = nowMillis.getAsLong();
        page.workerCommandsByWorkerId().forEach((workerId, command) -> {
            if (command.executeBeforeMillis() <= currentTimeMillis) {
                return;
            }
            DeliveryAttempt attempt = sessions.send(
                    workerId,
                    codec.encodeWorkerCommand(command)
            );
            if (attempt == DeliveryAttempt.REJECTED_BEFORE_SEND) {
                rejected.put(workerId, command);
            } else if (attempt == DeliveryAttempt.UNKNOWN) {
                LOGGER.warn(
                        "Worker command delivery became unknown commandId={} workerId={}",
                        command.commandId(),
                        workerId
                );
            }
        });
        if (!rejected.isEmpty()) {
            List<SeedResult> rejections = service.createAdapterRejections(
                    properties.endpointManagerId(),
                    rejected,
                    UNAVAILABLE_WORKER_OUTCOME_CODE
            );
            appendOrRetain(rejections, ResultSource.ADAPTER);
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Duration interval = properties.pumpInterval();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-delivery-websocket-pump"
            );
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        executor.scheduleWithFixedDelay(
                this::runSafely,
                0,
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdownNow();
        try {
            flushPendingResults();
            flushBufferedResults();
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Worker result shutdown flush failed: {}",
                    error.getMessage()
            );
        }
        sessions.closeAll(CloseStatus.GOING_AWAY);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void runSafely() {
        if (!running) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Worker Delivery WebSocket pump round failed: {}",
                    error.getMessage()
            );
        }
    }

    private boolean flushPendingResults() {
        if (pendingResults == null) {
            return true;
        }
        appendResults(pendingResults);
        pendingResults = null;
        return true;
    }

    private boolean flushBufferedResults() {
        List<SeedResult> batch = new ArrayList<>(
                properties.resultBatchSize()
        );
        resultBuffer.drainTo(batch, properties.resultBatchSize());
        if (batch.isEmpty()) {
            return true;
        }
        try {
            appendResults(new PendingResultBatch(
                    List.copyOf(batch),
                    ResultSource.WORKER
            ));
            return true;
        } catch (RuntimeException error) {
            pendingResults = new PendingResultBatch(
                    List.copyOf(batch),
                    ResultSource.WORKER
            );
            return false;
        }
    }

    private void appendOrRetain(
            List<SeedResult> results,
            ResultSource source
    ) {
        if (results.isEmpty()) {
            return;
        }
        PendingResultBatch batch = new PendingResultBatch(
                List.copyOf(results),
                source
        );
        try {
            appendResults(batch);
        } catch (RuntimeException error) {
            pendingResults = batch;
        }
    }

    private void appendResults(PendingResultBatch batch) {
        if (batch.source() == ResultSource.WORKER) {
            service.appendWorkerResults(
                    properties.endpointManagerId(),
                    batch.results()
            );
            return;
        }
        service.appendAdapterResults(
                properties.endpointManagerId(),
                batch.results()
        );
    }

    private enum ResultSource {
        WORKER,
        ADAPTER
    }

    private record PendingResultBatch(
            List<SeedResult> results,
            ResultSource source
    ) {
    }
}
