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
    private static final Path KERNEL_BINDING = SERVER_SOURCE.resolve(
            "com/xa/mass/server/kernelbinding"
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
    private static final Path WORKER_CATALOG_REDIS = WORKER_REDIS.resolve(
            "RedisWorkerResourceCatalog.java"
    );
    private static final Path WORKER_SCORE_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/score/redis"
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
    private static final Path WORKER_ASSEMBLY = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerassembly"
    );
    private static final Path WORKER_IDENTITY = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workeridentity"
    );
    private static final Path WORKER_BINDING = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerbinding"
    );
    private static final Path RUNTIME_VIEW = SERVER_SOURCE.resolve(
            "com/xa/mass/server/runtimeview"
    );
    private static final Path RUNTIME_VIEW_HTTP = SERVER_SOURCE.resolve(
            "com/xa/mass/server/api/v1/runtimeview"
    );
    private static final Path ADAPTER_SOURCE = Path.of(
            "../transport/netty-adapter/src/main/java"
    );

    @Test
    void serverDependsOnKernelContractsWithoutOwningRedisKeys()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains("implementation project(':kernel_jvm')")
                .contains(
                        "implementation project(':scenario_workers_jvm')"
                )
                .contains("implementation project(':transport:netty-adapter')")
                .contains(
                        "testImplementation "
                                + "project(':transport:java-worker')"
                )
                .doesNotContain(
                        "implementation "
                                + "project(':transport:java-worker')"
                )
                .doesNotContain("com.googlecode.libphonenumber")
                .doesNotContain("foundation_jvm")
                .doesNotContain("spring-boot-starter-websocket");

        String serverSources = readSources(SERVER_SOURCE);
        assertThat(serverSources)
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:")
                .doesNotContain("KernelCommandClient")
                .doesNotContain("TaskDataRuntime")
                .doesNotContain("WorkerDeliveryRuntime");
        assertThat(serverSources)
                .contains("class ServerException")
                .doesNotContain("com.xa.mass.foundation");
        assertThat(readSourcesExcluding(
                SERVER_SOURCE,
                SHARED_REDIS,
                KERNEL_ASSEMBLY,
                DELIVERY_ASSEMBLY,
                WORKER_IDENTITY,
                WORKER_BINDING
        ))
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis");
        assertThat(readSourcesExcluding(
                SERVER_SOURCE,
                WORKER_IDENTITY,
                WORKER_BINDING
        )).doesNotContain("\"wi:");
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
        assertThat(readSources(WORKER_SCORE_REDIS))
                .contains("\"wr:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:");

        String delivery = readSources(DELIVERY_REDIS);
        assertThat(delivery)
                .contains("HGET")
                .contains("HDEL")
                .contains("commands().hrandfieldWithvalues(")
                .contains("commands().rpush(")
                .doesNotContain("commands().hscan(")
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
    void runtimeViewUsesOnlyTheWorkerCatalogBoundedSample()
            throws IOException {
        String runtimeView = readSources(RUNTIME_VIEW)
                + readSources(RUNTIME_VIEW_HTTP);
        assertThat(runtimeView)
                .contains("WorkerResourceCatalog")
                .doesNotContain(".worker.redis")
                .doesNotContain("RedisWorkerResourceCatalog")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("WorkerScore")
                .doesNotContain("DeliveryCommand")
                .doesNotContain("DeliveryReport")
                .doesNotContain("TaskRuntime")
                .doesNotContain("Transport");

        String catalog = Files.readString(WORKER_CATALOG_REDIS);
        assertThat(occurrences(
                catalog,
                "hrandfieldWithvalues"
        )).isEqualTo(1);
        assertThat(catalog)
                .doesNotContain(".hscan(")
                .doesNotContain(".scan(")
                .doesNotContain(".keys(")
                .doesNotContain(".hlen(")
                .doesNotContain("WorkerScore")
                .doesNotContain("transport");
    }

    @Test
    void deliveryAccessProfilesDependOnTheServiceNotRedis()
            throws IOException {
        assertThat(readSources(HTTP))
                .contains("@RestController")
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("WorkerResultRuntime");
        assertThat(readSources(DELIVERY))
                .doesNotContain("@RestController")
                .doesNotContain("WorkerDeliveryHttpContract");
        assertThat(readSources(DELIVERY_APPLICATION))
                .doesNotContain(".server.api")
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce");
    }

    @Test
    void serverOnlyComposesAdapterAndBuiltInWorkerMechanisms()
            throws IOException {
        String host = readSources(DELIVERY_ADAPTER_HOST);
        assertThat(host)
                .contains("WorkerDeliveryAdapterManager")
                .contains("NettyWorkerDeliveryAdapters")
                .doesNotContain("dispatchOnce")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("WebSocketWorkerDeliveryAdapter")
                .doesNotContain("SocketWorkerDeliveryAdapter")
                .doesNotContain("WebSocketNettyServer")
                .doesNotContain("SocketNettyServer")
                .doesNotContain("adapter.netty.internal")
                .doesNotContain("io.netty")
                .doesNotContain("WebSocketSession")
                .doesNotContain("WorkerWebSocketHandler")
                .doesNotContain("TextMessage")
                .doesNotContain("DeliveryReport")
                .doesNotContain("\"23002\"")
                .doesNotContain("ArrayBlockingQueue");

        String assembly = readSources(WORKER_ASSEMBLY);
        assertThat(assembly)
                .contains("groupInitializer.initialize()")
                .contains("adapterManager.start()")
                .contains("adapterManager.close()")
                .contains("WorkerResourceCatalog")
                .contains("ScenarioWorkers")
                .contains("properties.groupConfigJson()")
                .contains("properties.workerConfigJson()")
                .contains("properties.runtimeApiBaseUrl()")
                .doesNotContain("ScenarioWorkerBundles")
                .doesNotContain("ScenarioWorkerBundleConfig")
                .doesNotContain("WebSocketWorkerDeliveryAdapter")
                .doesNotContain("adapter.netty.internal")
                .doesNotContain("PHONE_NUMBER")
                .doesNotContain("STRING_UTILS")
                .doesNotContain("workerIdPrefix")
                .doesNotContain("workerCount")
                .doesNotContain("OkHttpTextWebSocketClient")
                .doesNotContain("PhoneNumberCapability")
                .doesNotContain("StringUtilityCapability")
                .doesNotContain("WorkerEventHandler")
                .doesNotContain("WorkerEventDefinition")
                .doesNotContain("dispatchOnce")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("ScoreBand")
                .doesNotContain("Pacer");

        String adapter = readSources(ADAPTER_SOURCE);
        assertThat(adapter)
                .contains("class NettyWorkerDeliveryAdapter")
                .contains("interface WorkerDeliveryAdapter")
                .contains("class NettyWorkerDeliveryAdapters")
                .contains("interface NettyWorkerServer")
                .contains("class WebSocketNettyWorkerServer")
                .contains("class SocketNettyWorkerServer")
                .contains("class WorkerRouteRegistry")
                .contains("class WorkerConnectionMechanism")
                .contains("class DeliveryCommandPump")
                .contains("class DeliveryReportPump")
                .contains("BoundedDeliveryReportQueue")
                .doesNotContain("class NettyServerLifecycle")
                .doesNotContain("interface AdapterNetworkProtocol")
                .doesNotContain("class WebSocketNetworkProtocol")
                .doesNotContain("class SocketNetworkProtocol")
                .doesNotContain("class WorkerRouteDirectory")
                .doesNotContain("class WorkerIdentityHandler")
                .doesNotContain("class BoundWorkerHandler")
                .doesNotContain("class WorkerConnectionHandlerFactory")
                .doesNotContain("class WebSocketWorkerDeliveryAdapter")
                .doesNotContain("class SocketWorkerDeliveryAdapter")
                .doesNotContain("class WebSocketWorkerRouteDirectory")
                .doesNotContain("class SocketWorkerRouteDirectory")
                .doesNotContain("class WebSocketWorkerIdentityHandler")
                .doesNotContain("class SocketWorkerIdentityHandler")
                .doesNotContain("WorkerConnectionSession")
                .doesNotContain("WorkerConnectionSessionFactory")
                .doesNotContain("BoundWorkerConnectionDirectory")
                .doesNotContain("TextFrameStrategy")
                .doesNotContain("WorkerDeliveryAdapterCore")
                .doesNotContain("NettyWorkerDeliveryAdapterRuntime")
                .doesNotContain("TransportKind")
                .doesNotContain("AbstractNettyAdapter")
                .doesNotContain("interface WorkerNetworkServer")
                .doesNotContain("AdapterWorkerEventDispatcher")
                .doesNotContain("WorkerCommandLoop")
                .doesNotContain("WorkerResultLoop")
                .doesNotContain("BoundedWorkerResultQueue")
                .doesNotContain("deliveryExecutor")
                .doesNotContain("Future.get(")
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
                .contains("RedisWorkerResultRuntime")
                .doesNotContain("TaskRuntime")
                .doesNotContain("TaskResourceCatalog")
                .doesNotContain("WorkerRuntime")
                .doesNotContain("WorkerResourceCatalog")
                .doesNotContain("PythonKernelHttpTransport")
                .doesNotContain("ScoreBandCore");

        assertThat(Files.readString(KERNEL_ASSEMBLY))
                .contains("RedisWorkerRuntime")
                .contains("RedisWorkerResourceCatalog")
                .doesNotContain("HttpWorkerRuntime")
                .doesNotContain("HttpWorkerResourceCatalog")
                .doesNotContain("AssembledWorkerResourceCatalog")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("WorkerResultRuntime")
                .doesNotContain("RedisWorkerCommandRuntime")
                .doesNotContain("RedisWorkerResultRuntime");
    }

    @Test
    void pythonKernelBindingContainsOnlyTaskControlAdapters()
            throws IOException {
        assertThat(readSources(KERNEL_BINDING))
                .contains("HttpTaskRuntime")
                .contains("HttpTaskLifecycleCommands")
                .contains("HttpTaskDispatchWakeCommands")
                .doesNotContain("HttpWorkerRuntime")
                .doesNotContain("HttpWorkerResourceCatalog")
                .doesNotContain("AssembledWorkerResourceCatalog")
                .doesNotContain("\"/worker-groups")
                .doesNotContain("\"/workers");
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

    private static int occurrences(String value, String fragment) {
        return value.split(
                java.util.regex.Pattern.quote(fragment),
                -1
        ).length - 1;
    }
}
