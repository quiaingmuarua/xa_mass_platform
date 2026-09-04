package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerRouteVerifier;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.SocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.WebSocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterEventDispatcher;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.BatchDispatcher;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandItem;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportDispatcher;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Process composition boundary for the fixed Netty Adapter types. */
public final class NettyWorkerDeliveryAdapterFactory {

    private final WorkerDeliveryCodec codec;
    private final WorkerDeliveryRemoteApi remoteApi;
    private final WorkerRouteVerifier routeVerifier;

    public NettyWorkerDeliveryAdapterFactory(
            URI remoteBaseUrl,
            Duration remoteRequestTimeout,
            WorkerRouteVerifier routeVerifier
    ) {
        codec = new WorkerDeliveryCodec();
        remoteApi = new WorkerDeliveryRemoteApi(
                remoteBaseUrl,
                remoteRequestTimeout,
                codec
        );
        this.routeVerifier = Objects.requireNonNull(
                routeVerifier,
                "routeVerifier"
        );
    }

    public WorkerDeliveryAdapter create(
            String adapterId,
            NettyWorkerDeliveryAdapterConfig config
    ) {
        String requiredAdapterId = requireAdapterId(adapterId);
        NettyWorkerDeliveryAdapterConfig requiredConfig =
                Objects.requireNonNull(config, "config");
        NettyWorkerServer networkServer = switch (requiredConfig.type()) {
            case WEBSOCKET -> new WebSocketNettyWorkerServer(
                    requiredAdapterId,
                    requiredConfig.listenHost(),
                    requiredConfig.listenPort(),
                    requiredConfig.sendTimeLimit(),
                    requiredConfig.shutdownTimeout()
            );
            case SOCKET -> new SocketNettyWorkerServer(
                    requiredAdapterId,
                    requiredConfig.listenHost(),
                    requiredConfig.listenPort(),
                    requiredConfig.sendTimeLimit(),
                    requiredConfig.shutdownTimeout()
            );
        };
        DeliveryReportDispatcher reportDispatcher =
                new DeliveryReportDispatcher(
                        requiredAdapterId,
                        requiredConfig.reportQueueCapacity(),
                        requiredConfig.reportBackoff(),
                        remoteApi
                );
        WorkerRouteRegistry routes = new WorkerRouteRegistry(
                requiredConfig.reconnectVerificationRetention(),
                requiredConfig.maximumDisconnectedWorkers()
        );
        WorkerConnectionMechanism connectionMechanism =
                new WorkerConnectionMechanism(
                        routes,
                        networkServer,
                        routeVerifier,
                        codec,
                        reportDispatcher,
                        requiredAdapterId,
                        requiredConfig.sendTimeLimit(),
                        requiredConfig.maximumEncodedPropertiesBytes()
                );
        WorkerConnectionInboundHandler connectionInboundHandler =
                new WorkerConnectionInboundHandler(connectionMechanism);
        AdapterEventDispatcher adapterEventDispatcher =
                AdapterEventDispatcher.defaults(
                        requiredAdapterId,
                        connectionMechanism
                );
        DeliveryCommandProcess commandProcess = new DeliveryCommandProcess(
                connectionMechanism,
                adapterEventDispatcher,
                reportDispatcher,
                requiredAdapterId
        );
        BatchDispatcher<DeliveryCommandItem> commandDispatcher =
                BatchDispatcher.pulling(
                        requiredAdapterId,
                        "delivery-command",
                        requiredConfig.commandRetryCapacity(),
                        requiredConfig.commandConsumeLimit(),
                        requiredConfig.commandBackoff(),
                        () -> acquireCommands(
                                requiredAdapterId,
                                requiredConfig.commandConsumeLimit()
                        ),
                        commandProcess
                );
        AdapterProcessManager processManager = new AdapterProcessManager(
                commandDispatcher,
                reportDispatcher,
                requiredConfig.shutdownTimeout()
        );
        return new NettyWorkerDeliveryAdapter(
                requiredAdapterId,
                networkServer,
                connectionInboundHandler,
                connectionMechanism,
                processManager
        );
    }

    private List<DeliveryCommandItem> acquireCommands(
            String adapterId,
            int consumeLimit
    ) {
        Map<String, DeliveryCommand> acquired = remoteApi.consumeCommands(
                adapterId,
                consumeLimit
        );
        if (acquired.isEmpty()) {
            return List.of();
        }
        ArrayList<DeliveryCommandItem> batch = new ArrayList<>(
                acquired.size()
        );
        acquired.forEach((entryKey, command) -> batch.add(
                new DeliveryCommandItem(entryKey, command)
        ));
        return List.copyOf(batch);
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(adapterId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own an active Adapter"
            );
        }
        return adapterId;
    }
}
