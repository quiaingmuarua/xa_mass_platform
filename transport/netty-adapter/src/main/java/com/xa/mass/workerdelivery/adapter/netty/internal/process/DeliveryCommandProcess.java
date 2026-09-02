package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryCommandRemoteApi;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Resident Command acquisition and delivery process for one Adapter. */
public final class DeliveryCommandProcess {

    private static final String WORKER_DELIVERY_EXPIRED_EVENT =
            "platform.adapter.worker-delivery.expired";
    private static final String WORKER_SERVICEABILITY_EVIDENCE_FORWARD =
            "worker-serviceability-evidence:v1";

    private record QueuedCommand(
            String entryKey,
            DeliveryCommand command
    ) {}

    private static final System.Logger LOGGER = System.getLogger(
            DeliveryCommandProcess.class.getName()
    );

    private final ArrayDeque<QueuedCommand> retryQueue = new ArrayDeque<>();
    private final int retryQueueCapacity;
    private final DeliveryCommandRemoteApi remoteApi;
    private final WorkerConnectionMechanism connectionMechanism;
    private final DeliveryReportProcess reportProcess;
    private final WorkerDeliveryCodec codec;
    private final AdapterEventDispatcher adapterEventDispatcher;
    private final String adapterId;
    private final int commandConsumeLimit;
    private final long backoffMillis;
    private final LongSupplier nowMillis;
    private volatile boolean loopStopped;

    public DeliveryCommandProcess(
            DeliveryCommandRemoteApi remoteApi,
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            DeliveryReportProcess reportProcess,
            WorkerDeliveryCodec codec,
            String adapterId,
            int commandConsumeLimit,
            int retryQueueCapacity
    ) {
        this(
                remoteApi,
                connectionMechanism,
                adapterEventDispatcher,
                reportProcess,
                codec,
                adapterId,
                commandConsumeLimit,
                retryQueueCapacity,
                Duration.ofMillis(100),
                System::currentTimeMillis
        );
    }

    public DeliveryCommandProcess(
            DeliveryCommandRemoteApi remoteApi,
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            DeliveryReportProcess reportProcess,
            WorkerDeliveryCodec codec,
            String adapterId,
            int commandConsumeLimit,
            int retryQueueCapacity,
            Duration backoff
    ) {
        this(
                remoteApi,
                connectionMechanism,
                adapterEventDispatcher,
                reportProcess,
                codec,
                adapterId,
                commandConsumeLimit,
                retryQueueCapacity,
                backoff,
                System::currentTimeMillis
        );
    }

    DeliveryCommandProcess(
            DeliveryCommandRemoteApi remoteApi,
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            DeliveryReportProcess reportProcess,
            WorkerDeliveryCodec codec,
            String adapterId,
            int commandConsumeLimit,
            int retryQueueCapacity,
            LongSupplier nowMillis
    ) {
        this(
                remoteApi,
                connectionMechanism,
                adapterEventDispatcher,
                reportProcess,
                codec,
                adapterId,
                commandConsumeLimit,
                retryQueueCapacity,
                Duration.ofMillis(100),
                nowMillis
        );
    }

