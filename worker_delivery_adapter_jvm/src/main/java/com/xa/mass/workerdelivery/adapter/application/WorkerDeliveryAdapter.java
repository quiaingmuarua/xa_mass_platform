package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.ADAPTER_STOPPING;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.RESULT_BUFFER_FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.WORKER_FAILURE;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerSessionDirectory.WorkerSessionToken;
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

public final class WorkerDeliveryAdapter implements AutoCloseable {

    private static final String UNAVAILABLE_WORKER_OUTCOME_CODE = "3001";
    private final WorkerDeliveryGatewayClient gateway;
    private final WorkerDeliveryCodec codec;
    private final WorkerSessionDirectory sessions;
    private final Config config;
    private final LongSupplier nowMillis;
    private final ArrayBlockingQueue<SeedResult> resultBuffer;
    private String cursor;
    private List<SeedResult> pendingResults;

    public WorkerDeliveryAdapter(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerSessionDirectory sessions,
            Config config
    ) {
        this(gateway, codec, sessions, config, System::currentTimeMillis);
    }

    WorkerDeliveryAdapter(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerSessionDirectory sessions,
            Config config,
            LongSupplier nowMillis
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.config = Objects.requireNonNull(config, "config");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        resultBuffer = new ArrayBlockingQueue<>(
                config.resultBufferCapacity()
        );
    }

    public WorkerSessionToken connectWorker(
            String workerId,
            WorkerConnection connection
    ) {
        return sessions.bind(workerId, connection);
    }

    public void disconnectWorker(WorkerSessionToken token) {
        sessions.unbind(token);
    }

    public WorkerResultAcceptance acceptWorkerResult(
            WorkerSessionToken token,
            SeedResult result
    ) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(result, "result");
        if (!sessions.isCurrent(token)) {
            return WorkerResultAcceptance.STALE_SESSION;
        }
        var outcomeClass = WorkerDeliveryProtocol.classifyOutcomeCode(
                result.outcomeCode()
        );
        if (outcomeClass != SUCCESS && outcomeClass != WORKER_FAILURE) {
            return WorkerResultAcceptance.INVALID_OUTCOME;
        }
        if (!resultBuffer.offer(result)) {
            sessions.close(token, RESULT_BUFFER_FULL);
            return WorkerResultAcceptance.BUFFER_FULL;
        }
        return WorkerResultAcceptance.ACCEPTED;
    }

    public synchronized AdapterRoundResult dispatchOnce() {
        flushPendingResults();
        flushBufferedResults();

        WorkerCommandPage page = gateway.consumeWorkerCommands(
                config.endpointManagerId(),
                cursor,
                config.scanCount()
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
            CommandDeliveryAttempt attempt = sessions.deliver(
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

    @Override
    public synchronized void close() {
        try {
            flushPendingResults();
            flushBufferedResults();
        } finally {
            sessions.closeAll(ADAPTER_STOPPING);
        }
    }

    private void flushPendingResults() {
        if (pendingResults == null) {
            return;
        }
        gateway.appendResults(
                config.endpointManagerId(),
                pendingResults
        );
        pendingResults = null;
    }

    private void flushBufferedResults() {
        List<SeedResult> batch = new ArrayList<>(
                config.resultBatchSize()
        );
        resultBuffer.drainTo(batch, config.resultBatchSize());
        if (!batch.isEmpty()) {
            appendOrRetain(batch);
        }
    }

    private void appendOrRetain(List<SeedResult> results) {
        List<SeedResult> batch = List.copyOf(results);
        try {
            gateway.appendResults(
                    config.endpointManagerId(),
                    batch
            );
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

    public record Config(
            String endpointManagerId,
            int scanCount,
            int resultBatchSize,
            int resultBufferCapacity
    ) {
        public Config {
            if (endpointManagerId == null
                    || endpointManagerId.isBlank()) {
                throw new IllegalArgumentException(
                        "endpointManagerId must be non-blank"
                );
            }
            if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                    endpointManagerId
            )) {
                throw new IllegalArgumentException(
                        "system-polling cannot own a Worker Adapter"
                );
            }
            if (scanCount <= 0
                    || resultBatchSize <= 0
                    || resultBufferCapacity <= 0) {
                throw new IllegalArgumentException(
                        "Adapter bounds must be positive"
                );
            }
        }
    }

    public enum WorkerResultAcceptance {
        ACCEPTED,
        STALE_SESSION,
        INVALID_OUTCOME,
        BUFFER_FULL
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
