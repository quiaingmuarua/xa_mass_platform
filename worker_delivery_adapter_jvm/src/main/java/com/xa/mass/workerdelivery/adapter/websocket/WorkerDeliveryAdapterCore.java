package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.WORKER_FAILURE;

import com.xa.mass.workerdelivery.adapter.application.WorkerCommandPage;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.netty.channel.Channel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

final class WorkerDeliveryAdapterCore {

    private static final String UNAVAILABLE_WORKER_OUTCOME_CODE = "3001";

    private final WorkerDeliveryGatewayClient gateway;
    private final WorkerDeliveryCodec codec;
    private final WorkerConnectionRegistry connections;
    private final String adapterId;
    private final int scanCount;
    private final int resultBatchSize;
    private final LongSupplier nowMillis;
    private final ArrayBlockingQueue<SeedResult> resultBuffer;
    private final ArrayDeque<SeedResult> pendingResults =
            new ArrayDeque<>();
    private final ReentrantLock roundLock = new ReentrantLock();
    private volatile boolean closed;
    private String cursor;

    WorkerDeliveryAdapterCore(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerConnectionRegistry connections,
            String adapterId,
            int scanCount,
            int resultBatchSize,
            int resultBufferCapacity
    ) {
        this(
                gateway,
                codec,
                connections,
                adapterId,
                scanCount,
                resultBatchSize,
                resultBufferCapacity,
                System::currentTimeMillis
        );
    }

    WorkerDeliveryAdapterCore(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerConnectionRegistry connections,
            String adapterId,
            int scanCount,
            int resultBatchSize,
            int resultBufferCapacity,
            LongSupplier nowMillis
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.connections = Objects.requireNonNull(
                connections,
                "connections"
        );
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException(
                    "adapterId must be non-blank"
            );
        }
        if (scanCount <= 0
                || resultBatchSize <= 0
                || resultBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        this.adapterId = adapterId;
        this.scanCount = scanCount;
        this.resultBatchSize = resultBatchSize;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        resultBuffer = new ArrayBlockingQueue<>(resultBufferCapacity);
    }

    boolean connectWorker(
            String workerId,
            Channel channel
    ) {
        if (closed) {
            return false;
        }
        connections.bind(workerId, channel);
        if (closed) {
            connections.close(
                    workerId,
                    channel,
                    ADAPTER_STOPPING
            );
            return false;
        }
        return true;
    }

    void disconnectWorker(
            String workerId,
            Channel channel
    ) {
        connections.unbind(workerId, channel);
    }

    void closeWorker(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    ) {
        connections.close(workerId, channel, reason);
    }

    WorkerResultAcceptance acceptWorkerResult(SeedResult result) {
        Objects.requireNonNull(result, "result");
        if (closed) {
            return WorkerResultAcceptance.ADAPTER_CLOSED;
        }
        var outcomeClass = WorkerDeliveryProtocol.classifyOutcomeCode(
                result.outcomeCode()
        );
        if (outcomeClass != SUCCESS && outcomeClass != WORKER_FAILURE) {
            return WorkerResultAcceptance.INVALID_OUTCOME;
        }
        if (!resultBuffer.offer(result)) {
            return WorkerResultAcceptance.BUFFER_FULL;
        }
        return WorkerResultAcceptance.ACCEPTED;
    }

    void dispatchOnce(ExecutorService deliveryExecutor) {
        Objects.requireNonNull(deliveryExecutor, "deliveryExecutor");
        roundLock.lock();
        try {
            requireOpen();
            flushPendingResults();
            flushOneBufferedResultBatch();

            WorkerCommandPage page = gateway.consumeWorkerCommands(
                    adapterId,
                    cursor,
                    scanCount
            );
            cursor = page.nextCursor();
            deliverPage(
                    page.workerCommandsByWorkerId(),
                    deliveryExecutor
            );
            flushPendingResults();
        } finally {
            roundLock.unlock();
        }
    }

    void close() {
        roundLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            connections.closeAll(ADAPTER_STOPPING);
            flushPendingResults();
            while (!resultBuffer.isEmpty()) {
                drainOneBufferedResultBatch();
                flushPendingResults();
            }
        } finally {
            roundLock.unlock();
        }
    }

    private void deliverPage(
            Map<String, WorkerCommandEnvelope> commands,
            ExecutorService deliveryExecutor
    ) {
        long currentTimeMillis = nowMillis.getAsLong();
        List<Future<DeliveryOutcome>> deliveries = new ArrayList<>();
        for (Map.Entry<String, WorkerCommandEnvelope> entry
                : commands.entrySet()) {
            String workerId = entry.getKey();
            WorkerCommandEnvelope command = entry.getValue();
            if (command == null
                    || command.executeBeforeMillis()
                    <= currentTimeMillis) {
                continue;
            }
            deliveries.add(deliveryExecutor.submit(() ->
                    new DeliveryOutcome(
                            workerId,
                            command,
                            connections.deliver(workerId, command)
                    )
            ));
        }

        for (Future<DeliveryOutcome> delivery : deliveries) {
            DeliveryOutcome outcome;
            try {
                outcome = delivery.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new WorkerDeliveryAdapterException(
                        "Worker command delivery was interrupted",
                        error
                );
            } catch (ExecutionException error) {
                continue;
            }
            if (outcome.attempt() != REJECTED_BEFORE_SEND) {
                continue;
            }
            SeedResult rejection = createAdapterRejection(
                    outcome.workerId(),
                    outcome.command()
            );
            if (rejection != null) {
                pendingResults.addLast(rejection);
            }
        }
    }

    private void flushOneBufferedResultBatch() {
        drainOneBufferedResultBatch();
        flushPendingResults();
    }

    private void drainOneBufferedResultBatch() {
        List<SeedResult> batch = new ArrayList<>(resultBatchSize);
        resultBuffer.drainTo(batch, resultBatchSize);
        pendingResults.addAll(batch);
    }

    private void flushPendingResults() {
        while (!pendingResults.isEmpty()) {
            List<SeedResult> batch = new ArrayList<>(resultBatchSize);
            var iterator = pendingResults.iterator();
            while (iterator.hasNext()
                    && batch.size() < resultBatchSize) {
                batch.add(iterator.next());
            }
            gateway.appendResults(adapterId, List.copyOf(batch));
            for (int index = 0; index < batch.size(); index++) {
                pendingResults.removeFirst();
            }
        }
    }

    private SeedResult createAdapterRejection(
            String workerId,
            WorkerCommandEnvelope command
    ) {
        DeliverSeed seed = codec.decodeDeliverSeed(command.opaqueItem());
        if (seed == null || !workerId.equals(seed.workerId())) {
            return null;
        }
        return new SeedResult(
                command.commandId(),
                seed.opaqueResultContext(),
                UNAVAILABLE_WORKER_OUTCOME_CODE,
                null
        );
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Worker Delivery Adapter core is closed"
            );
        }
    }

    enum WorkerResultAcceptance {
        ACCEPTED,
        INVALID_OUTCOME,
        BUFFER_FULL,
        ADAPTER_CLOSED
    }

    private record DeliveryOutcome(
            String workerId,
            WorkerCommandEnvelope command,
            CommandDeliveryAttempt attempt
    ) {
    }
}
