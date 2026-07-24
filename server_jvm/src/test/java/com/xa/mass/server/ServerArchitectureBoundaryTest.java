package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerArchitectureBoundaryTest {

    @Test
    void serverHasNoKernelImplementationOrRedisDependency() throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("project(\":kernel_jvm\")")
                .doesNotContain("spring-data-redis")
                .doesNotContain("jedis")
                .doesNotContain("lettuce");

        StringBuilder sources = new StringBuilder();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> appendSource(sources, path));
        }
        assertThat(sources.toString())
                .doesNotContain("kernel_design")
                .doesNotContain(".score.")
                .doesNotContain(".pacer.")
                .doesNotContain("redis.clients")
                .doesNotContain("org.springframework.data.redis");
    }

    private static void appendSource(StringBuilder sources, Path path) {
        try {
            sources.append(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + path, error);
        }
    }
}
