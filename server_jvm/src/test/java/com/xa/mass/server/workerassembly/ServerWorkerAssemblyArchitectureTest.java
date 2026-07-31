package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ServerWorkerAssemblyArchitectureTest {

    @Test
    void assemblyUsesOwnerContractsAndRealWorkerTransportOnly()
            throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/server/workerassembly"
        );
        String sources;
        try (Stream<Path> paths = Files.walk(root)) {
            StringBuilder combined = new StringBuilder();
            paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            combined.append(Files.readString(path));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
            sources = combined.toString();
        }

        assertThat(sources)
                .contains("WorkerResourceCatalog")
                .contains("WorkerRuntime")
                .contains("WebSocketWorkerTransport")
                .contains("OkHttpTextWebSocketClient");
        assertThat(sources)
                .doesNotContain("io.lettuce")
                .doesNotContain("kernelredis")
                .doesNotContain("ScoreBand")
                .doesNotContain("Pacer")
                .doesNotContain("ResourceCommandController")
                .doesNotContain("RuntimeApiHttpClient");
    }
}
