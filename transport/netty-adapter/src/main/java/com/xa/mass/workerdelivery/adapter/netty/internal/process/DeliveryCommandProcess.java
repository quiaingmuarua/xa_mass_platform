package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism.DeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Processes one acquired Command batch without owning its consumption lane. */
public final class DeliveryCommandProcess
        implements AdapterBatchProcessor<DeliveryCommandItem> {

    private static final String WORKER_DELIVERY_EXPIRED_EVENT =
            "platform.adapter.worker-delivery.expired";
    private static final String WORKER_SERVICEABILITY_EVIDENCE_FORWARD =
            "worker-serviceability-evidence:v1";

    private static final System.Logger LOGGER = System.getLogger(
            DeliveryCommandProcess.class.getName()
    );

    private final WorkerConnectionMechanism connectionMechanism;
    private final DeliveryReportDispatcher reportDispatcher;
    private final AdapterEventDispatcher adapterEventDispatcher;
    private final String adapterId;
    private final LongSupplier nowMillis;

    public DeliveryCommandProcess(
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            DeliveryReportDispatcher reportDispatcher,
            String adapterId
    ) {
        this(
                connectionMechanism,
                adapterEventDispatcher,
                reportDispatcher,
                adapterId,
                System::currentTimeMillis
        );
    }

    DeliveryCommandProcess(
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            DeliveryReportDispatcher reportDispatcher,
            String adapterId,
            LongSupplier nowMillis
    ) {
        this.connectionMechanism = Objects.requireNonNull(
                connectionMechanism,
                "connectionMechanism"
        );
        this.adapterEventDispatcher = Objects.requireNonNull(
                adapterEventDispatcher,
                "adapterEventDispatcher"
        );
        this.reportDispatcher = Objects.requireNonNull(
                reportDispatcher,
                "reportDispatcher"
        );
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        this.adapterId = adapterId;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    @Override
    public BatchProcessResult process(
            List<DeliveryCommandItem> batch
    ) {
        List<DeliveryCommandItem> observed = List.copyOf(batch);
        long currentTimeMillis = nowMillis.getAsLong();
        ArrayList<Integer> retryIndexes = new ArrayList<>();
        for (int index = 0; index < observed.size(); index++) {
            DeliveryCommandItem item = observed.get(index);
            DeliveryCommand command = item.command();
            if (command.executeBeforeMillis() <= currentTimeMillis) {
                if (isTaskWorkerCommand(item)) {
                    offerExpiredTaskResult(item, currentTimeMillis);
                }
                continue;
            }
            WorkerConnectionMechanism.DeliveryAttempt attempt = dispatch(item);
            if (attempt == RETRY_LATER) {
                retryIndexes.add(index);
            } else if (attempt == UNKNOWN
                    && (isTaskWorkerCommand(item)
                    || isSystemWorkerCommand(item))) {
                logUnknownDelivery(item);
            }
        }
        if (retryIndexes.isEmpty()) {
            return BatchProcessResult.completed();
        }
        return BatchProcessResult.requeue(
                WorkerDeliveryAdapterErrorCode
                        .WORKER_DELIVERY_RETRY_LATER,
                retryIndexes
        );
    }

    private WorkerConnectionMechanism.DeliveryAttempt dispatch(
            DeliveryCommandItem item
    ) {
        DeliveryCommand command = item.command();
        if (command.dst() == ADAPTER) {
            offerAdapterEventResult(adapterEventDispatcher.dispatch(command));
            return WorkerConnectionMechanism.DeliveryAttempt.STARTED;
        }
        if (isTaskWorkerCommand(item) || isSystemWorkerCommand(item)) {
            return connectionMechanism.deliver(item.entryKey(), command);
        }
        logInvalidTarget(item.entryKey(), command);
        return WorkerConnectionMechanism.DeliveryAttempt.UNKNOWN;
    }

    private void offerExpiredTaskResult(
            DeliveryCommandItem item,
            long observedAtMillis
    ) {
        DeliveryReport rejection = DeliveryReport.fromCommand(
                item.command(),
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
                        "workerId", item.entryKey(),
                        "observedAtMillis", observedAtMillis
                )),
                WORKER_SERVICEABILITY_EVIDENCE_FORWARD
        );
        offerReport(
                rejection,
                item.entryKey(),
                "Expired TASK rejection was dropped"
        );
        offerReport(
                evidence,
                item.entryKey(),
                "Expired TASK serviceability evidence was dropped"
        );
    }

    private void offerAdapterEventResult(DeliveryReport report) {
        offerReport(
                report,
                report.messageType(),
                "Adapter Event Result was dropped"
        );
    }

    private void offerReport(
            DeliveryReport report,
            String target,
            String message
    ) {
        DeliveryReportDispatcher.DispatchStatus status =
                reportDispatcher.tryDispatch(report);
        if (report.dst() == TASK
                && status
                != DeliveryReportDispatcher.DispatchStatus.ACCEPTED) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} destination={1} target={2} message={3}",
                    adapterId,
                    report.dst(),
                    target,
                    message
            );
        }
    }

    private static boolean isTaskWorkerCommand(DeliveryCommandItem item) {
        return item.command().src() == TASK
                && item.command().dst() == WORKER;
    }

    private static boolean isSystemWorkerCommand(DeliveryCommandItem item) {
        return item.command().src() == SYSTEM
                && item.command().dst() == WORKER;
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

    private void logUnknownDelivery(DeliveryCommandItem item) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} target={3} "
                        + "messageType={4}",
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED.code(),
                "deliveryCommand.deliver",
                adapterId,
                item.entryKey(),
                item.command().messageType()
        );
    }
}
