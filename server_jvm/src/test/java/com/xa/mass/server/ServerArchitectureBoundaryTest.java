package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerArchitectureBoundaryTest {

    private static final Path SERVER_SOURCE = Path.of("src/main/java");
    private static final Path KERNEL_SOURCE = Path.of(
            "../kernel_jvm/src/main/java"
    );
    private static final Path SHARED_REDIS = SERVER_SOURCE.resolve(
            "com/xa/mass/server/kernelredis"
    );
    private static final Path KERNEL_ASSEMBLY = SERVER_SOURCE.resolve(
            "com/xa/mass/server/kernelbinding/KernelOwnerAssemblyConfiguration.java"
    );
    private static final Path TASK_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/task/redis"
    );
    private static final Path WORKER_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/worker/redis"
    );
    private static final Path DELIVERY_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/delivery/redis"
    );
    private static final Path HTTP = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerdelivery/http"
    );
    private static final Path WEBSOCKET = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerdelivery/websocket"
    );

    @Test
    void serverDependsOnKernelContractsWithoutOwningRedisKeys()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains("implementation project(':kernel_jvm')")
                .doesNotContain("implementation project(':worker_jvm')")
                .contains("testImplementation project(':worker_jvm')");

        String serverSources = readSources(SERVER_SOURCE);
        assertThat(serverSources)
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:")
                .doesNotContain("KernelCommandClient")
                .doesNotContain("TaskDataRuntime")
                .doesNotContain("WorkerDeliveryRuntime");
        assertThat(readSourcesExcluding(
                SERVER_SOURCE,
                SHARED_REDIS,
                KERNEL_ASSEMBLY
        ))
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis");
    }

    @Test
    void kernelOwnerRedisImplementationsStayDirectional()
            throws IOException {
        assertThat(readSources(TASK_REDIS))
                .contains(":items")
                .contains(":item-score")
                .contains(":results")
                .doesNotContain("worker-commands")
                .doesNotContain("seed-results")
                .doesNotContain("\"wr:");
        assertThat(readSources(WORKER_REDIS))
                .contains("\"wr:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:");

        String delivery = readSources(DELIVERY_REDIS);
        assertThat(delivery)
                .contains("HGET")
                .contains("HDEL")
                .contains("commands().hscan(")
                .contains("commands().rpush(")
                .doesNotContain("\"tc:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:");
    }

    @Test
    void sharedRedisPackageOnlyBuildsConnectionAndHealth()
            throws IOException {
        assertThat(readSources(SHARED_REDIS))
                .doesNotContain(".hget(")
                .doesNotContain(".hset(")
                .doesNotContain(".zadd(")
                .doesNotContain(".rpush(")
                .doesNotContain(".eval(");
    }

    @Test
    void deliveryAccessProfilesDependOnTheServiceNotRedis()
            throws IOException {
        assertThat(readSources(HTTP))
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("SeedResultRuntime");
        assertThat(readSources(WEBSOCKET))
                .doesNotContain(".delivery.redis")
                .doesNotContain(".workerdelivery.http")
                .doesNotContain("io.lettuce")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("SeedResultRuntime")
                .doesNotContain("decodeDeliverSeed");
    }

    private static String readSources(Path root) throws IOException {
        StringBuilder sources = new StringBuilder();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> appendSource(sources, path));
        }
        return sources.toString();
    }

    private static String readSourcesExcluding(
            Path root,
            Path... excluded
    ) throws IOException {
        StringBuilder sources = new StringBuilder();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> java.util.Arrays.stream(excluded)
                            .noneMatch(path::startsWith))
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
