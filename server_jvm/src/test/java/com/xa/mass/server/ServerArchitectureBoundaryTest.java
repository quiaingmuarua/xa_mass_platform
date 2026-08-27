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
    private static final Path PACER_SOURCE = Path.of(
            "../kernel_pacer_jvm/src/main/java"
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
    private static final Path KERNEL_PACER = SERVER_SOURCE.resolve(
            "com/xa/mass/server/kernelpacer"
    );
    private static final Path KERNEL_PACER_ASSEMBLY = KERNEL_PACER.resolve(
            "KernelPacerAssembly.java"
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
    private static final Path TASK_SCORE_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/score/redis/RedisTaskScoreBandCore.java"
    );
    private static final Path TASK_ITEM_SCORE_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/score/redis/RedisTaskItemScoreBandCore.java"
    );
    private static final Path WORKER_SCORE_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/score/redis/RedisWorkerScoreCore.java"
    );
    private static final Path DELIVERY_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/delivery/redis"
    );
    private static final Path SERVICEABILITY_REDIS = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/serviceability/redis"
    );
    private static final Path SERVICEABILITY = KERNEL_SOURCE.resolve(
            "com/xa/mass/kernel/serviceability"
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

    @Test
    void adapterBatchApiHasNoControlOnlyCompatibilityRoutes()
            throws IOException {
        String sources = readSources(HTTP);
        assertThat(sources)
                .contains("/commands:consume")
                .contains("/results:append")
                .doesNotContain("control-commands:consume")
                .doesNotContain("control-results:append")
                .doesNotContain("workerCommandsByWorkerId");
        assertThat(readSources(SERVER_SOURCE.resolve(
                "com/xa/mass/server/api/v1"
        )))
                .doesNotContain("WorkerControlController")
                .doesNotContain("/workers/direct-calls");
    }
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
    private static final Path TASK_DATA = SERVER_SOURCE.resolve(
            "com/xa/mass/server/taskdata"
    );
    private static final Path DIRECT_CALL = SERVER_SOURCE.resolve(
            "com/xa/mass/server/directcall"
    );
    private static final Path WORKER_SCHEDULING = SERVER_SOURCE.resolve(
            "com/xa/mass/server/workerscheduling"
    );
    private static final Path WORKER_SCHEDULING_HTTP = SERVER_SOURCE.resolve(
            "com/xa/mass/server/api/v1/WorkerSchedulingController.java"
    );
    @Test
    void serverUsesKernelContractsAndKeepsRedisInNamedOwners()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains("implementation project(':kernel_jvm')")
                .doesNotContain("scenario_workers_jvm")
                .doesNotContain("scenario_rpc_jvm")
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
                .doesNotContain(
                        "platform.adapter.worker-connection.changed"
                )
                .doesNotContain(
                        "platform.adapter.worker-delivery.expired"
                )
                .doesNotContain("worker-serviceability-evidence:v1")
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
        assertThat(readSources(SERVICEABILITY_REDIS))
                .contains("io.lettuce")
                .doesNotContain("WorkerScoreCore")
                .doesNotContain("TaskRuntime")
                .doesNotContain("Pacer");
        assertThat(readSources(SERVICEABILITY))
                .contains("WorkerServiceabilityRuntime")
                .doesNotContain("WorkerServiceabilityDispatchPolicy")
                .doesNotContain("WorkerServiceabilityResultHandler")
                .doesNotContain("WorkerServiceabilityDispatchHandler")
                .doesNotContain("ServiceLoader")
                .doesNotContain("ExecutorService")
                .doesNotContain("ScheduledExecutor");
    }

    @Test
    void kernelOwnerRedisImplementationsStayDirectional()
            throws IOException {
        assertThat(readSources(TASK_REDIS))
                .contains("RedisKeyspace")
                .contains(":task:")
                .contains(":items")
                .contains(":results")
                .doesNotContain("worker-commands")
                .doesNotContain("seed-results")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:");
        assertThat(readSources(WORKER_REDIS))
                .contains("RedisKeyspace")
                .contains(":worker:groups")
                .contains(":worker:metadata:")
                .contains(":worker:properties:")
                .contains(":worker:id_owners")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:");
        assertThat(readSources(TASK_ITEM_SCORE_REDIS))
                .contains("RedisKeyspace")
                .contains(":task:")
                .contains(":item_score")
                .doesNotContain(":items")
                .doesNotContain(":results");
        assertThat(readSources(WORKER_SCORE_REDIS))
                .contains("RedisKeyspace")
                .contains(":worker:score:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:");
        assertThat(readSources(TASK_SCORE_REDIS))
                .contains("RedisKeyspace")
                .contains(":task:score")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:");

        String delivery = readSources(DELIVERY_REDIS);
        assertThat(delivery)
                .contains("HGET")
                .contains("HDEL")
                .contains("commands().hrandfieldWithvalues(")
                .contains("commands().rpush(")
                .contains(":delivery:commands:")
                .contains(":result:routing:")
                .doesNotContain("commands().hscan(")
                .doesNotContain("\"tc:")
                .doesNotContain("\"tr:")
                .doesNotContain("\"wr:")
                .doesNotContain("\"wd:")
                .doesNotContain("\"rr:");
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
    void runtimeViewUsesBoundedOwnersWithoutCreatingRuntimeTruth()
            throws IOException {
        String runtimeView = readSources(RUNTIME_VIEW)
                + readSources(RUNTIME_VIEW_HTTP);
        assertThat(runtimeView)
                .contains("WorkerResourceCatalog")
                .contains("TaskResourceCatalog")
                .contains("TaskScoreBandCore")
                .contains("previewScoreStates")
                .contains("WorkerSchedulingService")
                .contains("WorkerNetworkObservationService")
                .contains("DirectCallService")
                .contains(
                        "platform.adapter.worker-connections.snapshot"
                )
                .doesNotContain(".worker.redis")
                .doesNotContain(".task.redis")
                .doesNotContain("RedisWorkerResourceCatalog")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("WorkerScoreCore")
                .doesNotContain("RedisTaskScoreBandCore")
                .doesNotContain("WorkerGroupTaskCallRegistrationService")
                .doesNotContain("DirectCallRegistry")
                .doesNotContain("WorkerRouteRegistry")
                .doesNotContain("NettyWorkerServer")
                .doesNotContain(".score()")
                .doesNotContain(".timeMillis()")
                .doesNotContain(".suffix()")
                .doesNotContain("DeliveryCommand")
                .doesNotContain("DeliveryReport")
                .doesNotContain(".register(")
                .doesNotContain(".createTask(")
                .doesNotContain(".approveTask(")
                .doesNotContain(".hscan(")
                .doesNotContain(".scan(")
                .doesNotContain(".keys(")
                .doesNotContain("Transport");

        String catalog = Files.readString(WORKER_CATALOG_REDIS);
        assertThat(occurrences(
                catalog,
                "hrandfieldWithvalues"
        )).isEqualTo(2);
        assertThat(catalog)
                .doesNotContain(".hscan(")
                .doesNotContain(".scan(")
                .doesNotContain(".keys(")
                .doesNotContain(".hlen(")
                .doesNotContain("WorkerScore")
                .doesNotContain("transport");
    }

    @Test
    void taskResultExportUsesOnlyTaskOwnerContracts()
            throws IOException {
        String taskData = readSources(TASK_DATA);
        assertThat(taskData)
                .contains("scanTaskItemSuccessResults")
                .contains("getScoreStates")
                .contains("TaskResourceCatalog")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("RedisTaskRuntime")
                .doesNotContain("RedisTaskScoreBandCore")
                .doesNotContain(".hscan(")
                .doesNotContain(".hgetall(")
                .doesNotContain("com.xa.mass.transport")
                .doesNotContain("ScenarioWorkers");
    }

    @Test
    void deliveryAccessProfilesDependOnTheServiceNotRedis()
            throws IOException {
        assertThat(readSources(HTTP))
                .contains("@RestController")
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce")
                .doesNotContain("WorkerCommandRuntime")
                .doesNotContain("TaskResultRuntime");
        assertThat(readSources(DELIVERY))
                .doesNotContain("@RestController")
                .doesNotContain("WorkerDeliveryHttpContract");
        assertThat(readSources(DELIVERY_APPLICATION))
                .contains("WorkerServiceabilityRuntime")
                .doesNotContain("WorkerChangeReportIngress")
                .doesNotContain("WorkerChangeInbox")
                .doesNotContain(".server.api")
                .doesNotContain(".delivery.redis")
                .doesNotContain("io.lettuce")
                .doesNotContain("WorkerScoreCore")
                .doesNotContain("Pacer");
    }

    @Test
    void directCallUsesOwnerMailboxWithoutOwningSchedulingOrRedis()
            throws IOException {
        String direct = readSources(DIRECT_CALL);
        assertThat(direct)
                .contains("DirectCallRegistry")
                .contains("currentEndpointManagerIds")
                .contains("WorkerCommandRuntime")
                .contains("offerWorkerCommands")
                .doesNotContain("WorkerScoreCore")
                .doesNotContain("getScoreStates")
                .doesNotContain("rewriteCurrentScores")
                .doesNotContain("releaseScoreHolds")
                .doesNotContain("TaskResultRuntime")
                .doesNotContain("TaskRuntime")
                .doesNotContain("TaskItem")
                .doesNotContain("Pacer")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework.data.redis")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("workerCommandsByAdapter")
                .doesNotContain("new Thread");
        assertThat(Files.readString(DIRECT_CALL.resolve(
                "DirectCallRegistry.java"
        )))
                .doesNotContain("DeferredResult")
                .doesNotContain("ResponseEntity")
                .doesNotContain("server.api.v1.directcall");
    }

    @Test
    void workerSchedulingIsTheOnlyServerScoreTransitionBoundary()
            throws IOException {
        String scheduling = readSources(WORKER_SCHEDULING);
        assertThat(scheduling)
                .contains("WorkerScoreCore")
                .contains("rewriteCurrentScores")
                .contains("getScoreStates")
                .contains("releaseScoreHolds")
                .doesNotContain("RedisWorkerScoreCore")
                .doesNotContain(".score.redis")
                .doesNotContain("PythonKernelHttpTransport")
                .doesNotContain("WorkerResourceCatalog")
                .doesNotContain("WorkerBindingService")
                .doesNotContain("DirectCall")
                .doesNotContain("Pacer")
                .doesNotContain("TaskRuntime")
                .doesNotContain("io.lettuce");
        assertThat(readSources(WORKER_SCHEDULING_HTTP))
                .contains("WorkerSchedulingService")
                .doesNotContain("RedisWorkerScoreCore")
                .doesNotContain("PythonKernelHttpTransport")
                .doesNotContain("io.lettuce");

        String serverSources = readSources(SERVER_SOURCE);
        assertThat(occurrences(serverSources, "rewriteCurrentScores("))
                .isEqualTo(1);
        assertThat(occurrences(serverSources, "releaseScoreHolds("))
                .isEqualTo(1);
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
                .doesNotContain("adapter.netty.internal")
                .doesNotContain(
                        "adapter.netty.internal.connection"
                                + ".WorkerPropertiesCache"
                )
                .doesNotContain("WorkerPropertiesObservation")
                .doesNotContain("io.netty")
                .doesNotContain("WebSocketSession")
                .doesNotContain("WorkerWebSocketHandler")
                .doesNotContain("TextMessage")
                .doesNotContain(
                        "workerdelivery.protocol.WorkerDeliveryProtocol"
                                + ".DeliveryReport"
                )
                .doesNotContain("\"23002\"")
                .doesNotContain("ArrayBlockingQueue");

        String assembly = readSources(WORKER_ASSEMBLY);
        assertThat(assembly)
                .contains("groupInitializer.initialize()")
                .contains("adapterManager.start()")
                .contains("adapterManager.close()")
                .contains("WorkerGroupRegistrationService")
                .contains("properties.groupConfigJson()")
                .doesNotContain("ScenarioWorkers")
                .doesNotContain("capabilityAssemblyJson")
                .doesNotContain("runtimeApiBaseUrl")
                .doesNotContain("sandboxRoot")
                .doesNotContain("ScenarioWorkerBundles")
                .doesNotContain("ScenarioWorkerBundleConfig")
                .doesNotContain("WorkerResourceCatalog")
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
    }

    @Test
    void deliveryOwnerAssemblyDoesNotCreateOtherKernelOwners()
            throws IOException {
        String deliveryAssembly = Files.readString(DELIVERY_ASSEMBLY);
        assertThat(deliveryAssembly)
                .contains("RedisWorkerCommandRuntime")
                .contains("RedisTaskResultRuntime")
                .contains("RedisWorkerServiceabilityRuntime")
                .doesNotContain("RedisWorkerChangeInbox")
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
                .doesNotContain("TaskResultRuntime")
                .doesNotContain("WorkerServiceabilityRuntime")
                .doesNotContain("RedisWorkerCommandRuntime")
                .doesNotContain("RedisTaskResultRuntime")
                .doesNotContain("RedisWorkerServiceabilityRuntime");

        assertThat(readSources(KERNEL_SOURCE))
                .doesNotContain("WorkerChangeInbox")
                .doesNotContain("route-change-inbox");
    }

    @Test
    void taskControlAndJavaPacersUseOnlyLocalKernelOwners()
            throws IOException {
        assertThat(readSources(KERNEL_BINDING))
                .contains("RedisTaskScoreBandCore")
                .contains("RedisTaskRuntime")
                .contains("DefaultTaskLifecycleCommands")
                .contains("DefaultTaskCallItemSubmission")
                .doesNotContain("RestClient")
                .doesNotContain("java.net.http")
                .doesNotContain("\"/health\"")
                .doesNotContain(".post()")
                .doesNotContain("HttpTaskRuntime")
                .doesNotContain("HttpTaskLifecycleCommands")
                .doesNotContain("HttpTaskCallItemSubmission")
                .doesNotContain("AssembledTaskRuntime")
                .doesNotContain("KernelHttpResultDecoder")
                .doesNotContain("HttpWorkerRuntime")
                .doesNotContain("HttpWorkerResourceCatalog")
                .doesNotContain("AssembledWorkerResourceCatalog")
                .doesNotContain("scheduling-observe")
                .doesNotContain("network-observe")
                .doesNotContain("KernelWorkerSchedulingReader")
                .doesNotContain("\"/worker-groups")
                .doesNotContain("\"/workers");

        assertThat(readSources(KERNEL_PACER))
                .contains("SmartLifecycle")
                .contains("KernelPacerRuntime")
                .doesNotContain("ResultConvergenceApplication")
                .doesNotContain("DispatchConvergenceApplication")
                .doesNotContain("ProcessBuilder")
                .doesNotContain("kernel_design.executable_spec.assembly")
                .doesNotContain("RestClient");
        assertThat(readSources(KERNEL_PACER_ASSEMBLY))
                .contains("KernelPacerRuntime")
                .doesNotContain("ScoreCore")
                .doesNotContain("TaskRuntime")
                .doesNotContain("RedisClient")
                .doesNotContain("RestClient");
        assertThat(readSources(PACER_SOURCE))
                .contains("ResultConvergenceApplication")
                .contains("DispatchConvergenceApplication")
                .doesNotContain("org.springframework")
                .doesNotContain("RedisClient");
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
