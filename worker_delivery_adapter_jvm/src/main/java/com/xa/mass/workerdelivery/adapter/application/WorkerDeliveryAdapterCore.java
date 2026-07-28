package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.WORKER_FAILURE;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.LongSupplier;

public final class WorkerDeliveryAdapterCore {

    private static final String UNAVAILABLE_WORKER_OUTCOME_CODE = "3001";
    private final WorkerDeliveryGatewayClient gateway;
    private final WorkerDeliveryCodec codec;
    private final WorkerConnectionRegistry connections;
    private final String endpointManagerId;
    private final int scanCount;
    private final int resultBatchSize;
    private final LongSupplier nowMillis;
    private final ArrayBlockingQueue<SeedResult> resultBuffer;
    private String cursor;
    private List<SeedResult> pendingResults;
    private boolean closed;

    public WorkerDeliveryAdapterCore(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerConnectionRegistry connections,
            String endpointManagerId,
            int scanCount,
            int resultBatchSize,
            int resultBufferCapacity
    ) {
        this(
                gateway,
                codec,
                connections,
                endpointManagerId,
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
            String endpointManagerId,
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
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        if (scanCount <= 0
                || resultBatchSize <= 0
                || resultBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        this.endpointManagerId = endpointManagerId;
        this.scanCount = scanCount;
        this.resultBatchSize = resultBatchSize;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        resultBuffer = new ArrayBlockingQueue<>(resultBufferCapacity);
    }

    public synchronized boolean connectWorker(
            String workerId,
            WorkerConnection connection
    ) {
        if (closed) {
            return false;
        }
        connections.bind(workerId, connection);
        return true;
    }

    public void disconnectWorker(
            String workerId,
            WorkerConnection connection
    ) {
        connections.unbind(workerId, connection);
    }

    public synchronized WorkerResultAcceptance acceptWorkerResult(
            SeedResult result
    ) {
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

    synchronized AdapterRoundResult dispatchOnce() {
        requireOpen();
        flushPendingResults();
        flushBufferedResults();

        WorkerCommandPage page = gateway.consumeWorkerCommands(
                endpointManagerId,
                cursor,
                scanCount
        );
        cursor = page.nextCursor();

        int deliveredCount = 0;
        int rejectedCount = 0;
        int unknownCount = 0;
        int expiredCount = 0;
        int invalidCount = 0;
        List<SeedResult> adapterRejections = new ArrayList<>();
        long currentTimeMillis = nowMillis.getAsLong();
        for (Map.Entry<String, WorkerCommandEnvelope> entry
                : page.workerCommandsByWorkerId().entrySet()) {
            String workerId = entry.getKey();
            WorkerCommandEnvelope command = entry.getValue();
            if (command.executeBeforeMillis() <= currentTimeMillis) {
                expiredCount++;
                continue;
            }
            CommandDeliveryAttempt attempt = connections.deliver(
                    workerId,
                    command
            );
            if (attempt == DELIVERED) {
                deliveredCount++;
            } else if (attempt == REJECTED_BEFORE_SEND) {
                SeedResult rejection = createAdapterRejection(
                        workerId,
                        command
                );
                if (rejection == null) {
                    invalidCount++;
                } else {
                    adapterRejections.add(rejection);
                    rejectedCount++;
                }
            } else {
                unknownCount++;
            }
        }
        if (!adapterRejections.isEmpty()) {
            appendOrRetain(adapterRejections);
        }
        return new AdapterRoundResult(
                page.workerCommandsByWorkerId().size(),
                deliveredCount,
                rejectedCount,
                unknownCount,
                expiredCount,
                invalidCount
        );
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        connections.closeAll(ADAPTER_STOPPING);
        flushPendingResults();
        flushBufferedResults();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Worker Delivery Adapter core is closed"
            );
        }
    }

    private void flushPendingResults() {
        if (pendingResults == null) {
            return;
        }
        gateway.appendResults(endpointManagerId, pendingResults);
        pendingResults = null;
    }

    private void flushBufferedResults() {
        List<SeedResult> batch = new ArrayList<>(resultBatchSize);
        resultBuffer.drainTo(batch, resultBatchSize);
        if (!batch.isEmpty()) {
            appendOrRetain(batch);
        }
    }

    private void appendOrRetain(List<SeedResult> results) {
        List<SeedResult> batch = List.copyOf(results);
        try {
            gateway.appendResults(endpointManagerId, batch);
        } catch (RuntimeException error) {
            pendingResults = batch;
            throw error;
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

    public enum WorkerResultAcceptance {
        ACCEPTED,
        INVALID_OUTCOME,
        BUFFER_FULL,
        ADAPTER_CLOSED
    }

    public record AdapterRoundResult(
            int consumedCount,
            int deliveredCount,
            int rejectedCount,
            int unknownCount,
            int expiredCount,
            int invalidCount
    ) {
    }
}
