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
    private static final Path HTTP = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/http"
    );
    private static final Path NETTY = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/netty"
    );
    private static final Path PROCESS = NETTY.resolve("internal/process");
    private static final Path CONNECTION = NETTY.resolve(
            "internal/connection"
    );
    private static final Path NETWORK = NETTY.resolve("internal/network");

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
        String application = readSources(APPLICATION) + readSources(HTTP);
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
                .contains("WorkerDeliveryHttpClient httpClient")
                .contains("DeliveryReportProcess.Acceptor reportAcceptor")
                .doesNotContain("FiniteQueue")
                .doesNotContain("CommandSource")
                .doesNotContain("ResultIngress")
                .doesNotContain("WorkerDeliveryGatewayClient")
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
                .doesNotContain("implements Runnable")
                .doesNotContain("ProcessRegistry")
                .doesNotContain("DeliveryCommandPump")
                .doesNotContain("DeliveryReportPump")
                .doesNotContain("BoundedDeliveryReportQueue")
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
        String http = read(HTTP.resolve("WorkerDeliveryHttpClient.java"));

        assertThat(commands)
                .contains("FiniteQueue<TargetedDeliveryCommand>")
                .contains("WorkerDeliveryHttpClient httpClient")
                .contains("DeliveryCommandHttpContract httpContract")
                .contains("Target target")
                .contains("DeliveryReportProcess.Acceptor reportAcceptor")
                .doesNotContain("FiniteQueue<String>")
                .doesNotContain("DeliveryReportHttpContract");
        assertThat(reports)
                .contains("FiniteQueue<String>")
                .contains("WorkerDeliveryHttpClient httpClient")
                .contains("DeliveryReportHttpContract httpContract")
                .doesNotContain("TargetedDeliveryCommand")
                .doesNotContain("DeliveryCommandHttpContract");
        assertThat(connection)
                .contains("WorkerDeliveryHttpClient httpClient")
                .contains("postEmptyAsync(path)")
                .doesNotContain("DeliveryCommandHttpContract")
                .doesNotContain("DeliveryReportHttpContract");
        assertThat(http)
                .contains("private final HttpClient http")
                .contains("postJson(")
                .contains("postEmptyAsync(")
                .doesNotContain("DeliveryCommand")
                .doesNotContain("DeliveryReport")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("acceptedCount")
                .doesNotContain("verify-binding");
        assertThat(Files.exists(APPLICATION.resolve(
                "WorkerDeliveryGatewayClient.java"
        ))).isFalse();
        assertThat(Files.exists(HTTP.resolve(
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
