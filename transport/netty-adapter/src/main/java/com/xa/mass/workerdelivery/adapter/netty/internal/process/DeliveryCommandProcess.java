package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess.DeliveryAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.http.WorkerDeliveryHttpClient;
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

    private static final System.Logger LOGGER = System.getLogger(
            DeliveryCommandProcess.class.getName()
    );

    private final FiniteQueue<TargetedDeliveryCommand> commandQueue;
    private final WorkerDeliveryHttpClient httpClient;
    private final DeliveryCommandHttpContract httpContract;
    private final Target target;
    private final DeliveryReportProcess.Acceptor reportAcceptor;
    private final WorkerDeliveryCodec codec;
    private final String adapterId;
    private final int commandConsumeLimit;
    private final LongSupplier nowMillis;
    private volatile boolean roundsStopped;
    private boolean closeFinished;

    public DeliveryCommandProcess(
            WorkerDeliveryHttpClient httpClient,
            Target target,
            DeliveryReportProcess.Acceptor reportAcceptor,
            WorkerDeliveryCodec codec,
            String adapterId,
            int commandConsumeLimit,
            int queueCapacity
    ) {
        this(
                httpClient,
                target,
                reportAcceptor,
                codec,
                adapterId,
                commandConsumeLimit,
                queueCapacity,
                System::currentTimeMillis
        );
    }

    DeliveryCommandProcess(
            WorkerDeliveryHttpClient httpClient,
            Target target,
            DeliveryReportProcess.Acceptor reportAcceptor,
            WorkerDeliveryCodec codec,
            String adapterId,
            int commandConsumeLimit,
            int queueCapacity,
            LongSupplier nowMillis
    ) {
        this.httpClient = Objects.requireNonNull(
                httpClient,
                "httpClient"
        );
        this.target = Objects.requireNonNull(target, "target");
        this.reportAcceptor = Objects.requireNonNull(
                reportAcceptor,
                "reportAcceptor"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        httpContract = new DeliveryCommandHttpContract(codec);
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (commandConsumeLimit <= 0
                || queueCapacity < commandConsumeLimit) {
            throw new IllegalArgumentException(
                    "commandConsumeLimit must be between 1 and command "
                            + "queue capacity"
            );
        }
        this.adapterId = adapterId;
        this.commandConsumeLimit = commandConsumeLimit;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        commandQueue = new FiniteQueue<>(queueCapacity);
    }

    @Override
    public void round() {
        if (roundsStopped) {
            return;
        }
        refillBelowSoftCapacity();
        if (roundsStopped) {
            return;
        }

        List<TargetedDeliveryCommand> observed = commandQueue.consume(
                commandQueue.capacity()
        );
        if (observed.isEmpty()) {
            return;
        }
        long currentTimeMillis = nowMillis.getAsLong();
        ArrayList<TargetedDeliveryCommand> retryLater = new ArrayList<>();
        for (TargetedDeliveryCommand queued : observed) {
            if (roundsStopped) {
                return;
            }
            DeliveryCommand command = queued.command();
            if (command == null
                    || command.executeBeforeMillis() <= currentTimeMillis) {
                offerExpiredResult(queued);
                continue;
            }
            if (target.deliver(queued.workerId(), command) == RETRY_LATER) {
                retryLater.add(queued);
            }
        }
        if (!roundsStopped && !retryLater.isEmpty()
                && commandQueue.ingress(retryLater)
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

    @Override
    public void quiesce() {
        roundsStopped = true;
        commandQueue.stopIngress();
    }

    @Override
    public synchronized void finishAfterSchedulerStop() {
        if (closeFinished) {
            return;
        }
        quiesce();
        commandQueue.clear();
        closeFinished = true;
    }

    private void refillBelowSoftCapacity() {
        if (commandQueue.estimatedSize() >= commandQueue.capacity()) {
            return;
        }

        Map<String, DeliveryCommand> acquired;
        try {
            acquired = consumeRemoteCommands();
        } catch (RuntimeException error) {
            logSourceFailure(error);
            return;
        }
        if (acquired.isEmpty() || roundsStopped) {
            return;
        }
        ArrayList<TargetedDeliveryCommand> batch = new ArrayList<>(
                acquired.size()
        );
        acquired.forEach((workerId, command) -> batch.add(
                new TargetedDeliveryCommand(workerId, command)
        ));
        if (commandQueue.ingress(batch)
                != FiniteQueue.QueueIngressStatus.ACCEPTED) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} commandCount={1} message={2}",
                    adapterId,
                    batch.size(),
                    "Consumed Adapter Commands were dropped"
            );
        }
    }

    private Map<String, DeliveryCommand> consumeRemoteCommands() {
        String path = "/api/v1/worker-delivery/endpoint-managers/"
                + WorkerDeliveryHttpClient.encodePathSegment(adapterId)
                + "/commands:consume";
        var response = httpClient.postJson(
                path,
                httpContract.encodeConsumeRequest(commandConsumeLimit)
        );
        if (response.statusCode() != 200) {
            WorkerDeliveryAdapterErrorCode errorCode =
                    response.statusCode() >= 500
                            ? WorkerDeliveryAdapterErrorCode
                            .REMOTE_API_UNAVAILABLE
                            : WorkerDeliveryAdapterErrorCode
                            .REMOTE_API_PROTOCOL_ERROR;
            throw new WorkerDeliveryAdapterException(
                    errorCode,
                    "deliveryCommand.consumeRemote",
                    "Worker command consume failed with HTTP "
                            + response.statusCode(),
                    null
            );
        }
        return httpContract.decodeConsumeResponse(response.body());
    }

    private void offerExpiredResult(TargetedDeliveryCommand queued) {
        DeliveryCommand command = queued.command();
        if (command == null) {
            return;
        }
        DeliveryReport rejection = DeliveryReport.fromCommand(
                command,
                ADAPTER,
                adapterId,
                Integer.toString(
                        WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED.code()
                ),
                "null"
        );
        if (reportAcceptor.ingress(List.of(
                codec.encodeDeliveryReport(rejection)
        )) != DeliveryReportProcess.ReportIngressStatus.ACCEPTED) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "adapterId={0} workerId={1} message={2}",
                    adapterId,
                    queued.workerId(),
                    "Adapter rejection result was dropped"
            );
        }
    }

    private void logSourceFailure(RuntimeException error) {
        WorkerDeliveryAdapterException failure;
        if (error instanceof WorkerDeliveryAdapterException classified) {
            failure = classified;
        } else {
            failure = new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                    "deliveryCommand.consumeRemote",
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

    public interface Target {

        DeliveryAttempt deliver(String workerId, DeliveryCommand command);
    }

    public enum DeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }
}