    DeliveryCommandProcess(
            DeliveryCommandRemoteApi remoteApi,
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            DeliveryReportProcess reportProcess,
            WorkerDeliveryCodec codec,
            String adapterId,
            int commandConsumeLimit,
            int retryQueueCapacity,
            Duration backoff,
            LongSupplier nowMillis
    ) {
        this.remoteApi = Objects.requireNonNull(remoteApi, "remoteApi");
        this.connectionMechanism = Objects.requireNonNull(
                connectionMechanism,
                "connectionMechanism"
        );
        this.adapterEventDispatcher = Objects.requireNonNull(
                adapterEventDispatcher,
                "adapterEventDispatcher"
        );
        this.reportProcess = Objects.requireNonNull(
                reportProcess,
                "reportProcess"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (commandConsumeLimit <= 0
                || retryQueueCapacity < commandConsumeLimit) {
            throw new IllegalArgumentException(
                    "commandConsumeLimit must be between 1 and command "
                            + "retry queue capacity"
            );
        }
        this.adapterId = adapterId;
        this.commandConsumeLimit = commandConsumeLimit;
        this.retryQueueCapacity = retryQueueCapacity;
        backoffMillis = requirePositiveMillis(
                backoff,
                "backoff"
        );
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    public void runLoop() {
        try {
            while (!loopStopped
                    && !Thread.currentThread().isInterrupted()) {
                boolean backoff = true;
                try {
                    dispatchBatch(consumeRetryBatch());
                    if (loopStopped) {
                        return;
                    }
                    List<QueuedCommand> fresh = acquireFreshBatch();
                    if (!fresh.isEmpty()) {
                        dispatchBatch(fresh);
                        backoff = false;
                    }
                } catch (RuntimeException error) {
                    if (!loopStopped
                            && !Thread.currentThread().isInterrupted()) {
                        logIterationFailure(error);
                    }
                }
                if (backoff && !awaitBackoff()) {
                    return;
                }
            }
        } finally {
            retryQueue.clear();
        }
    }

    private void dispatchBatch(List<QueuedCommand> observed) {
        if (observed.isEmpty()) {
            return;
        }
        long currentTimeMillis = nowMillis.getAsLong();
        ArrayList<QueuedCommand> retryLater = new ArrayList<>();
        for (QueuedCommand queued : observed) {
            if (loopStopped) {
                return;
            }
            DeliveryCommand command = queued.command();
            if (command.executeBeforeMillis() <= currentTimeMillis) {
                if (isTaskWorkerCommand(queued)) {
                    offerExpiredTaskResult(queued, currentTimeMillis);
                }
                continue;
            }
            if (dispatch(queued) == RETRY_LATER) {
                retryLater.add(queued);
            }
        }
        if (!loopStopped && !retryLater.isEmpty()
                && !offerRetryBatch(retryLater)) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} commandCount={1} message={2}",
                    adapterId,
                    retryLater.size(),
                    "Retryable Adapter Commands were dropped"
            );
        }
    }

    private List<QueuedCommand> acquireFreshBatch() {
        Map<String, DeliveryCommand> acquired = remoteApi.consume(
                adapterId,
                commandConsumeLimit
        );
        if (acquired.isEmpty() || loopStopped) {
            return List.of();
        }
        ArrayList<QueuedCommand> batch = new ArrayList<>(acquired.size());
        acquired.forEach((entryKey, command) -> batch.add(
                new QueuedCommand(entryKey, command)
        ));
        return List.copyOf(batch);
    }

    public void stop() {
        loopStopped = true;
    }

    private List<QueuedCommand> consumeRetryBatch() {
        int count = Math.min(commandConsumeLimit, retryQueue.size());
        if (count == 0) {
            return List.of();
        }
        ArrayList<QueuedCommand> consumed = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            consumed.add(retryQueue.removeFirst());
        }
        return List.copyOf(consumed);
    }

    private boolean offerRetryBatch(List<QueuedCommand> retryLater) {
        if (retryQueue.size() >= retryQueueCapacity) {
            return false;
        }
        retryQueue.addAll(retryLater);
        return true;
    }

    private WorkerConnectionMechanism.DeliveryAttempt dispatch(
            QueuedCommand queued
    ) {
        DeliveryCommand command = queued.command();
        if (command.dst() == ADAPTER) {
            offerAdapterEventResult(adapterEventDispatcher.dispatch(command));
            return WorkerConnectionMechanism.DeliveryAttempt.STARTED;
        }
        if (isTaskWorkerCommand(queued)
                || isSystemWorkerCommand(queued)) {
            return connectionMechanism.deliver(queued.entryKey(), command);
        }
        logInvalidTarget(queued.entryKey(), command);
        return WorkerConnectionMechanism.DeliveryAttempt.UNKNOWN;
    }

    private void offerExpiredTaskResult(
            QueuedCommand queued,
            long observedAtMillis
    ) {
        DeliveryReport rejection = DeliveryReport.fromCommand(
                queued.command(),
                ADAPTER,
                adapterId,
                Integer.toString(
                        WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED.code()
                ),
                "null"
        );
        DeliveryReport evidence = DeliveryReport.create(
                ADAPTER,
                adapterId,
                KERNEL,
                WORKER_DELIVERY_EXPIRED_EVENT,
                "200",
                Jsons.toJson(Map.of(
                        "workerId", queued.entryKey(),
                        "observedAtMillis", observedAtMillis
                )),
                WORKER_SERVICEABILITY_EVIDENCE_FORWARD
        );
        if (reportProcess.ingress(List.of(
                codec.encodeDeliveryReport(rejection),
                codec.encodeDeliveryReport(evidence)
        )) != DeliveryReportProcess.ReportIngressStatus.ACCEPTED) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} target={1} message={2}",
                    adapterId,
                    queued.entryKey(),
                    "Adapter rejection and serviceability evidence were dropped"
            );
        }
    }

    private void offerAdapterEventResult(DeliveryReport report) {
        if (reportProcess.ingress(List.of(
                codec.encodeDeliveryReport(report)
        )) != DeliveryReportProcess.ReportIngressStatus.ACCEPTED) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} messageType={1} message={2}",
                    adapterId,
                    report.messageType(),
                    "Adapter Event Result was dropped"
            );
        }
    }

    private static boolean isTaskWorkerCommand(QueuedCommand queued) {
        return queued.command().src() == TASK
                && queued.command().dst() == WORKER;
    }

    private static boolean isSystemWorkerCommand(QueuedCommand queued) {
        return queued.command().src() == SYSTEM
                && queued.command().dst() == WORKER;
    }

    private void logInvalidTarget(
            String entryKey,
            DeliveryCommand command
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} target={3} "
                        + "messageType={4}",
                (command.src() == SYSTEM
                        ? WorkerDeliveryAdapterErrorCode
                        .ADAPTER_COMMAND_INVALID
                        : WorkerDeliveryAdapterErrorCode
                        .WORKER_MESSAGE_INVALID).code(),
                "deliveryCommand.validateTarget",
                adapterId,
                entryKey,
                command.messageType()
        );
    }

    private void logIterationFailure(RuntimeException error) {
        WorkerDeliveryAdapterException failure = error
                instanceof WorkerDeliveryAdapterException classified
                ? classified
                : new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                        "deliveryCommand.runIteration",
                        "Delivery Command iteration failed",
                        error
                );
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} message={3}",
                failure.errorCode().code(),
                failure.operation(),
                adapterId,
                failure.getMessage()
        );
    }

    private boolean awaitBackoff() {
        if (loopStopped || Thread.currentThread().isInterrupted()) {
            return false;
        }
        try {
            Thread.sleep(backoffMillis);
            return !loopStopped;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long requirePositiveMillis(
            Duration value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.toMillis();
    }
}
