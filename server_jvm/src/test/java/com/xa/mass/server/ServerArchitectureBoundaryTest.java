package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerArchitectureBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path WORKER_DELIVERY_REDIS = SOURCE_ROOT.resolve(
            "com/xa/mass/server/workerdelivery/redis"
    );
    private static final Path WORKER_DELIVERY_HTTP = SOURCE_ROOT.resolve(
            "com/xa/mass/server/workerdelivery/http"
    );
    private static final Path WORKER_DELIVERY_PROTOCOL = SOURCE_ROOT.resolve(
            "com/xa/mass/server/workerdelivery/protocol"
    );

    @Test
    void onlyWorkerDeliveryRedisPackageMayAccessRedis() throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("project(\":kernel_jvm\")")
                .contains("spring-boot-starter-data-redis");

        StringBuilder nonRedisSources = new StringBuilder();
        StringBuilder allSources = new StringBuilder();
        try (var paths = Files.walk(SOURCE_ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        appendSource(allSources, path);
                        if (!path.startsWith(WORKER_DELIVERY_REDIS)) {
                            appendSource(nonRedisSources, path);
                        }
                    });
        }
        assertThat(nonRedisSources.toString())
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("io.lettuce");
        assertThat(allSources.toString())
                .doesNotContain("kernel_design")
                .doesNotContain(".score.")
                .doesNotContain(".pacer.");
    }

    @Test
    void workerDeliveryRedisUsesOnlyItsDirectionalOperations()
            throws IOException {
        assertThat(readSources(WORKER_DELIVERY_REDIS))
                .contains("HGET")
                .contains("HDEL")
                .contains("commands().hscan(")
                .contains("commands().rpush(")
                .doesNotContain("HSET")
                .doesNotContain("commands().hset(")
                .doesNotContain("LPOP")
                .doesNotContain("commands().lpop(")
                .doesNotContain("ZADD")
                .doesNotContain("ZRANGE");
    }

    @Test
    void workerDeliveryPackagesKeepDirectionalDependencies()
            throws IOException {
        assertThat(readSources(WORKER_DELIVERY_PROTOCOL))
                .doesNotContain("org.springframework")
                .doesNotContain("jakarta.")
                .doesNotContain(".workerdelivery.http")
                .doesNotContain(".workerdelivery.redis")
                .doesNotContain("WorkerDeliveryService")
                .doesNotContain("WorkerDeliveryRuntime");

        assertThat(readSources(WORKER_DELIVERY_HTTP))
                .doesNotContain(".workerdelivery.redis")
                .doesNotContain("WorkerDeliveryRuntime")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis");
    }

    private static String readSources(Path root) throws IOException {
        StringBuilder sources = new StringBuilder();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> appendSource(sources, path));
        }
        return sources.toString();
    }

    private static void appendSource(StringBuilder sources, Path path) {
        try {
            sources.append(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + path, error);
        }
    }
}
