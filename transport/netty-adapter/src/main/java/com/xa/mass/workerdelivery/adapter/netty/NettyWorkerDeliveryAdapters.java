package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.AFTER_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.BEFORE_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.http.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.SocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.WebSocketNettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.ScheduledAdapterProcess;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Finite construction boundary for the built-in Netty Adapter types. */
public final class NettyWorkerDeliveryAdapters {

    private static final String TASK_COMMAND_PROCESS_ID = "TASK_COMMAND";
    private static final String TASK_REPORT_PROCESS_ID = "TASK_REPORT";

    private NettyWorkerDeliveryAdapters() {
    }

    public static WorkerDeliveryAdapter webSocket(
            String adapterId,
            WorkerDeliveryHttpClient httpClient,
            String listenHost,
            int listenPort,
            List<NettyAdapterProcessConfig> processConfigs,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        validateCommon(
                adapterId,
                httpClient,
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
                httpClient,
                processConfigs,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    public static WorkerDeliveryAdapter socket(
            String adapterId,
            WorkerDeliveryHttpClient httpClient,
            String listenHost,
            int listenPort,
            List<NettyAdapterProcessConfig> processConfigs,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        validateCommon(
                adapterId,
                httpClient,
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
                httpClient,
                processConfigs,
                sendTimeLimit,
                shutdownTimeout
        );
    }

    private static WorkerDeliveryAdapter build(
            NettyWorkerServer networkServer,
            String adapterId,
            WorkerDeliveryHttpClient httpClient,
            List<NettyAdapterProcessConfig> processConfigs,
            Duration sendTimeLimit,
            Duration shutdownTimeout
    ) {
        NettyAdapterProcessConfig.TaskCommand commandConfig = null;
        NettyAdapterProcessConfig.TaskReport reportConfig = null;
        for (NettyAdapterProcessConfig processConfig : processConfigs) {
            if (processConfig instanceof NettyAdapterProcessConfig.TaskCommand
                    taskCommand) {
                commandConfig = taskCommand;
            } else if (processConfig
                    instanceof NettyAdapterProcessConfig.TaskReport taskReport) {
                reportConfig = taskReport;
            }
        }

        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        DeliveryReportProcess reportProcess = new DeliveryReportProcess(
                httpClient,
                adapterId,
                Objects.requireNonNull(reportConfig, "reportConfig")
                        .queueCapacity()
        );
        WorkerRouteRegistry routes = new WorkerRouteRegistry();
        WorkerConnectionMechanism connectionMechanism =
                new WorkerConnectionMechanism(
                        routes,
                        networkServer,
                        httpClient,
                        codec,
                        reportProcess.acceptor(),
                        adapterId,
                        sendTimeLimit
                );
        DeliveryCommandProcess commandProcess = new DeliveryCommandProcess(
                httpClient,
                connectionMechanism,
                reportProcess.acceptor(),
                codec,
                adapterId,
                Objects.requireNonNull(commandConfig, "commandConfig")
                        .consumeLimit(),
                commandConfig.queueCapacity()
        );

        ArrayList<ScheduledAdapterProcess> scheduledProcesses =
                new ArrayList<>(processConfigs.size());
        for (NettyAdapterProcessConfig processConfig : processConfigs) {
            if (processConfig instanceof NettyAdapterProcessConfig.TaskCommand
                    taskCommand) {
                scheduledProcesses.add(new ScheduledAdapterProcess(
                        TASK_COMMAND_PROCESS_ID,
                        Duration.ZERO,
                        taskCommand.interval(),
                        BEFORE_NETWORK_CLOSE,
                        commandProcess
                ));
            } else if (processConfig
                    instanceof NettyAdapterProcessConfig.TaskReport taskReport) {
                scheduledProcesses.add(new ScheduledAdapterProcess(
                        TASK_REPORT_PROCESS_ID,
                        taskReport.interval(),
                        taskReport.interval(),
                        AFTER_NETWORK_CLOSE,
                        reportProcess
                ));
            }
        }

        AdapterProcessManager processManager = new AdapterProcessManager(
                adapterId,
                shutdownTimeout,
                List.copyOf(scheduledProcesses)
        );
        return new NettyWorkerDeliveryAdapter(
                adapterId,
                networkServer,
                connectionMechanism,
                processManager
        );
    }

    private static void validateCommon(
            String adapterId,
            WorkerDeliveryHttpClient httpClient,
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
        Objects.requireNonNull(httpClient, "httpClient");
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
        requireProcessConfigs(processConfigs);
    }

    private static void requireProcessConfigs(
            List<NettyAdapterProcessConfig> processConfigs
    ) {
        Objects.requireNonNull(processConfigs, "processConfigs");
        Set<Class<?>> observedTypes = new HashSet<>();
        for (NettyAdapterProcessConfig processConfig : processConfigs) {
            Objects.requireNonNull(processConfig, "processConfig");
            if (!observedTypes.add(processConfig.getClass())) {
                throw new IllegalArgumentException(
                        "Adapter process types must be unique"
                );
            }
        }
        if (observedTypes.size() != 2
                || !observedTypes.contains(
                NettyAdapterProcessConfig.TaskCommand.class)
                || !observedTypes.contains(
                NettyAdapterProcessConfig.TaskReport.class)) {
            throw new IllegalArgumentException(
                    "Adapter requires exactly one TASK_COMMAND and one "
                            + "TASK_REPORT process"
            );
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
