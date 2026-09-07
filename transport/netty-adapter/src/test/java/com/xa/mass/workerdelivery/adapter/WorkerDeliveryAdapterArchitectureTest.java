package com.xa.mass.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");
    private static final Path APPLICATION = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/application"
    );
    private static final Path NETTY = SOURCE.resolve(
            "com/xa/mass/workerdelivery/adapter/netty"
    );
    private static final Path PROCESS = NETTY.resolve("internal/process");
    private static final Path CONNECTION = NETTY.resolve(
            "internal/connection"
    );
    private static final Path NETWORK = NETTY.resolve("internal/network");
    private static final Path REMOTE = NETTY.resolve("internal/remote");

    @Test
    void moduleDependsOnlyOnItsProtocolAndNetworkLibraries()
            throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        assertThat(build)
                .contains(
                        "api project(':transport:worker-delivery-contract')"
                )
                .contains("io.netty:netty-transport")
                .contains("com.github.ben-manes.caffeine:caffeine")
                .doesNotContain("spring-boot")
                .doesNotContain("project(':server_jvm')")
                .doesNotContain("project(':kernel_jvm')")
                .doesNotContain("lettuce");

        assertThat(readSources(SOURCE))
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("io.lettuce")
                .doesNotContain("org.springframework");
    }

    @Test
    void frozenOwnerPackagesKeepTheirDependencyDirection()
            throws IOException {
        String application = readSources(APPLICATION);
        String adapter = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapter.java"
        ));
        String connection = readSources(CONNECTION);
        String network = readSources(NETWORK);
        String process = readSources(PROCESS);
        String remote = readSources(REMOTE);

        assertThat(application)
                .doesNotContain("adapter.netty")
                .doesNotContain("ServerBootstrap")
                .doesNotContain("ScheduledExecutorService");
        assertThat(adapter)
                .contains("WorkerConnectionInboundHandler")
                .contains("WorkerConnectionMechanism")
                .contains("AdapterProcessManager")
                .contains("NettyWorkerServer")
                .doesNotContain("WebSocketNettyWorkerServer")
                .doesNotContain("SocketNettyWorkerServer")
                .doesNotContain("FiniteQueue")
                .doesNotContain("ScheduledExecutorService");
        assertThat(connection)
                .doesNotContain("FiniteQueue")
                .doesNotContain("DeliveryReportProcess")
                .doesNotContain("java.net.http")
                .doesNotContain("io.netty.bootstrap")
                .doesNotContain("io.netty.handler.codec.http")
                .doesNotContain(".pipeline()")
                .doesNotContain(".writeAndFlush(");
        assertThat(network)
                .doesNotContain("internal.process")
                .doesNotContain("internal.remote")
                .doesNotContain("WorkerDeliveryCodec");
        assertThat(process)
                .doesNotContain("java.net.http")
                .doesNotContain("java.net.URI")
                .doesNotContain("io.netty")
                .doesNotContain("ServerBootstrap");
        assertThat(remote)
                .doesNotContain("internal.process")
                .doesNotContain("internal.connection")
                .doesNotContain("internal.network")
                .doesNotContain("io.netty");
    }

    @Test
    void callbackAdapterDoesNotBecomeASecondConnectionOwner()
            throws IOException {
        String mechanism = read(CONNECTION.resolve(
                "WorkerConnectionMechanism.java"
        ));
        String handler = read(CONNECTION.resolve(
                "WorkerConnectionInboundHandler.java"
        ));

        assertThat(mechanism)
                .doesNotContain("extends SimpleChannelInboundHandler")
                .doesNotContain("channelRead0(")
                .doesNotContain("context.fireChannelInactive()");
        assertThat(handler)
                .contains("private final WorkerConnectionMechanism mechanism")
                .doesNotContain("WorkerRouteRegistry")
                .doesNotContain("internal.remote")
                .doesNotContain("WorkerDeliveryCodec")
                .doesNotContain("DeliveryReportProcess")
                .doesNotContain("NettyWorkerServer")
                .doesNotContain("adapterId")
                .doesNotContain("sendTimeLimit");
    }

    @Test
    void remoteApiKeepsHttpMechanicsOutOfProcessesAndConnection()
            throws IOException {
        String aggregate = read(NETTY.resolve(
                "NettyWorkerDeliveryAdapter.java"
        )) + read(NETTY.resolve(
                "NettyWorkerDeliveryAdapterFactory.java"
        ));
        String process = readSources(PROCESS);
        String connection = readSources(CONNECTION);
        String network = readSources(NETWORK);
        String remote = read(REMOTE.resolve("WorkerDeliveryRemoteApi.java"));

        assertThat(aggregate + process + connection + network)
                .doesNotContain("java.net.http")
                .doesNotContain("statusCode")
                .doesNotContain("commands:consume")
                .doesNotContain("results:append")
                .doesNotContain("verify-binding");
        assertThat(remote)
                .contains("java.net.http")
                .contains("Thread.ofVirtual()")
                .contains(".executor(HTTP_EXECUTOR)")
                .contains("commands:consume")
                .contains("results:append")
                .doesNotContain("verify-binding")
                .doesNotContain("Executors.newCachedThreadPool");
    }

    @Test
    void commandAndReportOwnersKeepFixedIndependentConsumerThreads()
            throws IOException {
        String command = read(PROCESS.resolve("DeliveryCommandProcess.java"));
        String commandDispatcher = read(PROCESS.resolve(
                "BatchDispatcher.java"
        ));
        String reportDispatcher = read(PROCESS.resolve(
                "DeliveryReportDispatcher.java"
        ));
        String result = read(PROCESS.resolve("BatchProcessResult.java"));
        String manager = read(PROCESS.resolve("AdapterProcessManager.java"));

        assertThat(command)
                .doesNotContain("Queue<")
                .doesNotContain("runLoop")
                .doesNotContain("loopStopped")
                .doesNotContain("pendingBatch")
                .doesNotContain("Thread.sleep")
                .doesNotContain("new Thread(")
                .doesNotContain("while (")
                .doesNotContain("retryCount")
                .doesNotContain("retry_cnt");
        assertThat(commandDispatcher)
                .contains("LinkedBlockingQueue<")
                .contains(".drainTo(")
                .contains("while (")
                .contains("Thread.sleep")
                .contains("new Thread(")
                .contains("thread.setDaemon(true)")
                .contains("volatile boolean stopped")
                .doesNotContain("static <T> BatchDispatcher<T> queued")
                .doesNotContain("while (currentBatch")
                .doesNotContain("retryCount")
                .doesNotContain("retry_cnt")
                .doesNotContain("Executors.");
        assertThat(reportDispatcher)
                .contains("LinkedBlockingQueue<DeliveryReport> taskQueue")
                .contains("LinkedBlockingQueue<DeliveryReport> systemQueue")
                .contains("LinkedBlockingQueue<DeliveryReport> kernelQueue")
                .contains("while (isActive())")
                .contains("new Thread(")
                .contains("thread.setDaemon(true)")
                .doesNotContain("Executor")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("Timer");
        assertThat(result)
                .contains("WorkerDeliveryAdapterErrorCode errorCode")
                .contains("List<Integer> requeueIndexes")
                .doesNotContain("List<T>");
        assertThat(manager)
                .contains("BatchDispatcher<DeliveryCommandItem>")
                .contains("DeliveryReportDispatcher")
                .doesNotContain("List<BatchDispatcher")
                .doesNotContain("register(")
                .doesNotContain("enum QuiescePhase");
        assertThat(command + commandDispatcher + reportDispatcher
                + result + manager)
                .doesNotContain("FiniteQueue")
                .doesNotContain("AdapterBatchLane")
                .doesNotContain("AdapterBatchSource")
                .doesNotContain("AdapterBatchFailurePolicy")
                .doesNotContain("DeliveryReportIngress")
                .doesNotContain("DeliveryReportQueue")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("Executors.")
                .doesNotContain("Timer")
                .doesNotContain("SystemControlProcess")
                .doesNotContain("ControlOnlyProcess");
        assertThat(Files.exists(PROCESS.resolve(
                "AdapterBatchLane.java"
        ))).isFalse();
        assertThat(Files.exists(PROCESS.resolve(
                "AdapterBatchSource.java"
        ))).isFalse();
        assertThat(Files.exists(PROCESS.resolve(
                "DeliveryReportQueue.java"
        ))).isFalse();
        assertThat(Files.exists(PROCESS.resolve(
                "DeliveryReportProcess.java"
        ))).isFalse();
    }

    @Test
    void adapterEventsRemainAStaticImmutableExecutionMap()
            throws IOException {
        String dispatcher = read(PROCESS.resolve(
                "AdapterEventDispatcher.java"
        ));

        assertThat(dispatcher)
                .doesNotContain("ConcurrentHashMap")
                .doesNotContain("ServiceLoader")
                .doesNotContain("Class.forName")
                .doesNotContain("registerHandler")
                .doesNotContain("unregisterHandler");
    }

    @Test
    void workerPropertiesProjectionDoesNotBecomeRouteOrLifecycleTruth()
            throws IOException {
        String registry = read(CONNECTION.resolve(
                "WorkerRouteRegistry.java"
        ));
        String cache = read(CONNECTION.resolve(
                "WorkerPropertiesCache.java"
        ));
        String observation = read(CONNECTION.resolve(
                "WorkerPropertiesObservation.java"
        ));
        String mechanism = read(CONNECTION.resolve(
                "WorkerConnectionMechanism.java"
        ));
        String dispatcher = read(PROCESS.resolve(
                "AdapterEventDispatcher.java"
        ));
        String network = readSources(NETWORK);

        assertThat(registry)
                .doesNotContain("CachedProperties")
                .doesNotContain("propertiesByWorkerId");
        assertThat(cache)
                .doesNotContain("Channel")
                .doesNotContain("WorkerConnectionState")
                .doesNotContain("WorkerRouteRegistry")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("WorkerScore")
                .doesNotContain("heartbeat")
                .doesNotContain("probe(")
                .doesNotContain("freshnessNanos")
                .doesNotContain("adapterEpoch")
                .doesNotContain("observationRevision")
                .doesNotContain("monotonicNanos");
        assertThat(observation)
                .doesNotContain("Channel")
                .doesNotContain("WorkerConnectionState")
                .doesNotContain("WorkerRouteRegistry")
                .doesNotContain("Freshness")
                .doesNotContain("Version")
                .doesNotContain("observedAtMillis");
        assertThat(mechanism)
                .contains("routes.hasVerificationEvidence(workerId)")
                .contains("propertiesCache.invalidate(workerId)")

                .doesNotContain("worker-properties.changed")
                .doesNotContain("worker-properties-evidence");
        assertThat(dispatcher)
                .contains("propertiesByWorkerId")
                .contains("updatedAtMillis")
                .doesNotContain("connectionState\"")
                .doesNotContain("propertiesFreshness")
                .doesNotContain("propertiesVersion")
                .doesNotContain("observedAtMillis");
        assertThat(network)
                .doesNotContain("WorkerPropertiesCache")
                .doesNotContain("WorkerPropertiesObservation");
        assertThat(Files.exists(NETTY.resolve(
                "NettyWorkerObservationCacheConfig.java"
        ))).isFalse();
        assertThat(Files.exists(NETTY.resolve(
                "NettyWorkerPropertiesCacheConfig.java"
        ))).isFalse();
        assertThat(Files.exists(NETTY.resolve(
                "NettyWorkerDeliveryAdapterConfig.java"
        ))).isTrue();
    }

    @Test
    void cachePolicyDoesNotEscapeConnectionOwners()
            throws IOException {
        String registry = read(CONNECTION.resolve(
                "WorkerRouteRegistry.java"
        ));
        String properties = read(CONNECTION.resolve(
                "WorkerPropertiesCache.java"
        ));
        String outsideConnection = readSources(APPLICATION)
                + read(NETTY.resolve("NettyWorkerDeliveryAdapter.java"))
                + readSources(NETWORK)
                + readSources(PROCESS)
                + readSources(REMOTE);

        assertThat(registry)
                .doesNotContain("activeChannels")
                .doesNotContain("pendingVerifications")
                .doesNotContain("verifiedWorkerIds")
                .doesNotContain("ROUTE_VERIFIED")
                .doesNotContain("AttributeKey<Boolean>")
                .doesNotContain("verifyingChannel")
                .doesNotContain("InboundKind")
                .doesNotContain("InboundInspection")
                .doesNotContain("VerificationActivation")
                .doesNotContain("CacheLoader")
                .doesNotContain("refreshAfterWrite")
                .doesNotContain("removalListener")
                .doesNotContain(".scheduler(");
        assertThat(properties)
                .doesNotContain("expireAfter")
                .doesNotContain("CacheLoader")
                .doesNotContain("refreshAfterWrite")
                .doesNotContain("removalListener")
                .doesNotContain(".scheduler(");
        assertThat(outsideConnection)
                .doesNotContain("com.github.benmanes.caffeine");
    }

    @Test
    void repositoryConsumersCannotImportInternalConstructionTypes()
            throws IOException {
        Path repository = Path.of("../..").toAbsolutePath().normalize();
        Path moduleSource = SOURCE.toAbsolutePath().normalize();
        List<Path> violations = new ArrayList<>();
        for (Path sourceRoot : productionSourceRoots(repository)) {
            if (sourceRoot.toAbsolutePath().normalize()
                    .equals(moduleSource)) {
                continue;
            }
            try (var paths = Files.walk(sourceRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectInternalImport(
                                repository,
                                path,
                                violations
                        ));
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void removedOwnerPathsAndShutdownEscapeHatchesStayAbsent()
            throws IOException {
        String sources = readSources(NETTY);
        assertThat(sources)
                .doesNotContain("WorkerDeliveryGatewayClient")
                .doesNotContain("DeliveryCommandPump")
                .doesNotContain("DeliveryReportPump")
                .doesNotContain("BoundedDeliveryReportQueue")
                .doesNotContain("FiniteQueue")
                .doesNotContain("syncUninterruptibly()");
        assertThat(Files.exists(REMOTE.resolve(
                "DeliveryCommandHttpContract.java"
        ))).isFalse();
        assertThat(Files.exists(REMOTE.resolve(
                "DeliveryReportHttpContract.java"
        ))).isFalse();
        assertThat(Files.exists(PROCESS.resolve(
                "TargetedDeliveryCommand.java"
        ))).isFalse();
    }

    private static void collectInternalImport(
            Path repository,
            Path path,
            List<Path> violations
    ) {
        try {
            if (Files.readString(path).contains(
                    "com.xa.mass.workerdelivery.adapter.netty.internal"
            )) {
                violations.add(repository.relativize(
                        path.toAbsolutePath().normalize()
                ));
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static List<Path> productionSourceRoots(Path repository)
            throws IOException {
        ArrayList<Path> roots = new ArrayList<>();
        addSourceRoot(repository.resolve("kernel_jvm"), roots);
        addSourceRoot(repository.resolve("server_jvm"), roots);
        addSourceRoot(repository.resolve("scenario_workers_jvm"), roots);
        addChildSourceRoots(repository.resolve("transport"), roots);
        addChildSourceRoots(repository.resolve("integrations"), roots);
        return List.copyOf(roots);
    }

    private static void addChildSourceRoots(
            Path parent,
            List<Path> roots
    ) throws IOException {
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (var children = Files.list(parent)) {
            children.filter(Files::isDirectory)
                    .forEach(child -> addSourceRoot(child, roots));
        }
    }

    private static void addSourceRoot(Path module, List<Path> roots) {
        Path source = module.resolve("src/main/java");
        if (Files.isDirectory(source)) {
            roots.add(source);
        }
    }

    private static String readSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return "";
        }
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

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
