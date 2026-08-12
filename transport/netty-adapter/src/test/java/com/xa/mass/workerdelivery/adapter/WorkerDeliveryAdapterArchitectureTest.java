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
    private static final Path WEBSOCKET = NETTY.resolve(
            "internal/websocket"
    );
    private static final Path SOCKET = NETTY.resolve("internal/socket");

    @Test
    void moduleDependsOnlyOnTheWorkerProtocolBoundary()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains("archivesName.set('xa-mass-netty-adapter')")
                .contains(
                        "api project(':worker_delivery_contract_jvm')"
                )
                .contains("io.netty:netty-transport")
                .contains("io.netty:netty-codec")
                .contains("io.netty:netty-codec-http")
                .doesNotContain("foundation_jvm")
                .doesNotContain("spring-boot")
                .doesNotContain("spring-websocket")
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
    void finiteFactoryIsTheOnlySupportedNettyConstructionSurface()
            throws IOException {
        String application = readSources(APPLICATION) + readSources(HTTP);
        assertThat(application)
                .contains("interface WorkerDeliveryAdapter")
                .contains("final class WorkerDeliveryAdapterManager")
                .contains("register(WorkerDeliveryAdapter adapter)")
                .doesNotContain("NettyWorkerDeliveryAdapters")
                .doesNotContain("WebSocketWorkerDeliveryAdapter")
                .doesNotContain("SocketWorkerDeliveryAdapter")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("@Configuration")
                .doesNotContain("@Component");

        String netty = readSources(NETTY);
        assertThat(netty)
                .contains("public final class NettyWorkerDeliveryAdapters")
                .contains("static WorkerDeliveryAdapter webSocket(")
                .contains("static WorkerDeliveryAdapter socket(")
                .contains("final class WebSocketWorkerDeliveryAdapter")
                .contains("final class SocketWorkerDeliveryAdapter")
                .doesNotContain(
                        "public final class WebSocketWorkerDeliveryAdapter"
                )
                .doesNotContain(
                        "public final class SocketWorkerDeliveryAdapter"
                )
                .doesNotContain("NettyWorkerDeliveryAdapterRuntime")
                .doesNotContain("TransportKind")
                .doesNotContain("AbstractNettyAdapter")
                .doesNotContain("interface WorkerNetworkServer")
                .doesNotContain("ServiceLoader")
                .doesNotContain("Class.forName");
        assertThat(directJavaFileNames(NETTY)).containsExactlyInAnyOrder(
                "NettyWorkerDeliveryAdapters.java",
                "WebSocketWorkerDeliveryAdapter.java",
                "SocketWorkerDeliveryAdapter.java"
        );
    }

    @Test
    void eachConcreteAdapterOwnsItsLifecycleAndExactNetworkServer()
            throws IOException {
        String websocket = read(
                NETTY.resolve("WebSocketWorkerDeliveryAdapter.java")
        );
        String socket = read(
                NETTY.resolve("SocketWorkerDeliveryAdapter.java")
        );
        for (String adapter : java.util.List.of(websocket, socket)) {
            assertThat(adapter)
                    .contains("private volatile WorkerDeliveryAdapterState")
                    .contains("ScheduledExecutorService scheduler")
                    .contains("ScheduledFuture<?> commandTask")
                    .contains("ScheduledFuture<?> reportTask")
                    .contains("DeliveryCommandPump commandPump")
                    .contains("DeliveryReportPump reportPump")
                    .contains("scheduleWithFixedDelay(")
                    .contains("networkServer.start()")
                    .contains("networkServer.close()")
                    .doesNotContain("extends ")
                    .doesNotContain("NettyWorkerDeliveryAdapterRuntime")
                    .doesNotContain("TransportKind");
        }
        assertThat(websocket)
                .contains("WebSocketNettyServer networkServer")
                .contains("WebSocketWorkerRouteDirectory routes")
                .doesNotContain("new SocketNettyServer(");
        assertThat(socket)
                .contains("SocketNettyServer networkServer")
                .contains("SocketWorkerRouteDirectory routes")
                .doesNotContain("WebSocketNettyServer");
    }

    @Test
    void physicalNetworkServersOwnIndependentPipelinesAndChannels()
            throws IOException {
        String websocket = read(WEBSOCKET.resolve(
                "WebSocketNettyServer.java"
        ));
        String socket = read(SOCKET.resolve("SocketNettyServer.java"));

        assertThat(websocket)
                .contains("new HttpServerCodec()")
                .contains("new WebSocketServerProtocolHandler(")
                .contains("new WebSocketWorkerIdentityHandler(")
                .contains("Set<Channel> childChannels")
                .contains("new MultiThreadIoEventLoopGroup(")
                .doesNotContain("LineBasedFrameDecoder")
                .doesNotContain("StringDecoder")
                .doesNotContain("new SocketWorkerIdentityHandler(");
        assertThat(socket)
                .contains("new LineBasedFrameDecoder(")
                .contains("new StringDecoder(")
                .contains("new StringEncoder(")
                .contains("new SocketWorkerIdentityHandler(")
                .contains("Set<Channel> childChannels")
                .contains("new MultiThreadIoEventLoopGroup(")
                .doesNotContain("HttpServerCodec")
                .doesNotContain("WebSocketServerProtocolHandler")
                .doesNotContain("WebSocketWorkerIdentityHandler");
    }

    @Test
    void protocolOwnersAreIndependentAndGatewayMechanismsStayNeutral()
            throws IOException {
        String gateway = readSources(GATEWAY);
        String websocket = readSources(WEBSOCKET);
        String socket = readSources(SOCKET);

        assertThat(readSources(NETTY.resolve("internal/connection")))
                .isEmpty();
        assertThat(gateway)
                .contains("final class DeliveryCommandPump")
                .contains("final class DeliveryReportPump")
                .contains("class BoundedDeliveryReportQueue")
                .contains("interface DeliveryCommandTarget")
                .doesNotContain(".internal.connection")
                .doesNotContain("io.netty")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("Future.get(")
                .doesNotContain("newFixedThreadPool")
                .doesNotContain("deliveryExecutor");
        assertThat(websocket)
                .contains("class WebSocketNettyServer")
                .contains("class WebSocketWorkerRouteDirectory")
                .contains("class WebSocketWorkerIdentityHandler")
                .contains("class WebSocketBoundWorkerHandler")
                .contains("enum WebSocketCloseReason")
                .contains("new TextWebSocketFrame(")
                .contains("new CloseWebSocketFrame(")
                .doesNotContain("LineBasedFrameDecoder")
                .doesNotContain(".internal.socket");
        assertThat(socket)
                .contains("class SocketNettyServer")
                .contains("class SocketWorkerRouteDirectory")
                .contains("class SocketWorkerIdentityHandler")
                .contains("class SocketBoundWorkerHandler")
                .contains("new LineBasedFrameDecoder(")
                .doesNotContain("TextWebSocketFrame")
                .doesNotContain(".internal.websocket");
    }

    @Test
    void eachProtocolPipelineOwnsIdentityTransitionAndBoundResultIngress()
            throws IOException {
        String netty = readSources(NETTY);
        assertThat(netty)
                .contains("verifiedWorkerIds")
                .contains("pendingVerifications")
                .contains("activeChannels")
                .contains("isRouteVerified(")
                .contains("beginVerification(")
                .contains("completeVerificationAndActivate(")
                .contains("pipeline().replace(")
                .contains("WebSocketBoundWorkerHandler")
                .contains("SocketBoundWorkerHandler")
                .contains("decodeDeliveryReport(")
                .contains("report.dst()")
                .contains("reportQueue.offer(")
                .contains("verifyWorkerRoute(")
                .contains("encodeDeliveryCommand(command)")
                .contains("WORKER_CONNECTION_IDENTIFY_EVENT_CODE")
                .contains("WORKER_CONNECTION_CLOSE_EVENT_CODE")
                .doesNotContain("IdentityPhase")
                .doesNotContain("setAutoRead(false)")
                .doesNotContain("WebSocketBoundWorkerDirectory")
                .doesNotContain("SocketBoundWorkerDirectory")
                .doesNotContain("WorkerConnectionSession")
                .doesNotContain("WorkerConnectionSessionFactory")
                .doesNotContain("BoundWorkerConnectionDirectory")
                .doesNotContain("TextFrameStrategy")
                .doesNotContain("interface WorkerRouteDirectory")
                .doesNotContain("AbstractWorkerRouteDirectory")
                .doesNotContain("SharedWorkerRouteDirectory")
                .doesNotContain("RouteDirectoryBridge")
                .doesNotContain("AdapterWorkerEventDispatcher")
                .doesNotContain("definitions.get(")
                .doesNotContain("AdapterMessageDefinitionManager")
                .doesNotContain("WorkerConnectionMessage")
                .doesNotContain("WorkerConnectionBind")
                .doesNotContain("decodeWorkerConnectionBind")
                .doesNotContain("verifyWorkerBinding")
                .doesNotContain("WorkerSessionToken")
                .doesNotContain("authToken")
                .doesNotContain("verifiedUntil")
                .doesNotContain("expireVerified")
                .doesNotContain("unbind(")
                .doesNotContain("WorkerResultPayloadHandler")
                .doesNotContain("WorkerResultAction")
                .doesNotContain("ServiceLoader");
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
