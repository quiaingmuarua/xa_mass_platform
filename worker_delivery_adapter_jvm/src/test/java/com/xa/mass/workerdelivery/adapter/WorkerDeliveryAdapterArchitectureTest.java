package com.xa.mass.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");
    private static final Path CORE = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/application"
    );
    private static final Path HTTP = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/http"
    );
    private static final Path MESSAGE = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/message"
    );
    private static final Path DISPATCH = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/dispatch"
    );
    private static final Path SOCKET = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/socket"
    );
    private static final Path WEBSOCKET = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/websocket"
    );

    @Test
    void moduleDependsOnlyOnTheWorkerProtocolBoundary()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains(
                        "api project("
                                + "':worker_delivery_contract_jvm')"
                )
                .contains("io.netty:netty-transport")
                .contains("io.netty:netty-codec")
                .contains("io.netty:netty-codec-http")
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
                .doesNotContain("SeedResultRuntime");
    }

    @Test
    void concreteAdapterOwnsListenerLifecycleAndDispatchMechanism()
            throws IOException {
        String sources = readSources(CORE) + readSources(HTTP);
        assertThat(sources)
                .contains("interface WorkerDeliveryAdapter")
                .contains("final class WorkerDeliveryAdapterManager")
                .contains("register(WorkerDeliveryAdapter adapter)")
                .doesNotContain("interface WorkerConnection {")
                .doesNotContain("interface WorkerConnectionRegistry")
                .doesNotContain("NettyWorkerConnectionRegistry")
                .doesNotContain("WorkerSessionToken")
                .doesNotContain("generation")
                .doesNotContain("isCurrent")
                .doesNotContain("STALE_SESSION")
                .doesNotContain("WebSocketSession")
                .doesNotContain("WorkerDeliveryAdapterDefinition")
                .doesNotContain("WorkerDeliveryAdapterFactory")
                .doesNotContain("SmartLifecycle")
                .doesNotContain("@Configuration")
                .doesNotContain("@Component");

        String websocketTransport = readSources(WEBSOCKET);
        assertThat(websocketTransport)
                .contains("class WebSocketWorkerDeliveryAdapter")
                .contains("interface WorkerConnectionRegistry")
                .contains(
                        "Map<String, Channel> channels"
                )
                .contains("class NettyWorkerConnectionRegistry")
                .contains("ServerBootstrap")
                .contains("NioServerSocketChannel")
                .contains("newFixedThreadPool")
                .doesNotContain("WebSocketWorkerDeliveryAdapterFactory")
                .doesNotContain("AdapterRoundResult")
                .doesNotContain("interface WorkerConnection {")
                .doesNotContain("ConnectionHandle")
                .doesNotContain("NettyWebSocketWorkerConnection")
                .doesNotContain("SpringWebSocket")
                .doesNotContain("WorkerWebSocketEndpointConfigurer");

        String socketTransport = readSources(SOCKET);
        assertThat(socketTransport)
                .contains("class SocketWorkerDeliveryAdapter")
                .contains("Map<String, Channel> channels")
                .contains("class NettySocketWorkerConnectionRegistry")
                .contains("LineBasedFrameDecoder")
                .contains("StringDecoder")
                .contains("StringEncoder")
                .doesNotContain("ConnectionHandle")
                .doesNotContain("WorkerSessionToken");

        String dispatch = readSources(DISPATCH);
        assertThat(dispatch)
                .contains("interface WorkerCommandDelivery")
                .contains("final class WorkerDeliveryAdapterCore")
                .contains("void dispatchOnce")
                .doesNotContain("io.netty")
                .doesNotContain(".websocket")
                .doesNotContain(".socket")
                .doesNotContain("Channel");
    }

    @Test
    void messageMechanismIsStaticAndTransportIndependent()
            throws IOException {
        String messageSources = readSources(MESSAGE);
        assertThat(messageSources)
                .contains("interface WorkerConnectionMessageHandler")
                .contains("class WorkerConnectionMessageDispatcher")
                .contains("class TaskItemResultMessageHandler")
                .contains("class BoundedWorkerResultBuffer")
                .contains("Map.copyOf(indexed)")
                .doesNotContain("register(")
                .doesNotContain("unregister(")
                .doesNotContain("ServiceLoader")
                .doesNotContain("io.netty")
                .doesNotContain("org.springframework")
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce");

        String transportSources =
                readSources(WEBSOCKET) + readSources(SOCKET);
        assertThat(transportSources)
                .contains("decodeWorkerConnectionMessage")
                .contains("encodeWorkerConnectionMessage")
                .contains("decodeWorkerConnectionBind")
                .contains("WorkerConnectionMessageDispatcher")
                .doesNotContain(
                        "private final "
                                + "WebSocketWorkerDeliveryAdapter adapter"
                );
    }

    private static String readSources(Path root) throws IOException {
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
}
