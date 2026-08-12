package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass.WORKER_FAILURE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.BoundedDeliveryReportQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

public final class WorkerConnectionSession {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerConnectionSession.class.getName()
    );

    private final BoundWorkerConnectionDirectory connections;
    private final WorkerDeliveryCodec codec;
    private final BoundedDeliveryReportQueue reportQueue;
    private final WorkerDeliveryGatewayClient gateway;
    private final String endpointManagerId;
    private final Duration sendTimeLimit;
    private final BooleanSupplier acceptingConnections;
    private final ChannelHandlerContext context;
    private final TextFrameStrategy frameStrategy;
    private final String operationPrefix;
    private BindingPhase phase = BindingPhase.UNBOUND;
    private String workerId;

    WorkerConnectionSession(
            BoundWorkerConnectionDirectory connections,
            WorkerDeliveryCodec codec,
            BoundedDeliveryReportQueue reportQueue,
            WorkerDeliveryGatewayClient gateway,
            String endpointManagerId,
            Duration sendTimeLimit,
            BooleanSupplier acceptingConnections,
            ChannelHandlerContext context,
            TextFrameStrategy frameStrategy,
            String operationPrefix
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportQueue = Objects.requireNonNull(reportQueue, "reportQueue");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        this.endpointManagerId = endpointManagerId;
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
        this.context = Objects.requireNonNull(context, "context");
        this.frameStrategy = Objects.requireNonNull(
                frameStrategy,
                "frameStrategy"
        );
        this.operationPrefix = Objects.requireNonNull(
                operationPrefix,
                "operationPrefix"
        );
    }

    public void onText(String encodedDeliveryReport) {
        DeliveryReport report = codec.decodeDeliveryReport(
                encodedDeliveryReport
        );
        if (report == null) {
            if (phase == BindingPhase.BOUND) {
                logDrop("dropMalformedWorkerResult", null);
            } else {
                frameStrategy.close(
                        context.channel(),
                        ConnectionCloseReason.INVALID_REPORT
                );
            }
            return;
        }
        switch (phase) {
            case UNBOUND -> handleUnbound(report);
            case VERIFYING -> frameStrategy.close(
                    context.channel(),
                    ConnectionCloseReason.VERIFICATION_IN_PROGRESS
            );
            case BOUND -> handleBound(report, encodedDeliveryReport);
        }
    }

    public void onInactive() {
        if (phase == BindingPhase.BOUND && workerId != null) {
            connections.deactivate(workerId, context.channel());
        }
        phase = BindingPhase.UNBOUND;
        workerId = null;
    }

    public void onException() {
        if (phase == BindingPhase.BOUND && workerId != null) {
            connections.close(
                    workerId,
                    context.channel(),
                    ConnectionCloseReason.TRANSPORT_ERROR
            );
        } else {
            context.close();
        }
    }

    private void handleUnbound(DeliveryReport report) {
        if (report.dst() != ADAPTER
                || !WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )) {
            frameStrategy.close(
                    context.channel(),
                    ConnectionCloseReason.IDENTITY_REQUIRED
            );
            return;
        }
        if (!isValidIdentity(report)) {
            frameStrategy.close(
                    context.channel(),
                    ConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }
        verifyAndActivate(report.sourceId());
    }

    private void handleBound(
            DeliveryReport report,
            String encodedDeliveryReport
    ) {
        if (report.src() != WORKER
                || !workerId.equals(report.sourceId())) {
            logDrop("dropWorkerSourceMismatch", report);
            return;
        }
        if (report.dst() == ADAPTER) {
            if (WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                    report.messageType()
            )) {
                logDrop("dropRepeatedIdentity", report);
            } else {
                logDrop("dropUnknownWorkerEvent", report);
            }
        } else if (report.dst() == TASK) {
            acceptTaskReport(report, encodedDeliveryReport);
        } else {
            logDrop("dropUnsupportedDestination", report);
        }
    }

    private void verifyAndActivate(String identifyingWorkerId) {
        if (phase != BindingPhase.UNBOUND
                || !acceptingConnections.getAsBoolean()) {
            frameStrategy.close(
                    context.channel(),
                    ConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }
        phase = BindingPhase.VERIFYING;
        Channel channel = context.channel();
        channel.config().setAutoRead(false);

        CompletionStage<Void> verification;
        try {
            verification = Objects.requireNonNull(
                    gateway.verifyWorkerRoute(
                            endpointManagerId,
                            identifyingWorkerId
                    ),
                    "Worker route verification returned null"
            );
        } catch (RuntimeException error) {
            finishVerification(identifyingWorkerId, error);
            return;
        }
        verification.whenComplete((ignored, failure) ->
                context.executor().execute(() ->
                        finishVerification(identifyingWorkerId, failure)
                )
        );
    }

    private void finishVerification(
            String identifyingWorkerId,
            Throwable failure
    ) {
        Channel channel = context.channel();
        if (!channel.isActive()) {
            return;
        }
        if (failure != null) {
            Throwable cause = unwrap(failure);
            if (cause instanceof WorkerDeliveryAdapterException classified
                    && classified.errorCode()
                    == WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED) {
                sendCloseCommand();
            } else {
                frameStrategy.close(
                        channel,
                        ConnectionCloseReason.VERIFICATION_FAILED
                );
            }
            return;
        }
        if (phase != BindingPhase.VERIFYING
                || !acceptingConnections.getAsBoolean()) {
            frameStrategy.close(
                    channel,
                    ConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }

        connections.activate(identifyingWorkerId, channel);
        workerId = identifyingWorkerId;
        phase = BindingPhase.BOUND;
        if (!acceptingConnections.getAsBoolean()) {
            connections.close(
                    workerId,
                    channel,
                    ConnectionCloseReason.ADAPTER_STOPPING
            );
            return;
        }
        if (!channel.config().isAutoRead()) {
            channel.config().setAutoRead(true);
            context.read();
        }
    }

    private void acceptTaskReport(
            DeliveryReport report,
            String encodedDeliveryReport
    ) {
        var outcome = classifyDeliveryReportOutcomeCode(report.outcomeCode());
        if (outcome != SUCCESS && outcome != WORKER_FAILURE) {
            logDrop("dropWorkerOutcome", report);
            return;
        }
        switch (reportQueue.offer(encodedDeliveryReport)) {
            case ACCEPTED -> {
            }
            case FULL -> connections.close(
                    workerId,
                    context.channel(),
                    ConnectionCloseReason.RESULT_BUFFER_FULL
            );
            case CLOSED -> connections.close(
                    workerId,
                    context.channel(),
                    ConnectionCloseReason.ADAPTER_STOPPING
            );
        }
    }

    private void sendCloseCommand() {
        DeliveryCommand command;
        try {
            command = DeliveryCommand.create(
                    ADAPTER,
                    WORKER,
                    WORKER_CONNECTION_CLOSE_EVENT_CODE,
                    Math.addExact(
                            System.currentTimeMillis(),
                            sendTimeLimit.toMillis()
                    ),
                    "null",
                    ""
            );
            frameStrategy.writeText(
                    context.channel(),
                    codec.encodeDeliveryCommand(command)
            ).addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException error) {
            frameStrategy.close(
                    context.channel(),
                    ConnectionCloseReason.VERIFICATION_FAILED
            );
        }
    }

    private static boolean isValidIdentity(DeliveryReport report) {
        return report.src() == WORKER
                && report.dst() == ADAPTER
                && "200".equals(report.outcomeCode())
                && report.forward().isEmpty()
                && "null".equals(report.payload());
    }

    private void logDrop(String action, DeliveryReport report) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} phase={2} messageType={3}",
                WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID.code(),
                operationPrefix + "." + action,
                phase,
                report == null ? "<malformed>" : report.messageType()
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private enum BindingPhase {
        UNBOUND,
        VERIFYING,
        BOUND
    }
}
