package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.AFTER_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.BEFORE_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.SocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.WebSocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.ScheduledAdapterProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryCommandRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerRouteRemoteApi;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Finite construction boundary for the built-in Netty Adapter types. */
public final class NettyWorkerDeliveryAdapters {

    private static final String DELIVERY_COMMAND_PROCESS_ID =
            "DELIVERY_COMMAND";
    private static final String DELIVERY_REPORT_PROCESS_ID =
            "DELIVERY_REPORT";

    private NettyWorkerDeliveryAdapters() {
    }

    public static WorkerDeliveryAdapter webSocket(
            String adapterId,
            URI remoteApiBaseUrl,
            Duration remoteRequestTimeout,
            String listenHost,
            int listenPort,
            List<NettyAdapterProcessConfig> processConfigs,
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
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        ProcessConfigs configs = requireProcessConfigs(processConfigs);
        NettyAdapterProcessConfig.DeliveryCommand commandConfig =
                configs.command();
        NettyAdapterProcessConfig.DeliveryReport reportConfig =
                configs.report();

        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        WorkerDeliveryHttpClient httpClient = new WorkerDeliveryHttpClient(
                remoteApiBaseUrl,
                remoteRequestTimeout
        );
        DeliveryCommandRemoteApi commandRemoteApi =
                new DeliveryCommandRemoteApi(httpClient, codec);
        DeliveryReportRemoteApi reportRemoteApi =
                new DeliveryReportRemoteApi(httpClient);
        WorkerRouteRemoteApi routeRemoteApi =
                new WorkerRouteRemoteApi(httpClient);
        DeliveryReportProcess reportProcess = new DeliveryReportProcess(
                reportRemoteApi,
                adapterId,
                reportConfig.queueCapacity()
        );
        WorkerRouteRegistry routes = new WorkerRouteRegistry();
        WorkerConnectionMechanism connectionMechanism =
                new WorkerConnectionMechanism(
                        routes,
                        networkServer,
                        routeRemoteApi,
                        codec,
                        reportProcess,
                        adapterId,
                        sendTimeLimit
                );
        WorkerConnectionInboundHandler connectionInboundHandler =
                new WorkerConnectionInboundHandler(connectionMechanism);
        DeliveryCommandProcess commandProcess = new DeliveryCommandProcess(
                commandRemoteApi,
                connectionMechanism,
                reportProcess,
                codec,
                adapterId,
                commandConfig.consumeLimit(),
                commandConfig.queueCapacity()
        );

        List<ScheduledAdapterProcess> scheduledProcesses = processConfigs
                .stream()
                .map(processConfig -> switch (processConfig) {
                    case NettyAdapterProcessConfig.DeliveryCommand command ->
                            new ScheduledAdapterProcess(
                        DELIVERY_COMMAND_PROCESS_ID,
                        Duration.ZERO,
                        command.interval(),
                        BEFORE_NETWORK_CLOSE,
                        commandProcess
                    );
                    case NettyAdapterProcessConfig.DeliveryReport report ->
                            new ScheduledAdapterProcess(
                        DELIVERY_REPORT_PROCESS_ID,
                        report.interval(),
                        report.interval(),
                        AFTER_NETWORK_CLOSE,
                        reportProcess
                    );
                })
                .toList();

        AdapterProcessManager processManager = new AdapterProcessManager(
                adapterId,
                shutdownTimeout,
                scheduledProcesses
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
