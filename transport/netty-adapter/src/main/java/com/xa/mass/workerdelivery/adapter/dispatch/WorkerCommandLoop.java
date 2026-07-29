package com.xa.mass.workerdelivery.adapter.dispatch;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.RETRY_LATER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class WorkerCommandLoop implements Runnable {

    private static final String UNAVAILABLE_WORKER_OUTCOME_CODE = "3001";
    private static final System.Logger LOGGER = System.getLogger(
            WorkerCommandLoop.class.getName()
    );

    private final WorkerDeliveryGatewayClient gateway;
    private final WorkerCommandDelivery commandDelivery;
    private final BoundedWorkerResultQueue resultQueue;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final String adapterId;
    private final int consumeLimit;
    private final int queueCapacity;
    private final LongSupplier nowMillis;
    private final ArrayDeque<QueuedCommand> commands = new ArrayDeque<>();
    private volatile boolean closed;

    public WorkerCommandLoop(
            WorkerDeliveryGatewayClient gateway,
            WorkerCommandDelivery commandDelivery,
            BoundedWorkerResultQueue resultQueue,
            String adapterId,
            int consumeLimit,
            int queueCapacity
    ) {
        this(
                gateway,
                commandDelivery,
                resultQueue,
                adapterId,
                consumeLimit,
                queueCapacity,
                System::currentTimeMillis
        );
    }

    WorkerCommandLoop(
            WorkerDeliveryGatewayClient gateway,
            WorkerCommandDelivery commandDelivery,
            BoundedWorkerResultQueue resultQueue,
            String adapterId,
            int consumeLimit,
            int queueCapacity,
            LongSupplier nowMillis
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.commandDelivery = Objects.requireNonNull(
                commandDelivery,
                "commandDelivery"
        );
        this.resultQueue = Objects.requireNonNull(
                resultQueue,
                "resultQueue"
        );
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException(
                    "adapterId must be non-blank"
            );
        }
        if (consumeLimit <= 0) {
            throw new IllegalArgumentException(
                    "consumeLimit must be positive"
            );
        }
        if (queueCapacity < consumeLimit) {
            throw new IllegalArgumentException(
                    "queueCapacity must be at least consumeLimit"
            );
        }
        this.adapterId = adapterId;
        this.consumeLimit = consumeLimit;
        this.queueCapacity = queueCapacity;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    @Override
    public synchronized void run() {
        if (closed) {
            return;
        }
        refillIfNeeded();
        forwardCurrentQueue();
    }

    public synchronized void close() {
        closed = true;
        commands.clear();
    }

    int queuedCommandCount() {
        return commands.size();
    }

    private void refillIfNeeded() {
        int remainingCapacity = queueCapacity - commands.size();
        if (remainingCapacity < consumeLimit) {
            return;
        }

        Map<String, WorkerCommandEnvelope> acquired;
        try {
            acquired = gateway.consumeWorkerCommands(
                    adapterId,
                    Math.min(consumeLimit, remainingCapacity)
            );
        } catch (RuntimeException error) {
            logGatewayFailure(error);
            return;
        }

        for (Map.Entry<String, WorkerCommandEnvelope> entry
                : acquired.entrySet()) {
            if (commands.size() >= queueCapacity) {
                break;
            }
            commands.addLast(new QueuedCommand(
                    entry.getKey(),
                    entry.getValue()
            ));
        }
    }

    private void forwardCurrentQueue() {
        int observed = commands.size();
        long currentTimeMillis = nowMillis.getAsLong();

        for (int index = 0; index < observed; index++) {
            QueuedCommand queued = commands.removeFirst();
            WorkerCommandEnvelope command = queued.command();
            if (command == null
                    || command.executeBeforeMillis() <= currentTimeMillis) {
                offerAdapterRejection(queued);
                continue;
            }

            var attempt = commandDelivery.deliver(
                    queued.workerId(),
                    command
            );
            if (attempt == RETRY_LATER) {
                commands.addLast(queued);
            }
        }
    }

    private void offerAdapterRejection(QueuedCommand queued) {
        WorkerCommandEnvelope command = queued.command();
        if (command == null) {
            return;
        }
        DeliverSeed seed = codec.decodeDeliverSeed(command.opaqueItem());
        if (seed == null || !queued.workerId().equals(seed.workerId())) {
            return;
        }
        SeedResult rejection = new SeedResult(
                command.commandId(),
                seed.opaqueResultContext(),
                UNAVAILABLE_WORKER_OUTCOME_CODE,
                null
        );
        var status = resultQueue.offer(rejection);
        if (status != BoundedWorkerResultQueue.OfferStatus.ACCEPTED) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} workerId={1} message={2}",
                    adapterId,
                    queued.workerId(),
                    "Adapter rejection result was dropped"
            );
        }
    }

    private void logGatewayFailure(RuntimeException error) {
        WorkerDeliveryAdapterException failure;
        if (error instanceof WorkerDeliveryAdapterException classified) {
            failure = classified;
        } else {
            failure = new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                    "adapter.consumeCommands",
                    "Worker command acquisition failed",
                    error
            );
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} message={3}",
                failure.errorCode().code(),
                failure.operation(),
                adapterId,
                failure.getMessage()
        );
    }

    private record QueuedCommand(
            String workerId,
            WorkerCommandEnvelope command
    ) {
    }
}
