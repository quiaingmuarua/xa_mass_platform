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
    private static final Path GATEWAY = NETTY.resolve("internal/gateway");
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
                .contains("DeliveryCommandPump commandPump")
                .contains("DeliveryReportPump reportPump")
                .contains("NettyWorkerServer networkServer")
                .contains("networkServer.start(connectionMechanism)")
                .doesNotContain("WebSocketNettyWorkerServer")
                .doesNotContain("SocketNettyWorkerServer")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("TextWebSocketFrame")
                .doesNotContain("LineBasedFrameDecoder");
    }

    @Test
    void frozenLayersKeepAStableDependencyDirection() throws IOException {
        String connection = readSources(CONNECTION);
        String network = readSources(NETWORK);
        String gateway = readSources(GATEWAY);

        assertThat(connection)
                .contains("WorkerRouteRegistry")
                .contains("WorkerConnectionMechanism")
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
                .doesNotContain("internal.gateway")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("BoundedDeliveryReportQueue")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("DeliveryCommandPump")
                .doesNotContain("DeliveryReportPump");

        assertThat(gateway)
                .contains("DeliveryCommandPump")
                .contains("DeliveryReportPump")
                .contains("BoundedDeliveryReportQueue")
                .doesNotContain("io.netty")
                .doesNotContain("ServerBootstrap");
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
