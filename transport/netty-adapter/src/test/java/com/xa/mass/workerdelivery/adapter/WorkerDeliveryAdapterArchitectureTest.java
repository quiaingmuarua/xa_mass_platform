package com.xa.mass.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");
    private static final Path APPLICATION = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/application"
    );
    private static final Path NETTY = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/netty"
    );
    private static final Path PROCESS = NETTY.resolve("internal/process");
    private static final Path CONNECTION = NETTY.resolve(
            "internal/connection"
    );
    private static final Path NETWORK = NETTY.resolve("internal/network");
    private static final Path REMOTE = NETTY.resolve("internal/remote");

    @Test
    void moduleDependsOnlyOnTheWorkerProtocolBoundary()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains("archivesName.set('xa-mass-netty-adapter')")
                .contains("api project(':worker_delivery_contract_jvm')")
                .contains("io.netty:netty-transport")
                .contains("io.netty:netty-codec")
                .contains("io.netty:netty-codec-http")
                .doesNotContain("foundation_jvm")
                .doesNotContain("spring-boot")
                .doesNotContain("project(':server_jvm')")
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("lettuce");

        assertThat(readSources(SOURCE))
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework")
                .doesNotContain("com.xa.mass.foundation")
                .doesNotContain("java.util.logging")
                .doesNotContain("LogUtils");
    }

    @Test
    void adapterAggregateDependsOnOwnerContractsNotPhysicalImplementations()
            throws IOException {
        String application = readSources(APPLICATION);
        assertThat(application)
                .contains("interface WorkerDeliveryAdapter")
                .contains("final class WorkerDeliveryAdapterManager")
                .doesNotContain("NettyWorkerDeliveryAdapters")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("ScheduledExecutorService");

        String adapter = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapter.java"
        ));
        assertThat(adapter)
                .contains("WorkerConnectionMechanism connectionMechanism")
                .contains("AdapterProcessManager processManager")
                .contains("NettyWorkerServer networkServer")
                .contains("networkServer.start(connectionMechanism)")
                .contains("processManager.start()")
                .contains("processManager.close()")
                .doesNotContain("ScheduledAdapterProcess")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("ScheduledFuture")
                .doesNotContain("WebSocketNettyWorkerServer")
                .doesNotContain("SocketNettyWorkerServer")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("DeliveryCommandProcess")
                .doesNotContain("DeliveryReportProcess")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("FiniteQueue")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("TextWebSocketFrame")
                .doesNotContain("LineBasedFrameDecoder");
    }

    @Test
    void frozenLayersKeepAStableDependencyDirection() throws IOException {
        String connection = readSources(CONNECTION);
        String network = readSources(NETWORK);
        String process = readSources(PROCESS);
        String processManager = read(PROCESS.resolve(
                "AdapterProcessManager.java"
        ));

        assertThat(connection)
                .contains("WorkerRouteRegistry")
                .contains("WorkerConnectionMechanism")
                .contains("WorkerRouteRemoteApi routeRemoteApi")
                .contains("DeliveryReportProcess reportProcess")
                .doesNotContain("FiniteQueue")
                .doesNotContain("CommandSource")
                .doesNotContain("ResultIngress")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("java.net.http")
                .doesNotContain("java.net.URI")
                .doesNotContain("statusCode")
                .doesNotContain("commands:consume")
                .doesNotContain("results:append")
                .doesNotContain("verify-binding")
                .doesNotContain("DeliveryCommandHttpContract")
                .doesNotContain("DeliveryReportHttpContract")
                .doesNotContain("interface Target")
                .doesNotContain("interface Acceptor")
                .doesNotContain("io.netty.bootstrap")
                .doesNotContain("EventLoopGroup")
                .doesNotContain("io.netty.handler.codec.http")
                .doesNotContain("TextWebSocketFrame")
                .doesNotContain("CloseWebSocketFrame")
                .doesNotContain("LineBasedFrameDecoder")
                .doesNotContain("StringDecoder")
                .doesNotContain("LineEncoder")
                .doesNotContain(".pipeline()")
                .doesNotContain(".writeAndFlush(");

        assertThat(network)
                .contains("interface NettyWorkerServer extends AutoCloseable")
                .doesNotContain("internal.process")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("FiniteQueue")
                .doesNotContain("DeliveryCommandProcess")
                .doesNotContain("DeliveryReportProcess")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("DeliveryCommandPump")
                .doesNotContain("DeliveryReportPump");

        assertThat(process)
                .contains("final class FiniteQueue<T>")
                .contains("final class DeliveryCommandProcess")
                .contains("final class DeliveryReportProcess")
                .contains("final class AdapterProcessManager")
                .contains("interface AdapterProcess")
                .contains("record ScheduledAdapterProcess")
                .contains("record TargetedDeliveryCommand")
                .contains("FiniteQueue<TargetedDeliveryCommand>")
                .contains("FiniteQueue<String>")
                .contains("DeliveryCommandRemoteApi remoteApi")
                .contains("DeliveryReportRemoteApi remoteApi")
                .contains("WorkerConnectionMechanism connectionMechanism")
                .contains("DeliveryReportProcess reportProcess")
                .doesNotContain("implements Runnable")
                .doesNotContain("ProcessRegistry")
                .doesNotContain("DeliveryCommandPump")
                .doesNotContain("DeliveryReportPump")
                .doesNotContain("BoundedDeliveryReportQueue")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("java.net.http")
                .doesNotContain("java.net.URI")
                .doesNotContain("statusCode")
                .doesNotContain("commands:consume")
                .doesNotContain("results:append")
                .doesNotContain("verify-binding")
                .doesNotContain("DeliveryCommandHttpContract")
                .doesNotContain("DeliveryReportHttpContract")
                .doesNotContain("interface Target")
                .doesNotContain("interface Acceptor")
                .doesNotContain("io.netty")
                .doesNotContain("ServerBootstrap");

        assertThat(processManager)
                .contains("List<ScheduledAdapterProcess> processes")
                .contains("ScheduledExecutorService scheduler")
                .contains("void start()")
                .contains("void quiesce(QuiescePhase phase)")
                .contains("public void close()")
                .contains("scheduler.scheduleWithFixedDelay(")
                .doesNotContain("ScheduledFuture")
                .doesNotContain("Map<String")
                .doesNotContain("NettyWorkerServer")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("DeliveryCommandProcess")
                .doesNotContain("DeliveryReportProcess");

        assertThat(readSources(NETTY.resolve("internal/gateway")))
                .isEmpty();
    }

    @Test
    void remoteContractsBelongToOwnersAndHttpClientStaysMechanical()
            throws IOException {
        String commands = read(PROCESS.resolve(
                "DeliveryCommandProcess.java"
        ));
        String reports = read(PROCESS.resolve(
                "DeliveryReportProcess.java"
        ));
        String connection = read(CONNECTION.resolve(
                "WorkerConnectionMechanism.java"
        ));
        String commandRemote = read(REMOTE.resolve(
                "DeliveryCommandRemoteApi.java"
        ));
        String reportRemote = read(REMOTE.resolve(
                "DeliveryReportRemoteApi.java"
        ));
        String routeRemote = read(REMOTE.resolve(
                "WorkerRouteRemoteApi.java"
        ));
        String http = read(REMOTE.resolve("WorkerDeliveryHttpClient.java"));
        String factory = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapters.java"
        ));

        assertThat(commands)
                .contains("FiniteQueue<TargetedDeliveryCommand>")
                .contains("DeliveryCommandRemoteApi remoteApi")
                .contains("WorkerConnectionMechanism connectionMechanism")
                .contains("DeliveryReportProcess reportProcess")
                .contains("connectionMechanism.deliver(")
                .contains("reportProcess.ingress(")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("DeliveryCommandHttpContract")
                .doesNotContain("interface Target")
                .doesNotContain("interface Acceptor")
                .doesNotContain("FiniteQueue<String>")
                .doesNotContain("DeliveryReportHttpContract");
        assertThat(reports)
                .contains("FiniteQueue<String>")
                .contains("DeliveryReportRemoteApi remoteApi")
                .contains("remoteApi.append(adapterId, batch)")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("DeliveryReportHttpContract")
                .doesNotContain("interface Acceptor")
                .doesNotContain("TargetedDeliveryCommand")
                .doesNotContain("DeliveryCommandHttpContract");
        assertThat(connection)
                .contains("WorkerRouteRemoteApi routeRemoteApi")
                .contains("DeliveryReportProcess reportProcess")
                .contains("routeRemoteApi.verify(adapterId, workerId)")
                .contains("reportProcess.ingress(")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("postEmptyAsync(")
                .doesNotContain("DeliveryCommandHttpContract")
                .doesNotContain("DeliveryReportHttpContract")
                .doesNotContain("interface Target")
                .doesNotContain("interface Acceptor");
        assertThat(commandRemote)
                .contains("DeliveryCommandHttpContract httpContract")
                .contains("commands:consume")
                .contains("httpClient.postJson(")
                .contains("200")
                .contains("REMOTE_API_UNAVAILABLE")
                .contains("REMOTE_API_PROTOCOL_ERROR");
        assertThat(reportRemote)
                .contains("DeliveryReportHttpContract httpContract")
                .contains("results:append")
                .contains("httpClient.postJson(")
                .contains("202")
                .contains("REMOTE_API_UNAVAILABLE")
                .contains("REMOTE_API_PROTOCOL_ERROR");
        assertThat(routeRemote)
                .contains("verify-binding")
                .contains("httpClient.postEmptyAsync(")
                .contains("204")
                .contains("WORKER_ROUTE_REJECTED")
                .contains("REMOTE_API_UNAVAILABLE")
                .contains("REMOTE_API_PROTOCOL_ERROR");
        assertThat(http)
                .contains("private final HttpClient http")
                .contains("postJson(")
                .contains("postEmptyAsync(")
                .contains("expectedStatus")
                .contains("UnexpectedStatus")
                .contains("RequestFailure")
                .doesNotContain("DeliveryCommand")
                .doesNotContain("DeliveryReport")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("acceptedCount")
                .doesNotContain("verify-binding");
        assertThat(factory)
                .contains("URI remoteApiBaseUrl")
                .contains("Duration remoteRequestTimeout")
                .contains("new WorkerDeliveryHttpClient(")
                .contains("new DeliveryCommandRemoteApi(httpClient, codec)")
                .contains("new DeliveryReportRemoteApi(httpClient)")
                .contains("new WorkerRouteRemoteApi(httpClient)")
                .doesNotContain("WorkerDeliveryHttpClient httpClient,");
        assertThat(Files.exists(APPLICATION.resolve(
                "WorkerDeliveryGatewayClient.java"
        ))).isFalse();
        assertThat(Files.exists(REMOTE.resolve(
                "HttpWorkerDeliveryGatewayClient.java"
        ))).isFalse();
    }

    @Test
    void repositoryConsumersCannotImportTheInternalConstructionSurface()
            throws IOException {
        Path repository = Path.of("../..").toAbsolutePath().normalize();
        Path moduleSource = SOURCE.toAbsolutePath().normalize();
        List<Path> violations = new ArrayList<>();
        try (var paths = Files.walk(repository)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> normalized(path).contains(
                            "/src/main/java/"
                    ))
                    .filter(path -> !path.toAbsolutePath()
                            .normalize()
                            .startsWith(moduleSource))
                    .forEach(path -> {
                        try {
                            if (Files.readString(path).contains(
                                    "com.xa.mass.workerdelivery.adapter."
                                            + "netty.internal"
                            )) {
                                violations.add(repository.relativize(
                                        path.toAbsolutePath().normalize()
                                ));
                            }
                        } catch (IOException error) {
                            throw new IllegalStateException(error);
                        }
                    });
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void shutdownAndWebSocketTextHaveNoProductionTestEscapeHatches()
            throws IOException {
        String netty = readSources(NETTY);
        String webSocket = read(NETWORK.resolve(
                "WebSocketNettyWorkerServer.java"
        ));
        assertThat(netty)
                .doesNotContain("trackedConnectionCount")
                .doesNotContain("activeConnectionCount")
                .doesNotContain("verifiedWorkerCount")
                .doesNotContain("pendingVerificationCount")
                .doesNotContain("syncUninterruptibly()");
        assertThat(Files.exists(SOURCE.resolve("module-info.java")))
                .isFalse();
        assertThat(webSocket)
                .contains("channel.writeAndFlush(message)")
                .contains("output.add(new TextWebSocketFrame(message))")
                .doesNotContain(
                        "writeAndFlush(new TextWebSocketFrame(message))"
                );
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/');
    }

    private static String readSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return "";
        }
        StringBuilder sources = new StringBuilder();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            sources.append(Files.readString(path));
                        } catch (IOException error) {
                            throw new IllegalStateException(error);
                        }
                    });
        }
        return sources.toString();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
