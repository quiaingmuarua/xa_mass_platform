package com.xa.mass.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
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

        String sources = readSources(SOURCE);
        assertThat(sources)
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("WorkerResultRuntime")
                .doesNotContain("com.xa.mass.foundation")
                .doesNotContain("java.util.logging")
                .doesNotContain("LogUtils")
                .contains("class WorkerDeliveryAdapterException")
                .containsOnlyOnce("extends RuntimeException");
    }

    @Test
    void finiteFactoryBuildsOneSharedAdapterMechanism() throws IOException {
        String application = readSources(APPLICATION) + readSources(HTTP);
        assertThat(application)
                .contains("interface WorkerDeliveryAdapter")
                .contains("final class WorkerDeliveryAdapterManager")
                .doesNotContain("NettyWorkerDeliveryAdapters")
                .doesNotContain("NettyWorkerDeliveryAdapter")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("ScheduledExecutorService");

        String factory = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapters.java"
        ));
        String adapter = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapter.java"
        ));
        assertThat(factory)
                .contains("public final class NettyWorkerDeliveryAdapters")
                .contains("static WorkerDeliveryAdapter webSocket(")
                .contains("static WorkerDeliveryAdapter socket(")
                .contains("new NettyWorkerDeliveryAdapter(")
                .contains("AdapterNetworkProtocol.webSocket(")
                .contains("AdapterNetworkProtocol.socket(");
        assertThat(adapter)
                .contains("final class NettyWorkerDeliveryAdapter")
                .doesNotContain("public final class")
                .contains("WorkerRouteDirectory routes")
                .contains("DeliveryCommandPump commandPump")
                .contains("DeliveryReportPump reportPump")
                .contains("NettyServerLifecycle networkServer")
                .contains("ScheduledExecutorService scheduler")
                .contains("scheduleWithFixedDelay(")
                .doesNotContain("TransportKind")
                .doesNotContain("extends ");
        assertThat(directJavaFileNames(NETTY)).containsExactlyInAnyOrder(
                "NettyWorkerDeliveryAdapters.java",
                "NettyWorkerDeliveryAdapter.java"
        );
    }

    @Test
    void oneConnectionMechanismOwnsRouteIdentityAndResultIngress()
            throws IOException {
        String connection = readSources(CONNECTION);
        assertThat(connection)
                .containsOnlyOnce("class WorkerRouteDirectory")
                .containsOnlyOnce("class WorkerIdentityHandler")
                .containsOnlyOnce("class BoundWorkerHandler")
                .containsOnlyOnce("class WorkerConnectionHandlerFactory")
                .contains("verifiedWorkerIds")
                .contains("pendingVerifications")
                .contains("activeChannels")
                .contains("verifyWorkerRoute(")
                .contains("pipeline().replace(")
                .contains("extends SimpleChannelInboundHandler<String>")
                .contains("reportQueue.offer(encodedReport)")
                .contains("codec.encodeDeliveryCommand(command)")
                .doesNotContain("TextWebSocketFrame")
                .doesNotContain("CloseWebSocketFrame")
                .doesNotContain("LineBasedFrameDecoder")
                .doesNotContain("StringDecoder")
                .doesNotContain("StringEncoder")
                .doesNotContain("LineEncoder");
    }

    @Test
    void oneNetworkLifecycleOwnsResourcesAndProtocolsOwnOnlyFraming()
            throws IOException {
        String lifecycle = read(NETWORK.resolve(
                "NettyServerLifecycle.java"
        ));
        String protocol = read(NETWORK.resolve(
                "AdapterNetworkProtocol.java"
        ));
        String websocket = read(NETWORK.resolve(
                "WebSocketNetworkProtocol.java"
        ));
        String socket = read(NETWORK.resolve(
                "SocketNetworkProtocol.java"
        ));

        assertThat(lifecycle)
                .contains("new ServerBootstrap()")
                .contains("new MultiThreadIoEventLoopGroup(")
                .contains("Set<Channel> childChannels")
                .contains("networkProtocol.installPipeline(")
                .contains("networkProtocol.close(")
                .doesNotContain("HttpServerCodec")
                .doesNotContain("WebSocketServerProtocolHandler")
                .doesNotContain("LineBasedFrameDecoder")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("BoundedDeliveryReportQueue");
        assertThat(protocol)
                .contains("public sealed interface AdapterNetworkProtocol")
                .contains("permits WebSocketNetworkProtocol")
                .contains("SocketNetworkProtocol")
                .contains("void installPipeline(")
                .contains("void close(")
                .doesNotContain("TransportKind");
        assertThat(websocket)
                .contains("new HttpServerCodec()")
                .contains("new WebSocketServerProtocolHandler(")
                .contains("new TextWebSocketFrame(")
                .contains("new CloseWebSocketFrame(")
                .contains("extends MessageToMessageCodec<WebSocketFrame, String>")
                .doesNotContain("LineBasedFrameDecoder")
                .doesNotContain("LineEncoder")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("BoundedDeliveryReportQueue")
                .doesNotContain("WorkerDeliveryCodec");
        assertThat(socket)
                .contains("new LineBasedFrameDecoder(")
                .contains("new StringDecoder(")
                .contains("new LineEncoder(")
                .contains("LineSeparator.UNIX")
                .doesNotContain("HttpServerCodec")
                .doesNotContain("WebSocketFrame")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("BoundedDeliveryReportQueue")
                .doesNotContain("WorkerDeliveryCodec");
    }

    @Test
    void gatewayPumpsStayTransportNeutralAndOldOwnersAreGone()
            throws IOException {
        String gateway = readSources(GATEWAY);
        String netty = readSources(NETTY);
        assertThat(gateway)
                .contains("final class DeliveryCommandPump")
                .contains("final class DeliveryReportPump")
                .contains("class BoundedDeliveryReportQueue")
                .contains("interface DeliveryCommandTarget")
                .doesNotContain("io.netty")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("Future.get(")
                .doesNotContain("newFixedThreadPool");
        assertThat(netty)
                .doesNotContain("class WebSocketWorkerDeliveryAdapter")
                .doesNotContain("class SocketWorkerDeliveryAdapter")
                .doesNotContain("class WebSocketWorkerRouteDirectory")
                .doesNotContain("class SocketWorkerRouteDirectory")
                .doesNotContain("class WebSocketWorkerIdentityHandler")
                .doesNotContain("class SocketWorkerIdentityHandler")
                .doesNotContain("class WebSocketBoundWorkerHandler")
                .doesNotContain("class SocketBoundWorkerHandler")
                .doesNotContain("class WebSocketNettyServer")
                .doesNotContain("class SocketNettyServer")
                .doesNotContain("IdentityPhase")
                .doesNotContain("WorkerConnectionSession")
                .doesNotContain("TextFrameStrategy")
                .doesNotContain("AbstractNettyAdapter")
                .doesNotContain("RouteDirectoryBridge")
                .doesNotContain("ServiceLoader")
                .doesNotContain("Class.forName")
                .doesNotContain("unbind(")
                .doesNotContain("verifiedUntil");
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

    private static Set<String> directJavaFileNames(Path root)
            throws IOException {
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
