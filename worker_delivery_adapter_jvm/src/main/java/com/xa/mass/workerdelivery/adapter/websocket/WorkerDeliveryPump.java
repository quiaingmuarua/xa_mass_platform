package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.application.WorkerCommandPage;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerSessionRegistry.DeliveryAttempt;
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
        prefix = "xa.mass.worker-delivery.adapter.websocket",
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
    private final WorkerDeliveryGatewayClient gateway;
    private final WorkerDeliveryCodec codec;
    private final WorkerSessionRegistry sessions;
    private final WorkerWebSocketProperties properties;
    private final LongSupplier nowMillis;
    private final ArrayBlockingQueue<SeedResult> resultBuffer;
    private volatile boolean running;
    private ScheduledExecutorService executor;
    private String cursor;
    private List<SeedResult> pendingResults;

    @Autowired
    public WorkerDeliveryPump(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerSessionRegistry sessions,
            WorkerWebSocketProperties properties
    ) {
        this(
                gateway,
                codec,
                sessions,
                properties,
                System::currentTimeMillis
        );
    }

    WorkerDeliveryPump(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerSessionRegistry sessions,
            WorkerWebSocketProperties properties,
            LongSupplier nowMillis
    ) {
        this.gateway = gateway;
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

        WorkerCommandPage page = gateway.consumeWorkerCommands(
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
            appendOrRetain(createAdapterRejections(rejected));
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
            appendResults(List.copyOf(batch));
            return true;
        } catch (RuntimeException error) {
            pendingResults = List.copyOf(batch);
            return false;
        }
    }

    private void appendOrRetain(List<SeedResult> results) {
        if (results.isEmpty()) {
            return;
        }
        List<SeedResult> batch = List.copyOf(results);
        try {
            appendResults(batch);
        } catch (RuntimeException error) {
            pendingResults = batch;
        }
    }

    private void appendResults(List<SeedResult> batch) {
        gateway.appendResults(
                properties.endpointManagerId(),
                batch
        );
    }

    private List<SeedResult> createAdapterRejections(
            Map<String, WorkerCommandEnvelope> commandsByWorkerId
    ) {
        List<SeedResult> results = new ArrayList<>();
        commandsByWorkerId.forEach((workerId, command) -> {
            DeliverSeed seed = codec.decodeDeliverSeed(command.opaqueItem());
            if (seed == null || !workerId.equals(seed.workerId())) {
                LOGGER.warn(
                        "Dropped invalid Adapter rejection commandId={} workerId={}",
                        command.commandId(),
                        workerId
                );
                return;
            }
            results.add(new SeedResult(
                    command.commandId(),
                    seed.opaqueResultContext(),
                    UNAVAILABLE_WORKER_OUTCOME_CODE,
                    null
            ));
        });
        return List.copyOf(results);
    }
}
