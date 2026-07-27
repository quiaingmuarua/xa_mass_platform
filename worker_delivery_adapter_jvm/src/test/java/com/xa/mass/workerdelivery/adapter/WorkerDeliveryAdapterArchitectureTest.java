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
                .contains("org.springframework:spring-websocket")
                .doesNotContain("spring-boot")
                .doesNotContain("project(':server_jvm')")
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("lettuce");

        String sources = readSources(SOURCE);
        assertThat(sources)
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("SeedResultRuntime");
    }

    @Test
    void coreContainsNoFrameworkHostThreadOrLifecycleTypes()
            throws IOException {
        String sources = readSources(CORE) + readSources(HTTP);
        assertThat(sources)
                .contains("interface WorkerConnection")
                .contains("interface WorkerSessionDirectory")
                .contains("final class WorkerDeliveryAdapter")
                .doesNotContain("WebSocketSession")
                .doesNotContain("SmartLifecycle")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("Executors.")
                .doesNotContain("@Configuration")
                .doesNotContain("@Component");

        assertThat(readSources(WEBSOCKET))
                .contains("WebSocketSession")
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("WorkerCommandPage")
                .doesNotContain("dispatchOnce")
                .doesNotContain("ArrayBlockingQueue")
                .doesNotContain("\"3001\"")
                .doesNotContain("cursor");
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
