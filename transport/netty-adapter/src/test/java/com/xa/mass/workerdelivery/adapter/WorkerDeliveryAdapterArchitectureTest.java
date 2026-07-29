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
    private static final Path RESULT = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/result"
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
                        "archivesName.set('xa-mass-netty-adapter')"
                )
                .contains(
                        "api project("
                                + "':worker_delivery_contract_jvm')"
                )
                .contains("api project(':foundation_jvm')")
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
                .doesNotContain("WorkerResultRuntime");
        assertThat(sources)
                .doesNotContain("java.util.logging")
                .doesNotContain("LogUtils");
        assertThat(sources)
                .contains("class WorkerDeliveryAdapterException")
                .containsOnlyOnce("extends CodedRuntimeException");
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
                .contains("newScheduledThreadPool")
                .contains("commandTask = scheduler.scheduleWithFixedDelay")
                .contains("resultTask = scheduler.scheduleWithFixedDelay")
                .contains("WriteTimeoutHandler")
                .doesNotContain("WebSocketWorkerDeliveryAdapterFactory")
                .doesNotContain("AdapterRoundResult")
                .doesNotContain("newFixedThreadPool")
                .doesNotContain("deliveryExecutor")
                .doesNotContain("Future.get(")
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
                .contains("WriteTimeoutHandler")
                .doesNotContain("newFixedThreadPool")
                .doesNotContain("deliveryExecutor")
                .doesNotContain("Future.get(")
                .doesNotContain("ConnectionHandle")
                .doesNotContain("WorkerSessionToken");

        String dispatch = readSources(DISPATCH);
        assertThat(dispatch)
                .contains("interface WorkerCommandDelivery")
                .contains("final class WorkerCommandLoop")
                .contains("ArrayDeque")
                .contains("STARTED")
                .contains("RETRY_LATER")
                .contains("UNKNOWN")
                .contains("public synchronized void run()")
                .doesNotContain("WorkerDeliveryAdapterCore")
                .doesNotContain("dispatchOnce")
                .doesNotContain("ExecutorService")
                .doesNotContain("Future")
                .doesNotContain("io.netty")
                .doesNotContain(".websocket")
                .doesNotContain(".socket")
                .doesNotContain("Channel");

        String result = readSources(RESULT);
        assertThat(result)
                .contains("final class BoundedWorkerResultQueue")
                .contains("final class WorkerResultLoop")
                .contains("ArrayDeque")
                .contains("String encodedWorkerResult")
                .contains("public synchronized void run()")
                .doesNotContain("decodeWorkerResult")
                .doesNotContain("encodeWorkerResult")
                .doesNotContain("io.netty")
                .doesNotContain("Channel");
    }

    @Test
    void resultIngressValidatesButForwardsTheOriginalPayload()
            throws IOException {
        String messageSources = readSources(MESSAGE);
        assertThat(messageSources)
                .contains("class WorkerResultPayloadHandler")
                .contains("String encodedWorkerResult")
                .contains("decodeWorkerResult(encodedWorkerResult)")
                .contains("resultQueue.offer(encodedWorkerResult)")
                .doesNotContain("encodeWorkerResult")
                .doesNotContain("AdapterMessageDefinition")
                .doesNotContain("WorkerConnectionMessage")
                .doesNotContain("ServiceLoader")
                .doesNotContain("io.netty")
                .doesNotContain("org.springframework")
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce");

        assertThat(readSources(RESULT))
                .contains("class BoundedWorkerResultQueue")
                .doesNotContain("WorkerResultSource")
                .doesNotContain("WorkerConnectionMessage");

        String transportSources =
                readSources(WEBSOCKET) + readSources(SOCKET);
        assertThat(transportSources)
                .contains("decodeWorkerConnectionBind")
                .contains("resultHandler.handle(")
                .doesNotContain("decodeWorkerConnectionMessage")
                .doesNotContain("encodeWorkerConnectionMessage")
                .doesNotContain("AdapterMessageDefinitionManager")
                .doesNotContain("WorkerConnectionMessage")
                .doesNotContain("encodeWorkerResult")
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
