package com.xa.mass.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");
    private static final Path APPLICATION = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/application"
    );
    private static final Path HTTP = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/http"
    );
    private static final Path INTERNAL = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/internal"
    );
    private static final Path SOCKET = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/socket"
    );
    private static final Path WEBSOCKET = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/websocket"
    );
    private static final String INTERNAL_RUNTIME_IMPORT =
            "import com.xa.mass.workerdelivery.adapter.internal."
                    + "NettyWorkerDeliveryAdapterRuntime;";

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
    void concreteAdaptersAreStableFacadesOverOneNettyRuntime()
            throws IOException {
        String application = readSources(APPLICATION) + readSources(HTTP);
        assertThat(application)
                .contains("interface WorkerDeliveryAdapter")
                .contains("final class WorkerDeliveryAdapterManager")
                .contains("register(WorkerDeliveryAdapter adapter)")
                .doesNotContain("NettyWorkerDeliveryAdapterRuntime")
                .doesNotContain("BoundWorkerConnectionDirectory")
                .doesNotContain("WorkerConnectionSession")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("@Configuration")
                .doesNotContain("@Component");

        String websocket = readSources(WEBSOCKET);
        String socket = readSources(SOCKET);
        for (String facade : java.util.List.of(websocket, socket)) {
            assertThat(facade)
                    .contains(INTERNAL_RUNTIME_IMPORT)
                    .contains("private final NettyWorkerDeliveryAdapterRuntime")
                    .contains("runtime.start()")
                    .contains("runtime.close()")
                    .doesNotContain("io.netty")
                    .doesNotContain("ServerBootstrap")
                    .doesNotContain("ScheduledExecutorService")
                    .doesNotContain("WorkerConnectionSession")
                    .doesNotContain("Map<String, Channel>")
                    .doesNotContain("BindingPhase");
        }
        assertThat(websocket)
                .contains("class WebSocketWorkerDeliveryAdapter")
                .contains("NettyWorkerDeliveryAdapterRuntime.webSocket(");
        assertThat(socket)
                .contains("class SocketWorkerDeliveryAdapter")
                .contains("NettyWorkerDeliveryAdapterRuntime.socket(");
        assertThat(occurrences(readSources(SOURCE), INTERNAL_RUNTIME_IMPORT))
                .isEqualTo(2);
    }

    @Test
    void internalRuntimeOwnsOneNettySpecificMechanismWithoutAnSpi()
            throws IOException {
        String internal = readSources(INTERNAL);
        assertThat(internal)
                .contains(
                        "public final class "
                                + "NettyWorkerDeliveryAdapterRuntime"
                )
                .contains("ServerBootstrap")
                .contains("NioServerSocketChannel")
                .contains("newScheduledThreadPool(")
                .contains("new MultiThreadIoEventLoopGroup(")
                .contains("scheduleWithFixedDelay(")
                .contains("LineBasedFrameDecoder")
                .contains("WebSocketServerProtocolHandler")
                .contains("WriteTimeoutHandler")
                .contains("Set<Channel> childChannels")
                .contains("track(Channel channel)")
                .contains("closeChildChannels(")
                .contains("final class DeliveryCommandPump")
                .contains("final class DeliveryReportPump")
                .contains("final class BoundedDeliveryReportQueue")
                .contains("final class BoundWorkerConnectionDirectory")
                .contains("final class WorkerConnectionSession")
                .doesNotContain("public final class DeliveryCommandPump")
                .doesNotContain("public final class DeliveryReportPump")
                .doesNotContain("public final class BoundedDeliveryReportQueue")
                .doesNotContain("public interface TextFrameStrategy")
                .doesNotContain("WorkerDeliveryAdapterFactory")
                .doesNotContain("WorkerDeliveryAdapterDefinition")
                .doesNotContain("ServiceLoader")
                .doesNotContain("Class.forName")
                .doesNotContain("Future.get(")
                .doesNotContain("newFixedThreadPool")
                .doesNotContain("deliveryExecutor");

        assertThat(occurrences(
                internal,
                "public final class NettyWorkerDeliveryAdapterRuntime"
        )).isEqualTo(1);
    }

    @Test
    void oneConnectionSessionOwnsFixedIdentityAndTaskReportIngress()
            throws IOException {
        String internal = readSources(INTERNAL);
        assertThat(internal)
                .contains("enum BindingPhase")
                .contains("UNBOUND")
                .contains("VERIFYING")
                .contains("BOUND")
                .contains("decodeDeliveryReport(")
                .contains("report.dst()")
                .contains("reportQueue.offer(encodedDeliveryReport)")
                .contains("verifyWorkerRoute(")
                .contains("encodeDeliveryCommand(command)")
                .contains("WORKER_CONNECTION_IDENTIFY_EVENT_CODE")
                .contains("WORKER_CONNECTION_CLOSE_EVENT_CODE")
                .doesNotContain("AdapterWorkerEventDispatcher")
                .doesNotContain("definitions.get(")
                .doesNotContain("AdapterMessageDefinitionManager")
                .doesNotContain("WorkerConnectionMessage")
                .doesNotContain("WorkerConnectionBind")
                .doesNotContain("decodeWorkerConnectionBind")
                .doesNotContain("verifyWorkerBinding")
                .doesNotContain("WorkerSessionToken")
                .doesNotContain("generation")
                .doesNotContain("TERMINATED")
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

    private static int occurrences(String value, String fragment) {
        return value.split(
                java.util.regex.Pattern.quote(fragment),
                -1
        ).length - 1;
    }
}
