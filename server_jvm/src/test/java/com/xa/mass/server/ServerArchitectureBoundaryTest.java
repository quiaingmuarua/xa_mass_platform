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
    private static final Path TASK_DATA_REDIS = SOURCE_ROOT.resolve(
            "com/xa/mass/server/taskdata/redis"
    );
    private static final Path KERNEL_REDIS = SOURCE_ROOT.resolve(
            "com/xa/mass/server/kernelredis"
    );
    private static final Path TASK_DATA = SOURCE_ROOT.resolve(
            "com/xa/mass/server/taskdata"
    );
    private static final Path WORKER_DELIVERY_HTTP = SOURCE_ROOT.resolve(
            "com/xa/mass/server/workerdelivery/http"
    );
    private static final Path WORKER_DELIVERY_WEBSOCKET = SOURCE_ROOT.resolve(
            "com/xa/mass/server/workerdelivery/websocket"
    );

    @Test
    void onlyOwnerRedisPackagesAndSharedConnectionMayAccessRedis()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("project(\":kernel_jvm\")")
                .doesNotContain("implementation project(':worker_jvm')")
                .contains("testImplementation project(':worker_jvm')")
                .contains("project(':worker_delivery_contract_jvm')")
                .contains("spring-boot-starter-data-redis");

        StringBuilder nonRedisSources = new StringBuilder();
        StringBuilder allSources = new StringBuilder();
        try (var paths = Files.walk(SOURCE_ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        appendSource(allSources, path);
                        if (!path.startsWith(WORKER_DELIVERY_REDIS)
                                && !path.startsWith(TASK_DATA_REDIS)
                                && !path.startsWith(KERNEL_REDIS)) {
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
                .doesNotContain("ZRANGE")
                .doesNotContain("\"tc:")
                .doesNotContain("\"tr:")
                .doesNotContain(":groups")
                .doesNotContain(":item-score")
                .doesNotContain(":items");
    }

    @Test
    void taskDataRedisUsesOnlyItsOwnerKeys() throws IOException {
        assertThat(readSources(TASK_DATA_REDIS))
                .contains(":items")
                .contains(":item-score")
                .contains(":results")
                .contains(":groups")
                .doesNotContain("worker-commands")
                .doesNotContain("seed-results")
                .doesNotContain("candidate:")
                .doesNotContain(":task:score");
    }

    @Test
    void sharedRedisPackageDoesNotExecuteOwnerKeyOperations()
            throws IOException {
        assertThat(readSources(KERNEL_REDIS))
                .doesNotContain(".hget(")
                .doesNotContain(".hset(")
                .doesNotContain(".zadd(")
                .doesNotContain(".rpush(")
                .doesNotContain(".lpop(")
                .doesNotContain(".eval(");
    }

    @Test
    void workerDeliveryPackagesKeepDirectionalDependencies()
            throws IOException {
        assertThat(readSources(WORKER_DELIVERY_HTTP))
                .doesNotContain(".workerdelivery.redis")
                .doesNotContain("WorkerDeliveryRuntime")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis");

        assertThat(readSources(WORKER_DELIVERY_WEBSOCKET))
                .doesNotContain(".workerdelivery.redis")
                .doesNotContain(".workerdelivery.http")
                .doesNotContain("WorkerDeliveryRuntime")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("decodeDeliverSeed");
    }

    @Test
    void taskDataApplicationDoesNotCrossIntoControlOrWorkerDelivery()
            throws IOException {
        assertThat(readSources(TASK_DATA))
                .doesNotContain("KernelCommandClient")
                .doesNotContain(".workerdelivery.")
                .doesNotContain(".workerdelivery.redis");
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
