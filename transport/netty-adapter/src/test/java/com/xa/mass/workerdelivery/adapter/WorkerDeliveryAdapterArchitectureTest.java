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
    void moduleDependsOnlyOnItsProtocolAndNetworkLibraries()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains(
                        "api project(':transport:worker-delivery-contract')"
                )
                .contains("io.netty:netty-transport")
                .doesNotContain("spring-boot")
                .doesNotContain("project(':server_jvm')")
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("lettuce");

        assertThat(readSources(SOURCE))
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework");
    }

    @Test
    void frozenOwnerPackagesKeepTheirDependencyDirection()
            throws IOException {
        String application = readSources(APPLICATION);
        String adapter = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapter.java"
        ));
        String connection = readSources(CONNECTION);
        String network = readSources(NETWORK);
        String process = readSources(PROCESS);
        String remote = readSources(REMOTE);

        assertThat(application)
                .doesNotContain("adapter.netty")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("ScheduledExecutorService");
        assertThat(adapter)
                .contains("WorkerConnectionInboundHandler")
                .contains("WorkerConnectionMechanism")
                .contains("AdapterProcessManager")
                .contains("NettyWorkerServer")
                .doesNotContain("WebSocketNettyWorkerServer")
                .doesNotContain("SocketNettyWorkerServer")
                .doesNotContain("DeliveryCommandProcess")
                .doesNotContain("DeliveryReportProcess")
                .doesNotContain("FiniteQueue")
                .doesNotContain("ScheduledExecutorService");
        assertThat(connection)
                .doesNotContain("FiniteQueue")
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("java.net.http")
                .doesNotContain("io.netty.bootstrap")
                .doesNotContain("io.netty.handler.codec.http")
                .doesNotContain(".pipeline()")
                .doesNotContain(".writeAndFlush(");
        assertThat(network)
                .doesNotContain("internal.process")
                .doesNotContain("internal.remote")
                .doesNotContain("WorkerDeliveryCodec");
        assertThat(process)
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("java.net.http")
                .doesNotContain("java.net.URI")
                .doesNotContain("io.netty")
                .doesNotContain("ServerBootstrap");
        assertThat(remote)
                .doesNotContain("internal.process")
                .doesNotContain("internal.connection")
                .doesNotContain("internal.network")
                .doesNotContain("io.netty");
    }

    @Test
    void callbackAdapterDoesNotBecomeASecondConnectionOwner()
            throws IOException {
        String mechanism = read(CONNECTION.resolve(
                "WorkerConnectionMechanism.java"
        ));
        String handler = read(CONNECTION.resolve(
                "WorkerConnectionInboundHandler.java"
        ));

        assertThat(mechanism)
                .doesNotContain("extends SimpleChannelInboundHandler")
                .doesNotContain("channelRead0(")
                .doesNotContain("context.fireChannelInactive()");
        assertThat(handler)
                .contains("private final WorkerConnectionMechanism mechanism")
                .doesNotContain("WorkerRouteRegistry")
                .doesNotContain("WorkerRouteRemoteApi")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("DeliveryReportProcess")
                .doesNotContain("NettyWorkerServer")
                .doesNotContain("adapterId")
                .doesNotContain("sendTimeLimit");
    }

    @Test
    void remoteApisHideHttpFromProcessesAndConnection()
            throws IOException {
        String process = readSources(PROCESS);
        String connection = readSources(CONNECTION);
        String http = read(REMOTE.resolve("WorkerDeliveryHttpClient.java"));

        assertThat(process + connection)
                .doesNotContain("WorkerDeliveryHttpClient")
                .doesNotContain("java.net.http")
                .doesNotContain("statusCode")
                .doesNotContain("commands:consume")
                .doesNotContain("results:append")
                .doesNotContain("verify-binding");
        assertThat(http)
                .doesNotContain("DeliveryCommand")
                .doesNotContain("DeliveryReport")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("acceptedCount")
                .doesNotContain("verify-binding");
    }

    @Test
    void repositoryConsumersCannotImportInternalConstructionTypes()
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
                    .forEach(path -> collectInternalImport(
                            repository,
                            path,
                            violations
                    ));
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void removedOwnerPathsAndShutdownEscapeHatchesStayAbsent()
            throws IOException {
        String sources = readSources(NETTY);
        assertThat(sources)
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("DeliveryCommandPump")
                .doesNotContain("DeliveryReportPump")
                .doesNotContain("BoundedDeliveryReportQueue")
                .doesNotContain("syncUninterruptibly()");
        assertThat(Files.exists(REMOTE.resolve(
                "DeliveryCommandHttpContract.java"
        ))).isFalse();
        assertThat(Files.exists(REMOTE.resolve(
                "DeliveryReportHttpContract.java"
        ))).isFalse();
        assertThat(Files.exists(PROCESS.resolve(
                "TargetedDeliveryCommand.java"
        ))).isFalse();
    }

    private static void collectInternalImport(
            Path repository,
            Path path,
            List<Path> violations
    ) {
        try {
            if (Files.readString(path).contains(
                    "com.xa.mass.workerdelivery.adapter.netty.internal"
            )) {
                violations.add(repository.relativize(
                        path.toAbsolutePath().normalize()
                ));
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
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
