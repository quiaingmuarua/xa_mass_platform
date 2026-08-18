package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass.WORKER_FAILURE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerPropertiesCacheConfig;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerRouteRemoteApi;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Shared Netty connection mechanism for one Adapter instance.
 *
 * <p>It owns identity interpretation, route verification, Command routing,
 * and Result ingress. Physical framing, writes, and closes always return to
 * the selected {@link NettyWorkerServer} owner.
 */
public final class WorkerConnectionMechanism {

    private static final Set<String> PROPERTIES_PAYLOAD_FIELDS = Set.of(
            "properties"
    );
    private static final String WORKER_PROPERTIES_SNAPSHOT_EVENT =
            "platform.worker.properties.snapshot";

    private static final System.Logger LOGGER = System.getLogger(
            WorkerConnectionMechanism.class.getName()
    );

    private final WorkerRouteRegistry routes;
    private final WorkerPropertiesCache propertiesCache;
    private final NettyWorkerServer networkServer;
    private final WorkerRouteRemoteApi routeRemoteApi;
    private final WorkerDeliveryCodec codec;
    private final DeliveryReportProcess reportProcess;
    private final String adapterId;
    private final Duration sendTimeLimit;

    public WorkerConnectionMechanism(
            WorkerRouteRegistry routes,
            NettyWorkerServer networkServer,
            WorkerRouteRemoteApi routeRemoteApi,
            WorkerDeliveryCodec codec,
            DeliveryReportProcess reportProcess,
            String adapterId,
            Duration sendTimeLimit,
            NettyWorkerPropertiesCacheConfig propertiesCacheConfig
    ) {
        this.routes = Objects.requireNonNull(routes, "routes");
        propertiesCache = new WorkerPropertiesCache(propertiesCacheConfig);
        this.networkServer = Objects.requireNonNull(
                networkServer,
                "networkServer"
        );
        this.routeRemoteApi = Objects.requireNonNull(
                routeRemoteApi,
                "routeRemoteApi"
        );
        this.codec = Objects.requireNonNull(codec, "codec");
        this.reportProcess = Objects.requireNonNull(
                reportProcess,
                "reportProcess"
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

    void receive(
            ChannelHandlerContext context,
            String encodedReport
    ) {
        String workerId = routes.claimedWorkerId(context.channel());
        if (workerId == null) {
            receiveIdentity(context, encodedReport);
        } else if (routes.hasVerificationEvidence(workerId)) {
            receiveBoundReport(context, workerId, encodedReport);
        } else {
            // First-verification input is deliberately not buffered.
        }
    }

    void channelInactive(Channel channel) {
        String workerId = routes.claimedWorkerId(channel);
        routes.onChannelClosed(channel);
        invalidatePropertiesIfRouteForgotten(workerId);
    }

    void channelFailed(Channel channel, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String workerId = routes.claimedWorkerId(channel);
        routes.onChannelClosed(channel);
        invalidatePropertiesIfRouteForgotten(workerId);
        networkServer.closeConnection(
                channel,
                AdapterConnectionCloseReason.TRANSPORT_ERROR
        );
    }

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

    public Map<String, WorkerConnectionState> connectionStates(
            List<String> workerIds
    ) {
        return routes.connectionStates(workerIds);
    }

    public Map<String, WorkerPropertiesObservation> workerProperties(
            List<String> workerIds
    ) {
        List<String> requiredWorkerIds = List.copyOf(
                Objects.requireNonNull(workerIds, "workerIds")
        );
        Map<String, WorkerPropertiesObservation> observations =
                new LinkedHashMap<>();
        for (String workerId : requiredWorkerIds) {
            if (!routes.hasVerificationEvidence(workerId)) {
                propertiesCache.invalidate(workerId);
                observations.put(
                        workerId,
                        WorkerPropertiesObservation.unknown()
                );
            } else {
                observations.put(
                        workerId,
                        propertiesCache.observation(workerId)
                );
            }
        }
        return Collections.unmodifiableMap(observations);
    }

    public Map<String, CloseCurrentOutcome> closeCurrentConnections(
            List<String> workerIds
    ) {
        Map<String, Channel> detached = routes.detachActiveChannels(workerIds);
        List<String> requiredWorkerIds = List.copyOf(workerIds);
        Map<String, CloseCurrentOutcome> outcomes = new LinkedHashMap<>();
        for (String workerId : requiredWorkerIds) {
            Channel channel = detached.get(workerId);
            if (channel == null) {
                outcomes.put(workerId, CloseCurrentOutcome.NOT_CONNECTED);
                continue;
            }
            invalidatePropertiesIfRouteForgotten(workerId);
            boolean active = channel.isActive();
            networkServer.closeConnection(
                    channel,
                    AdapterConnectionCloseReason.MANAGEMENT_REQUEST
            );
            outcomes.put(
                    workerId,
                    active
                            ? CloseCurrentOutcome.CLOSE_STARTED
                            : CloseCurrentOutcome.NOT_CONNECTED
            );
        }
        return Collections.unmodifiableMap(outcomes);
    }

    public void clear() {
        routes.clear();
        propertiesCache.clear();
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
            case VERIFIED_ACTIVATED -> {
                closeReplaced(admission.replacedChannel());
            }
            case VERIFICATION_CLAIMED -> {
                propertiesCache.invalidate(workerId);
                verifyRoute(context, workerId);
            }
        }
    }

    private void verifyRoute(
            ChannelHandlerContext context,
            String workerId
    ) {
        try {
            routeRemoteApi.verify(adapterId, workerId)
                    .whenComplete((ignored, failure) ->
                            context.executor().execute(() ->
                                    finishVerification(
                                            context,
                                            workerId,
                                            failure
                                    )
                            )
                    );
        } catch (RuntimeException error) {
            finishVerification(context, workerId, error);
        }
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

        if (!routes.completeVerification(workerId, channel)) {
            routes.onChannelClosed(channel);
            networkServer.closeConnection(
                    channel,
                    AdapterConnectionCloseReason.VERIFICATION_FAILED
            );
            return;
        }
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
        if (report.dst() != TASK && report.dst() != SYSTEM) {
            logDrop("dropUnsupportedDestination", report);
            return;
        }

        var outcome = classifyDeliveryReportOutcomeCode(report.outcomeCode());
        if (outcome != SUCCESS && outcome != WORKER_FAILURE) {
            logDrop("dropWorkerOutcome", report);
            return;
        }
        observePropertiesResult(context.channel(), report);
        boolean taskReport = report.dst() == TASK;
        switch (reportProcess.ingress(List.of(encodedReport))) {
            case ACCEPTED -> {
            }
            case FULL -> {
                if (taskReport) {
                    closeCurrent(
                            workerId,
                            context.channel(),
                            AdapterConnectionCloseReason.RESULT_BUFFER_FULL
                    );
                } else {
                    logDrop("dropSystemResultBufferFull", report);
                }
            }
            case CLOSED -> {
                if (taskReport) {
                    closeCurrent(
                            workerId,
                            context.channel(),
                            AdapterConnectionCloseReason.ADAPTER_STOPPING
                    );
                } else {
                    logDrop("dropSystemResultBufferClosed", report);
                }
            }
        }
    }

    void observePropertiesResult(
            Channel currentChannel,
            DeliveryReport report
    ) {
        Objects.requireNonNull(currentChannel, "currentChannel");
        Objects.requireNonNull(report, "report");
        if (!WORKER_PROPERTIES_SNAPSHOT_EVENT.equals(report.messageType())
                || !"200".equals(report.outcomeCode())) {
            return;
        }
        String workerId = report.sourceId();
        if (!routes.isCurrentConnected(workerId, currentChannel)) {
            return;
        }
        try {
            Map<String, Object> payload = Jsons.parseObject(report.payload());
            Object rawProperties = payload.get("properties");
            if (!payload.keySet().equals(PROPERTIES_PAYLOAD_FIELDS)
                    || !(rawProperties instanceof Map<?, ?> values)) {
                return;
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return;
                }
                properties.put(key, entry.getValue());
            }
            WorkerPropertiesCache.ObservationWrite write =
                    propertiesCache.observe(workerId, properties);
            if (!routes.isCurrentConnected(workerId, currentChannel)) {
                propertiesCache.rollback(write);
            }
        } catch (RuntimeException ignored) {
            // The original valid DeliveryReport still follows Result ingress.
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
        invalidatePropertiesIfRouteForgotten(workerId);
        networkServer.closeConnection(channel, reason);
    }

    private void close(
            Channel channel,
            AdapterConnectionCloseReason reason
    ) {
        String workerId = routes.claimedWorkerId(channel);
        routes.onChannelClosed(channel);
        invalidatePropertiesIfRouteForgotten(workerId);
        networkServer.closeConnection(channel, reason);
    }

    private void invalidatePropertiesIfRouteForgotten(String workerId) {
        if (workerId != null && !routes.hasVerificationEvidence(workerId)) {
            propertiesCache.invalidate(workerId);
        }
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
                && "null".equals(report.payload())
                && report.forward().isEmpty();
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

    public enum DeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }

    public enum CloseCurrentOutcome {
        CLOSE_STARTED("close-started"),
        NOT_CONNECTED("not-connected");

        private final String wireValue;

        CloseCurrentOutcome(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }
}
