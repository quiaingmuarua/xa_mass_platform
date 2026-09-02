package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.SocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.WebSocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterEventDispatcher;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.BatchDispatcher;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Finite construction boundary for the built-in Netty Adapter types. */
public final class NettyWorkerDeliveryAdapters {

    private NettyWorkerDeliveryAdapters() {
    }

    public static WorkerDeliveryAdapter webSocket(
            String adapterId,
            URI remoteApiBaseUrl,
            Duration remoteRequestTimeout,
            String listenHost,
            int listenPort,
            List<NettyAdapterProcessConfig> processConfigs,
            NettyWorkerRouteCacheConfig routeCacheConfig,
            NettyWorkerPropertiesCacheConfig propertiesCacheConfig,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        validateCommon(
                adapterId,
                remoteApiBaseUrl,
                remoteRequestTimeout,
                listenHost,
                listenPort,
                processConfigs,
                routeCacheConfig,
                propertiesCacheConfig,
                sendTimeLimit,
                shutdownTimeout
        );
        return build(
                new WebSocketNettyWorkerServer(
                        adapterId,
                        listenHost,
                        listenPort,
                        sendTimeLimit,
                        shutdownTimeout
                ),
                adapterId,
                remoteApiBaseUrl,
                remoteRequestTimeout,
                processConfigs,
                routeCacheConfig,
                propertiesCacheConfig,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    public static WorkerDeliveryAdapter socket(
            String adapterId,
            URI remoteApiBaseUrl,
            Duration remoteRequestTimeout,
            String listenHost,
            int listenPort,
            List<NettyAdapterProcessConfig> processConfigs,
            NettyWorkerRouteCacheConfig routeCacheConfig,
            NettyWorkerPropertiesCacheConfig propertiesCacheConfig,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        validateCommon(
                adapterId,
                remoteApiBaseUrl,
                remoteRequestTimeout,
                listenHost,
                listenPort,
                processConfigs,
                routeCacheConfig,
                propertiesCacheConfig,
                sendTimeLimit,
                shutdownTimeout
        );
        return build(
                new SocketNettyWorkerServer(
                        adapterId,
                        listenHost,
                        listenPort,
                        sendTimeLimit,
                        shutdownTimeout
                ),
                adapterId,
                remoteApiBaseUrl,
                remoteRequestTimeout,
                processConfigs,
                routeCacheConfig,
                propertiesCacheConfig,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    private static WorkerDeliveryAdapter build(
            NettyWorkerServer networkServer,
            String adapterId,
            URI remoteApiBaseUrl,
            Duration remoteRequestTimeout,
            List<NettyAdapterProcessConfig> processConfigs,
            NettyWorkerRouteCacheConfig routeCacheConfig,
            NettyWorkerPropertiesCacheConfig propertiesCacheConfig,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        ProcessConfigs configs = requireProcessConfigs(processConfigs);
        NettyAdapterProcessConfig.DeliveryCommand commandConfig =
                configs.command();
        NettyAdapterProcessConfig.DeliveryReport reportConfig =
                configs.report();

        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        WorkerDeliveryRemoteApi remoteApi = new WorkerDeliveryRemoteApi(
                remoteApiBaseUrl,
                remoteRequestTimeout,
                codec
        );
        BatchDispatcher<String> reportDispatcher = BatchDispatcher.queued(
                adapterId,
                "delivery-report",
                reportConfig.queueCapacity(),
                WorkerDeliveryRemoteApi.MAX_RESULTS_PER_APPEND,
                reportConfig.interval(),
                new DeliveryReportProcess(remoteApi, adapterId)
        );
        WorkerRouteRegistry routes = new WorkerRouteRegistry(routeCacheConfig);
        WorkerConnectionMechanism connectionMechanism =
                new WorkerConnectionMechanism(
                        routes,
                        networkServer,
                        remoteApi,
                        codec,
                        reportDispatcher,
                        adapterId,
                        sendTimeLimit,
                        propertiesCacheConfig
                );
        WorkerConnectionInboundHandler connectionInboundHandler =
                new WorkerConnectionInboundHandler(connectionMechanism);
        AdapterEventDispatcher adapterEventDispatcher =
                AdapterEventDispatcher.defaults(
                        adapterId,
                        connectionMechanism
                );
        AdapterProcessManager processManager = new AdapterProcessManager(
                adapterId,
                remoteApi,
                connectionMechanism,
                adapterEventDispatcher,
                reportDispatcher,
                codec,
                commandConfig.consumeLimit(),
                commandConfig.queueCapacity(),
                commandConfig.interval(),
                shutdownTimeout
        );

        return new NettyWorkerDeliveryAdapter(
                adapterId,
                networkServer,
                connectionInboundHandler,
                connectionMechanism,
                processManager
        );
    }

    private static void validateCommon(
            String adapterId,
            URI remoteApiBaseUrl,
            Duration remoteRequestTimeout,
            String listenHost,
            int listenPort,
            List<NettyAdapterProcessConfig> processConfigs,
            NettyWorkerRouteCacheConfig routeCacheConfig,
            NettyWorkerPropertiesCacheConfig propertiesCacheConfig,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(adapterId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own an active Adapter"
            );
        }
        Objects.requireNonNull(remoteApiBaseUrl, "remoteApiBaseUrl");
        requirePositive(remoteRequestTimeout, "remoteRequestTimeout");
        if (listenHost == null || listenHost.isBlank()) {
            throw new IllegalArgumentException(
                    "listenHost must be non-blank"
            );
        }
        if (listenPort < 1 || listenPort > 65_535) {
            throw new IllegalArgumentException(
                    "listenPort must be between 1 and 65535"
            );
        }
        Objects.requireNonNull(routeCacheConfig, "routeCacheConfig");
        Objects.requireNonNull(
                propertiesCacheConfig,
                "propertiesCacheConfig"
        );
        requirePositive(sendTimeLimit, "sendTimeLimit");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(processConfigs, "processConfigs");
    }

    private static ProcessConfigs requireProcessConfigs(
            List<NettyAdapterProcessConfig> processConfigs
    ) {
        Objects.requireNonNull(processConfigs, "processConfigs");
        NettyAdapterProcessConfig.DeliveryCommand command = null;
        NettyAdapterProcessConfig.DeliveryReport report = null;
        for (NettyAdapterProcessConfig processConfig : processConfigs) {
            Objects.requireNonNull(processConfig, "processConfig");
            if (processConfig instanceof NettyAdapterProcessConfig
                    .DeliveryCommand
                    value) {
                if (command != null) {
                    throw duplicateProcess();
                }
                command = value;
            } else if (processConfig
                    instanceof NettyAdapterProcessConfig
                    .DeliveryReport value) {
                if (report != null) {
                    throw duplicateProcess();
                }
                report = value;
            }
        }
        if (command == null || report == null || processConfigs.size() != 2) {
            throw new IllegalArgumentException(
                    "Adapter requires exactly one DELIVERY_COMMAND and one "
                            + "DELIVERY_REPORT process"
            );
        }
        return new ProcessConfigs(command, report);
    }

    private static IllegalArgumentException duplicateProcess() {
        return new IllegalArgumentException(
                "Adapter process types must be unique"
        );
    }

    private record ProcessConfigs(
            NettyAdapterProcessConfig.DeliveryCommand command,
            NettyAdapterProcessConfig.DeliveryReport report
    ) {}

    private static void requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
