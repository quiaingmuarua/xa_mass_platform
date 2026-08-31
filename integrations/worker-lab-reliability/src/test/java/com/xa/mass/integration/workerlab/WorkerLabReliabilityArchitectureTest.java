package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerLabReliabilityArchitectureTest {

    @Test
    void integrationUsesOnlyPublicHttpAndDeliveryJsonSupport()
            throws IOException {
        Path project = Path.of("").toAbsolutePath().normalize();
        String build = Files.readString(project.resolve("build.gradle"));
        String sources;
        try (var files = Files.walk(project.resolve("src/main/java"))) {
            sources = files.filter(Files::isRegularFile)
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        assertThat(build)
                .contains("project(':transport:worker-delivery-contract')")
                .doesNotContain("scenario_workers_jvm")
                .doesNotContain("server_jvm")
                .doesNotContain("kernel_jvm")
                .doesNotContain("netty-adapter");
        assertThat(sources)
                .doesNotContain("com.xa.mass.scenarioworkers")
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("com.xa.mass.adapter")
                .doesNotContain("com.xa.mass.worker.javase");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
