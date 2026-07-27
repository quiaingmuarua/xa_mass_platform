package com.xa.mass.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");

    @Test
    void moduleDependsOnlyOnTheWorkerProtocolBoundary()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains(
                        "implementation project("
                                + "':worker_delivery_contract_jvm')"
                )
                .doesNotContain("project(':server_jvm')")
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("spring-boot-starter-data-redis")
                .doesNotContain("lettuce");

        String sources = readSources(SOURCE);
        assertThat(sources)
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("SeedResultRuntime");
    }

    @Test
    void websocketUsesTheHttpClientPortRatherThanServerInternals()
            throws IOException {
        String websocket = readSources(SOURCE.resolve(
                "com/xa/mass/workerdelivery/adapter/websocket"
        ));
        assertThat(websocket)
                .contains("WorkerDeliveryGatewayClient")
                .doesNotContain("HttpWorkerDeliveryGatewayClient")
                .doesNotContain("WorkerDeliveryService")
                .doesNotContain("WorkerDeliveryHttpContract");
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
