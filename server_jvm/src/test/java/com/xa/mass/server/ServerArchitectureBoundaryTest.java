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
    private static final Path DELIVERY_ASSEMBLY = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerdelivery/"
                    + "WorkerDeliveryOwnerAssemblyConfiguration.java"
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
            "com/xa/mass/server/api/v1/workerdelivery"
    );
    private static final Path DELIVERY = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerdelivery"
    );
    private static final Path DELIVERY_APPLICATION = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerdelivery/application"
    );
    private static final Path DELIVERY_ADAPTER_HOST = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerdelivery/adapter"
    );
    private static final Path ADAPTER_SOURCE = Path.of(
            "../worker_delivery_adapter_jvm/src/main/java"
    );

    @Test
    void serverDependsOnKernelContractsWithoutOwningRedisKeys()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains("implementation project(':kernel_jvm')")
                .contains("implementation project(':worker_delivery_adapter_jvm')")
                .doesNotContain("spring-boot-starter-websocket")
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
                KERNEL_ASSEMBLY,
                DELIVERY_ASSEMBLY
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
                .contains("@RestController")
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("SeedResultRuntime");
        assertThat(readSources(DELIVERY))
                .doesNotContain("@RestController")
                .doesNotContain("WorkerDeliveryHttpContract");
        assertThat(readSources(DELIVERY_APPLICATION))
                .doesNotContain(".server.api")
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce");
    }

    @Test
    void serverOnlyHostsTheAdapterMechanism() throws IOException {
        String host = readSources(DELIVERY_ADAPTER_HOST);
        assertThat(host)
                .contains("WorkerDeliveryAdapterManager")
                .contains("manager.start()")
                .contains("manager.close()")
                .doesNotContain("dispatchOnce")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("WebSocketSession")
                .doesNotContain("WorkerWebSocketHandler")
                .doesNotContain("TextMessage")
                .doesNotContain("SeedResult")
                .doesNotContain("\"3001\"")
                .doesNotContain("ArrayBlockingQueue");

        String adapter = readSources(ADAPTER_SOURCE);
        assertThat(adapter)
                .contains("class WorkerWebSocketHandler")
                .contains("class NettyWorkerConnectionRegistry")
                .contains("class WebSocketWorkerDeliveryAdapter")
                .contains("interface WorkerDeliveryAdapter")
                .contains("class WorkerDeliveryAdapterCore")
                .contains("io.netty")
                .doesNotContain("org.springframework")
                .doesNotContain("SpringWebSocketWorkerConnection")
                .doesNotContain("ScheduledWorkerDeliveryAdapter");
    }

    @Test
    void deliveryOwnerAssemblyDoesNotCreateOtherKernelOwners()
            throws IOException {
        String deliveryAssembly = Files.readString(DELIVERY_ASSEMBLY);
        assertThat(deliveryAssembly)
                .contains("RedisWorkerCommandRuntime")
                .contains("RedisSeedResultRuntime")
                .doesNotContain("TaskRuntime")
                .doesNotContain("TaskResourceCatalog")
                .doesNotContain("WorkerRuntime")
                .doesNotContain("WorkerResourceCatalog")
                .doesNotContain("PythonKernelHttpTransport")
                .doesNotContain("ScoreBandCore");

        assertThat(Files.readString(KERNEL_ASSEMBLY))
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("SeedResultRuntime")
                .doesNotContain("RedisWorkerCommandRuntime")
                .doesNotContain("RedisSeedResultRuntime");
    }

    private static String readSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return "";
        }
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
