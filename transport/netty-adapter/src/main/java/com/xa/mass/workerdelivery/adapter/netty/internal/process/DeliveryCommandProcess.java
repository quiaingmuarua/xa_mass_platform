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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Scheduled Command acquisition and delivery process for one Adapter. */
public final class DeliveryCommandProcess implements AdapterProcess {

    private static final int MAX_FRESH_BATCHES_PER_ROUND = 4;
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

    private final FiniteQueue<QueuedCommand> retryQueue;
    private final DeliveryCommandRemoteApi remoteApi;
    private final WorkerConnectionMechanism connectionMechanism;
    private final DeliveryReportProcess reportProcess;
    private final WorkerDeliveryCodec codec;
    private final AdapterEventDispatcher adapterEventDispatcher;
    private final String adapterId;
    private final int commandConsumeLimit;
    private final LongSupplier nowMillis;
    private volatile boolean roundsStopped;
    private boolean closeFinished;

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
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        retryQueue = new FiniteQueue<>(retryQueueCapacity);
    }

    @Override
    public void round() {
        if (roundsStopped) {
            return;
        }
        dispatchBatch(retryQueue.consume(commandConsumeLimit));
        if (roundsStopped) {
            return;
        }

        for (int index = 0;
                index < MAX_FRESH_BATCHES_PER_ROUND && !roundsStopped;
                index++) {
            List<QueuedCommand> fresh = acquireFreshBatch();
            if (fresh == null || fresh.isEmpty()) {
                return;
            }
            dispatchBatch(fresh);
            if (fresh.size() < commandConsumeLimit) {
                return;
            }
        }
    }

    private void dispatchBatch(List<QueuedCommand> observed) {
        if (observed.isEmpty()) {
            return;
        }
        long currentTimeMillis = nowMillis.getAsLong();
        ArrayList<QueuedCommand> retryLater = new ArrayList<>();
        for (QueuedCommand queued : observed) {
            if (roundsStopped) {
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
        if (!roundsStopped && !retryLater.isEmpty()
                && retryQueue.ingress(retryLater)
                != FiniteQueue.QueueIngressStatus.ACCEPTED) {
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
        Map<String, DeliveryCommand> acquired;
        try {
            acquired = remoteApi.consume(adapterId, commandConsumeLimit);
        } catch (RuntimeException error) {
            logSourceFailure(error);
            return null;
        }
        if (acquired.isEmpty() || roundsStopped) {
            return List.of();
        }
        ArrayList<QueuedCommand> batch = new ArrayList<>(acquired.size());
        acquired.forEach((entryKey, command) -> batch.add(
                new QueuedCommand(entryKey, command)
        ));
        return List.copyOf(batch);
    }

    @Override
    public void quiesce() {
        roundsStopped = true;
        retryQueue.stopIngress();
    }

    @Override
    public synchronized void finishAfterSchedulerStop() {
        if (closeFinished) {
            return;
        }
        quiesce();
        retryQueue.clear();
        closeFinished = true;
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

    private void logSourceFailure(RuntimeException error) {
        WorkerDeliveryAdapterException failure;
        if (error instanceof WorkerDeliveryAdapterException classified) {
            failure = classified;
        } else {
            failure = new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                    "deliveryCommand.consumeRemote",
                    "Delivery Command acquisition failed",
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
}
