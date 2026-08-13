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
import com.xa.mass.workerdelivery.adapter.http.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess.DeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Shared Netty connection mechanism for one Adapter instance.
 *
 * <p>It owns identity interpretation, route verification, Command routing,
 * and Result ingress. Physical framing, writes, and closes always return to
 * the selected {@link NettyWorkerServer} owner.
 */
@ChannelHandler.Sharable
public final class WorkerConnectionMechanism
        extends SimpleChannelInboundHandler<String>
        implements DeliveryCommandProcess.Target {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerConnectionMechanism.class.getName()
    );

    private final WorkerRouteRegistry routes;
    private final NettyWorkerServer networkServer;
    private final WorkerDeliveryHttpClient httpClient;
    private final WorkerDeliveryCodec codec;
    private final DeliveryReportProcess.Acceptor reportAcceptor;
    private final String adapterId;
    private final Duration sendTimeLimit;

    public WorkerConnectionMechanism(
            WorkerRouteRegistry routes,
            NettyWorkerServer networkServer,
            WorkerDeliveryHttpClient httpClient,
            WorkerDeliveryCodec codec,
            DeliveryReportProcess.Acceptor reportAcceptor,
            String adapterId,
            Duration sendTimeLimit
    ) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.networkServer = Objects.requireNonNull(
                networkServer,
                "networkServer"
        );
        this.httpClient = Objects.requireNonNull(
                httpClient,
                "httpClient"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportAcceptor = Objects.requireNonNull(
                reportAcceptor,
                "reportAcceptor"
        );
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        this.adapterId = adapterId;
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            String encodedReport
    ) {
        WorkerRouteRegistry.InboundInspection inbound =
                routes.inspectInbound(context.channel());
        switch (inbound.kind()) {
            case IDENTITY_REQUIRED -> receiveIdentity(context, encodedReport);
            case VERIFICATION_PENDING -> {
                // First-verification input is deliberately not buffered.
            }
            case VERIFIED -> receiveBoundReport(
                    context,
                    inbound.workerId(),
                    encodedReport
            );
            case INVALID -> close(
                    context.channel(),
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        routes.onChannelClosed(context.channel());
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        routes.onChannelClosed(context.channel());
        networkServer.closeConnection(
                context.channel(),
                AdapterConnectionCloseReason.TRANSPORT_ERROR
        );
    }

    @Override
    public DeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        Objects.requireNonNull(command, "command");
        Channel channel = routes.activeChannel(workerId);
        if (channel == null) {
            return DeliveryAttempt.RETRY_LATER;
        }

        String encodedCommand;
        try {
            encodedCommand = codec.encodeDeliveryCommand(command);
        } catch (RuntimeException error) {
            closeCurrent(
                    workerId,
                    channel,
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.UNKNOWN;
        }

        TextWriteAttempt attempt = networkServer.writeText(
                channel,
                encodedCommand
        );
        return switch (attempt) {
            case STARTED -> DeliveryAttempt.STARTED;
            case RETRY_LATER -> DeliveryAttempt.RETRY_LATER;
            case UNKNOWN -> {
                closeCurrent(
                        workerId,
                        channel,
                        AdapterConnectionCloseReason.TRANSPORT_ERROR
                );
                yield DeliveryAttempt.UNKNOWN;
            }
        };
    }

    public void clear() {
        routes.clear();
    }

    private void receiveIdentity(
            ChannelHandlerContext context,
            String encodedReport
    ) {
        DeliveryReport report = decode(encodedReport);
        if (report == null) {
            close(
                    context.channel(),
                    AdapterConnectionCloseReason.INVALID_REPORT
            );
            return;
        }
        if (report.dst() != ADAPTER
                || !WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )) {
            close(
                    context.channel(),
                    AdapterConnectionCloseReason.IDENTITY_REQUIRED
            );
            return;
        }
        if (!isValidIdentity(report)) {
            close(
                    context.channel(),
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }

        Channel channel = context.channel();
        String workerId = report.sourceId();
        WorkerRouteRegistry.IdentityAdmission admission =
                routes.admitIdentity(workerId, channel);
        switch (admission.kind()) {
            case VERIFICATION_BUSY -> close(
                    channel,
                    AdapterConnectionCloseReason.VERIFICATION_IN_PROGRESS
            );
            case VERIFIED_ACTIVATED -> closeReplaced(
                    admission.replacedChannel()
            );
            case VERIFICATION_CLAIMED -> verifyRoute(
                    context,
                    workerId
            );
        }
    }

    private void verifyRoute(
            ChannelHandlerContext context,
            String workerId
    ) {
        CompletionStage<Void> verification;
        try {
            String path = "/api/v1/worker-delivery/endpoint-managers/"
                    + WorkerDeliveryHttpClient.encodePathSegment(adapterId)
                    + "/workers/"
                    + WorkerDeliveryHttpClient.encodePathSegment(workerId)
                    + ":verify-binding";
            verification = httpClient.postEmptyAsync(path)
                    .thenApply(response -> {
                        requireVerifiedRoute(response.statusCode());
                        return null;
                    });
        } catch (RuntimeException error) {
            finishVerification(context, workerId, error);
            return;
        }
        verification.whenComplete((ignored, failure) ->
                context.executor().execute(() ->
                        finishVerification(context, workerId, failure)
                )
        );
    }

    private static void requireVerifiedRoute(int statusCode) {
        if (statusCode == 204) {
            return;
        }
        WorkerDeliveryAdapterErrorCode errorCode;
        if (statusCode >= 400 && statusCode < 500) {
            errorCode = WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED;
        } else if (statusCode >= 500) {
            errorCode = WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_UNAVAILABLE;
        } else {
            errorCode = WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_PROTOCOL_ERROR;
        }
        throw new WorkerDeliveryAdapterException(
                errorCode,
                "workerConnection.verifyRoute",
                "Worker route verification failed with HTTP " + statusCode,
                null
        );
    }

    private void finishVerification(
            ChannelHandlerContext context,
            String workerId,
            Throwable failure
    ) {
        Channel channel = context.channel();
        if (failure != null) {
            if (!routes.cancelVerification(workerId, channel)) {
                return;
            }
            if (isDefiniteRouteRejection(failure)) {
                writeTerminalClose(channel);
            } else {
                networkServer.closeConnection(
                        channel,
                        AdapterConnectionCloseReason.VERIFICATION_FAILED
                );
            }
            return;
        }

        if (!channel.isActive()) {
            routes.cancelVerification(workerId, channel);
            return;
        }

        WorkerRouteRegistry.ActivationResult activation =
                routes.completeVerificationAndActivate(workerId, channel);
        if (!activation.accepted()) {
            routes.onChannelClosed(channel);
            networkServer.closeConnection(
                    channel,
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }
        closeReplaced(activation.replacedChannel());
    }

    private void receiveBoundReport(
            ChannelHandlerContext context,
            String workerId,
            String encodedReport
    ) {
        DeliveryReport report = decode(encodedReport);
        if (report == null) {
            logDrop("dropMalformedWorkerResult", null);
            return;
        }
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
            return;
        }
        if (report.dst() != TASK) {
            logDrop("dropUnsupportedDestination", report);
            return;
        }

        var outcome = classifyDeliveryReportOutcomeCode(report.outcomeCode());
        if (outcome != SUCCESS && outcome != WORKER_FAILURE) {
            logDrop("dropWorkerOutcome", report);
            return;
        }
        switch (reportAcceptor.ingress(List.of(encodedReport))) {
            case ACCEPTED -> {
            }
            case FULL -> closeCurrent(
                    workerId,
                    context.channel(),
                    AdapterConnectionCloseReason.RESULT_BUFFER_FULL
            );
            case CLOSED -> closeCurrent(
                    workerId,
                    context.channel(),
                    AdapterConnectionCloseReason.ADAPTER_STOPPING
            );
        }
    }

    private DeliveryReport decode(String encodedReport) {
        try {
            return codec.decodeDeliveryReport(encodedReport);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void writeTerminalClose(Channel channel) {
        try {
            DeliveryCommand command = DeliveryCommand.create(
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
            networkServer.writeTextAndClose(
                    channel,
                    codec.encodeDeliveryCommand(command),
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
        } catch (RuntimeException error) {
            networkServer.closeConnection(
                    channel,
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
        }
    }

    private void closeCurrent(
            String workerId,
            Channel channel,
            AdapterConnectionCloseReason reason
    ) {
        routes.deactivate(workerId, channel);
        networkServer.closeConnection(channel, reason);
    }

    private void close(
            Channel channel,
            AdapterConnectionCloseReason reason
    ) {
        routes.onChannelClosed(channel);
        networkServer.closeConnection(channel, reason);
    }

    private void closeReplaced(Channel replacedChannel) {
        if (replacedChannel != null) {
            networkServer.closeConnection(
                    replacedChannel,
                    AdapterConnectionCloseReason.REPLACED
            );
        }
    }

    private void logDrop(String action, DeliveryReport report) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} phase=BOUND messageType={2}",
                WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID.code(),
                "netty." + action,
                report == null ? "<malformed>" : report.messageType()
        );
    }

    private static boolean isValidIdentity(DeliveryReport report) {
        return report.src() == WORKER
                && report.dst() == ADAPTER
                && WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                report.messageType()
        )
                && "200".equals(report.outcomeCode())
                && report.forward().isEmpty()
                && "null".equals(report.payload());
    }

    private static boolean isDefiniteRouteRejection(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof WorkerDeliveryAdapterException classified
                && classified.errorCode()
                == WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
