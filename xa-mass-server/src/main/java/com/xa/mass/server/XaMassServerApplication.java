package com.xa.mass.server;

import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.runtime.redis.RedisWorkerRegistry;
import com.xa.mass.runtime.redis.RedisTaskResultRuntime;
import com.xa.mass.runtime.redis.RedisTaskWorkRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.worker.runtime.routing.WorkerCandidateBucketPolicies;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.runtime.presence.RedisWorkerPresenceStore;
import com.xa.mass.api.review.InProcessTaskReviewReportQueue;
import com.xa.mass.api.review.InMemoryTaskReviewStore;
import com.xa.mass.api.review.JdbcTaskReviewStore;
import com.xa.mass.api.review.QueueBackedTaskReviewReadModelWriter;
import com.xa.mass.api.review.TaskReviewMaterializationPolicy;
import com.xa.mass.api.review.TaskReviewMaterializer;
import com.xa.mass.api.review.TaskReviewReadModel;
import com.xa.mass.api.review.TaskReviewReadModelWriter;
import com.xa.mass.api.review.TaskReviewReportQueue;
import com.xa.mass.api.review.TaskReviewStore;
import com.xa.mass.api.review.TaskReviewStoreMaterializer;
import com.xa.mass.api.review.TaskReviewStoreTaskReviewReadModel;
import com.xa.mass.server.catalog.CatalogMetadataProjection;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.storage.memory.InMemoryCatalogMetadataStore;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.auth.PrincipalDirectory;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.api.auth.CompositePrincipalDirectory;
import com.xa.mass.api.auth.DefaultOperatorPrincipalDirectory;
import com.xa.mass.server.auth.jdbc.JdbcCredentialPrincipalStore;
import com.xa.mass.trace.sink.ExecutionEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Verified mainline Spring Boot entry for the server runtime shell.
 */
@SpringBootApplication(scanBasePackages = {"com.xa.mass.server", "com.xa.mass.api", "com.xa.mass.workerpack"})
public class XaMassServerApplication {

    private static final Logger log = LoggerFactory.getLogger(XaMassServerApplication.class);
    @Value("${mass.websocket.port:18088}")
    private int massWebSocketPort;

    @Value("${mass.websocket.max-connections:1000}")
    private int maxConnections;

    @Value("${mass.socket.enabled:false}")
    private boolean massSocketEnabled;

    @Value("${mass.socket.port:18089}")
    private int massSocketPort;

    @Value("${mass.engine.worker-threads:8}")
    private int workerThreads;

    @Value("${mass.engine.assignment-retry-delay-millis:1000}")
    private long assignmentRetryDelayMillis;

    @Value("${mass.engine.runtime-ready-dispatch-idle-backoff-max-millis:30000}")
    private long runtimeReadyDispatchIdleBackoffMaxMillis;

    @Value("${mass.engine.lease-watchdog-interval-seconds:30}")
    private long leaseWatchdogIntervalSeconds;

    @Value("${mass.engine.task-message-lease-seconds:300}")
    private long taskMessageLeaseSeconds;

    @Value("${mass.runtime.event-handler-timeout-ms:0}")
    private long eventHandlerTimeoutMillis;

    @Value("${mass.runtime.transport-max-pending-tasks:10000}")
    private int transportRuntimeMaxPendingTasks;

    @Value("${mass.runtime.event-max-pending-tasks:10000}")
    private int eventRuntimeMaxPendingTasks;

    @Value("${mass.transport.node-id:${random.uuid}}")
    private String transportNodeId;

    @Value("${mass.runtime.mode:memory}")
    private String runtimeMode;

    @Value("${mass.runtime.redis.namespace:xa:mass:runtime:v1}")
    private String runtimeRedisNamespace;

    @Value("${mass.runtime.redis.max-queued-items:1000000}")
    private int runtimeRedisMaxQueuedItems;

    @Value("${mass.transport.delivery.store:memory}")
    private String transportDeliveryStore;

    @Value("${mass.transport.delivery.max-queued-items:100000}")
    private int transportDeliveryMaxQueuedItems;

    @Value("${mass.transport.delivery.max-items-per-route:10000}")
    private int transportDeliveryMaxItemsPerRoute;

    @Value("${mass.transport.delivery.redis.namespace:xa:mass:transport:delivery:v1}")
    private String transportDeliveryRedisNamespace;

    @Value("${mass.transport.presence.store:memory}")
    private String transportPresenceStore;

    @Value("${mass.transport.presence.lease-millis:30000}")
    private long transportPresenceLeaseMillis;

    @Value("${mass.transport.presence.redis.namespace:xa:mass:transport:presence:v1}")
    private String transportPresenceRedisNamespace;

    @Value("${mass.storage.mode:memory}")
    private String storageMode;

    @Value("${mass.storage.jdbc.url:jdbc:h2:mem:xa_mass;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false}")
    private String storageJdbcUrl;

    @Value("${mass.storage.jdbc.username:sa}")
    private String storageJdbcUsername;

    @Value("${mass.storage.jdbc.password:}")
    private String storageJdbcPassword;

    @Value("${mass.review.materialization.mode:OFF}")
    private String taskReviewMaterializationMode;

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.database:0}")
    private int redisDatabase;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    private Environment environment;

    @Autowired
    void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public static void main(String[] args) {
        log.info("Starting XA Mass server");

        SpringApplication application = new SpringApplication(XaMassServerApplication.class);
        ConfigurableApplicationContext context = application.run(args);
        Environment environment = context.getEnvironment();
        log.info("Effective profiles: {}", Arrays.toString(effectiveProfiles(environment)));
        String httpPort = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8088"));
        String webSocketPort = environment.getProperty("mass.websocket.port", "18088");

        log.info("XA Mass server started");
        log.info("==============================");
        log.info("HTTP control console: http://localhost:{}/", httpPort);
        log.info("HTTP API docs: http://localhost:{}/doc.html", httpPort);
        log.info("Embedded transport adapters: {}", describeConfiguredTransportAdapters(
                "ws://localhost:" + webSocketPort + "/ws",
                true,
                "tcp://localhost:" + environment.getProperty("mass.socket.port", "18089"),
                Boolean.parseBoolean(environment.getProperty("mass.socket.enabled", "false"))
        ));
        log.info("==============================");
    }

    @Bean(destroyMethod = "close")
    @Profile({"memory-local", "durable-local"})
    public JdbcStorageRuntime jdbcStorageRuntime() {
        JdbcStorageMode mode = JdbcStorageMode.parse(storageMode);
        if (isDurableLocalProfile() && !mode.isJdbc()) {
            throw new IllegalStateException("durable-local requires mass.storage.mode to be JDBC-enabled");
        }
        return JdbcStorageRuntime.create(
                mode,
                storageJdbcUrl,
                storageJdbcUsername,
                storageJdbcPassword
        );
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    public TaskShellStore taskShellStore(JdbcStorageRuntime jdbcStorageRuntime) {
        if (jdbcStorageRuntime.isEnabled()) {
            return jdbcStorageRuntime.taskShellStore();
        }
        return new InMemoryTaskShellStore();
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    public CatalogMetadataStore catalogMetadataStore(JdbcStorageRuntime jdbcStorageRuntime) {
        if (jdbcStorageRuntime.isEnabled()) {
            return jdbcStorageRuntime.catalogMetadataStore();
        }
        return new InMemoryCatalogMetadataStore();
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    public TaskReviewStore taskReviewStore(JdbcStorageRuntime jdbcStorageRuntime) {
        if (jdbcStorageRuntime.isEnabled()) {
            return new JdbcTaskReviewStore(jdbcStorageRuntime.dataSource());
        }
        return new InMemoryTaskReviewStore();
    }

    private static String[] effectiveProfiles(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 0 ? environment.getDefaultProfiles() : activeProfiles;
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    public TaskReviewReadModel taskReviewReadModel(TaskReviewStore taskReviewStore) {
        return new TaskReviewStoreTaskReviewReadModel(taskReviewStore);
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    public TaskReviewMaterializer taskReviewMaterializer(TaskReviewStore taskReviewStore) {
        return new TaskReviewStoreMaterializer(taskReviewStore);
    }

    @Bean(destroyMethod = "close")
    @Profile({"memory-local", "durable-local"})
    public TaskReviewReportQueue taskReviewReportQueue(TaskReviewMaterializer taskReviewMaterializer) {
        return new InProcessTaskReviewReportQueue(taskReviewMaterializer);
    }

    @Bean
    @Primary
    @Profile({"memory-local", "durable-local"})
    public TaskReviewMaterializationPolicy taskReviewMaterializationPolicy() {
        return TaskReviewMaterializationPolicy.fromDefaultMode(taskReviewMaterializationMode);
    }

    @Bean
    @Primary
    @Profile({"memory-local", "durable-local"})
    public TaskReviewReadModelWriter taskReviewReadModelWriter(TaskReviewReportQueue taskReviewReportQueue,
                                                              TaskReviewMaterializationPolicy policy) {
        return new QueueBackedTaskReviewReadModelWriter(taskReviewReportQueue, policy);
    }

    @Bean(destroyMethod = "shutdown")
    @Profile({"memory-local", "durable-local"})
    public TaskWorkRuntime taskWorkRuntime() {
        String normalizedMode = normalizeInfraMode(runtimeMode, "memory");
        if ("redis".equals(normalizedMode)) {
            return new RedisTaskWorkRuntime(redisUri(), runtimeRedisNamespace, runtimeRedisMaxQueuedItems);
        }
        requireDurableLocalInfraMode("mass.runtime.mode", "redis", normalizedMode);
        if ("memory".equals(normalizedMode)) {
            return new InMemoryTaskWorkRuntime();
        }
        throw new IllegalArgumentException("Unsupported mass.runtime.mode: " + runtimeMode);
    }

    @Bean(destroyMethod = "shutdown")
    @Profile({"memory-local", "durable-local"})
    public TaskResultRuntime taskResultRuntime() {
        String normalizedMode = normalizeInfraMode(runtimeMode, "memory");
        if ("redis".equals(normalizedMode)) {
            return new RedisTaskResultRuntime(redisUri(), runtimeRedisNamespace + ":result");
        }
        requireDurableLocalInfraMode("mass.runtime.mode", "redis", normalizedMode);
        if ("memory".equals(normalizedMode)) {
            return new InMemoryTaskResultRuntime();
        }
        throw new IllegalArgumentException("Unsupported mass.runtime.mode: " + runtimeMode);
    }

    @Bean(destroyMethod = "stop")
    @Profile({"memory-local", "durable-local"})
    public MassSdkApplication fullStackRuntimeApplication(ObjectProvider<MassBootstrapDataProvider> bootstrapDataProvider,
                                                          JdbcStorageRuntime jdbcStorageRuntime,
                                                          CatalogMetadataStore catalogMetadataStore,
                                                          TaskShellStore taskShellStore,
                                                          TaskWorkRuntime taskWorkRuntime,
                                                          TaskResultRuntime taskResultRuntime,
                                                          ObjectProvider<ExecutionEventSink> executionEventSinkProvider) {
        MassSdk.Builder builder = MassSdk.builder();
        if (jdbcStorageRuntime.isEnabled()) {
            builder.credentialPrincipalStore(new JdbcCredentialPrincipalStore(
                    jdbcStorageRuntime.dataSource(),
                    JdbcStorageMode.parse(storageMode)
            ));
        }
        MassSdkApplication app = builder
                .projectCatalogBootstrap(new ProjectEventCatalogRegistry())
                .transport(transport -> {
                    java.util.function.Supplier<TransportDeliveryStore> deliveryStoreFactory =
                            resolveTransportDeliveryStoreFactory();
                    java.util.function.Supplier<WorkerPresenceStore> presenceStoreFactory =
                            resolveTransportPresenceStoreFactory();
                    transport
                        .transportNodeId(transportNodeId)
                        .maxDeliveryQueuedItems(transportDeliveryMaxQueuedItems)
                        .maxDeliveryItemsPerRoute(transportDeliveryMaxItemsPerRoute)
                        .workerPresenceLeaseMillis(transportPresenceLeaseMillis)
                        .webSocketAdapter(webSocket -> webSocket
                                .server(massWebSocketPort)
                                .enabled(true)
                                .maxConnections(maxConnections))
                        .socketAdapter(socket -> socket
                                .enabled(massSocketEnabled)
                                .serverEnabled(massSocketEnabled)
                                .server(massSocketPort)
                                .maxConnections(maxConnections))
                        .transportRuntimeMaxPendingTasks(transportRuntimeMaxPendingTasks)
                                .eventRuntimeMaxPendingTasks(eventRuntimeMaxPendingTasks)
                                .eventHandlerTimeoutMillis(eventHandlerTimeoutMillis)
                                .queueMode();
                    if (deliveryStoreFactory != null) {
                        transport.deliveryStoreFactory(deliveryStoreFactory);
                    }
                    if (presenceStoreFactory != null) {
                        transport.presenceStoreFactory(presenceStoreFactory);
                    }
                })
                .engine(engine -> {
                    engine.enabled(true)
                            .workerThreads(workerThreads)
                            .assignmentRetryDelayMillis(assignmentRetryDelayMillis)
                            .runtimeReadyDispatchIdleBackoffMaxMillis(runtimeReadyDispatchIdleBackoffMaxMillis)
                            .leaseWatchdogIntervalSeconds(leaseWatchdogIntervalSeconds)
                            .taskMessageLeaseSeconds(taskMessageLeaseSeconds)
                            .taskShellStore(taskShellStore);
                    engine.taskWorkRuntime(taskWorkRuntime);
                    engine.taskResultRuntime(taskResultRuntime);
                    WorkerRegistry workerRegistry = workerRegistry();
                    if (workerRegistry != null) {
                        engine.workerRegistry(workerRegistry);
                    }
                    ExecutionEventSink executionEventSink = executionEventSinkProvider.getIfAvailable();
                    if (executionEventSink != null) {
                        engine.executionEventSink(executionEventSink);
                    }
                    if (jdbcStorageRuntime.isEnabled()) {
                        engine.ruleStorage(jdbcStorageRuntime.ruleStorage());
                    }
                    MassBootstrapDataProvider provider = bootstrapDataProvider.getIfAvailable();
                    if (provider != null) {
                        engine.bootstrapDataProvider(provider);
                    }
                })
                .build();
        CatalogMetadataProjection.restoreIntoApplication(catalogMetadataStore, app);
        return app;
    }

    private WorkerRegistry workerRegistry() {
        String normalizedMode = normalizeInfraMode(runtimeMode, "memory");
        if ("redis".equals(normalizedMode)) {
            return new RedisWorkerRegistry(
                    redisUri(),
                    runtimeRedisNamespace + ":worker",
                    WorkerCandidateBucketPolicies.defaultPolicy()
            );
        }
        requireDurableLocalInfraMode("mass.runtime.mode", "redis", normalizedMode);
        if ("memory".equals(normalizedMode)) {
            return null;
        }
        throw new IllegalArgumentException("Unsupported mass.runtime.mode: " + runtimeMode);
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    @Order(2)
    public CommandLineRunner fullStackStarter(MassSdkApplication app, JdbcStorageRuntime jdbcStorageRuntime) {
        return args -> {
            log.info("Starting embedded transport runtime + engine");
            try {
                app.start();
                if (!app.isRunning()) {
                    throw new IllegalStateException("MassApplication failed to start properly");
                }

                Thread.sleep(1000L);

                MDC.clear();
                log.info("Spring Boot HTTP API is ready");
                log.info("Embedded transport adapters configured: {}", describeConfiguredTransportAdapters(
                        "ws://localhost:" + massWebSocketPort + "/ws",
                        true,
                        "tcp://localhost:" + massSocketPort,
                        massSocketEnabled
                ));
                log.info("Full-stack runtime startup complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                MDC.clear();
                log.error("Startup interrupted", e);
                throw new RuntimeException("Startup process was interrupted", e);
            } catch (RuntimeException e) {
                MDC.clear();
                log.error("Full-stack startup failed: {}", e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                MDC.clear();
                log.error("Full-stack startup failed", e);
                throw new RuntimeException("Failed to start full-stack services", e);
            }
        };
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    @Order(4)
    public CommandLineRunner taskReviewReadModelFinalityListener(MassSdkApplication app,
                                                                 @Qualifier("taskReviewReadModelWriter")
                                                                 TaskReviewReadModelWriter writer) {
        return args -> app.addTaskWorkFinalListener(notification -> {
            try {
                writer.recordWorkFinal(enrichTaskWorkFinalNotification(app, notification));
            } catch (RuntimeException e) {
                TaskWorkFinalSnapshot snapshot = notification == null ? null : notification.finalSnapshot();
                log.warn("Task review read-model finality write failed: taskId={}, messageId={}, reason={}",
                        snapshot == null ? null : snapshot.taskId(),
                        snapshot == null ? null : snapshot.messageId(),
                        e.getMessage(),
                e);
            }
        });
    }

    @Bean
    @Profile({"memory-local", "durable-local"})
    @Order(4)
    public CommandLineRunner taskReviewReadModelAttemptClosedListener(MassSdkApplication app,
                                                                      @Qualifier("taskReviewReadModelWriter")
                                                                      TaskReviewReadModelWriter writer) {
        return args -> app.addTaskWorkAttemptClosedListener(notification -> {
            try {
                writer.recordAttemptClosed(notification);
            } catch (RuntimeException e) {
                var snapshot = notification == null ? null : notification.attemptSnapshot();
                log.warn("Task review read-model attempt write failed: taskId={}, messageId={}, attemptId={}, reason={}",
                        snapshot == null ? null : snapshot.taskId(),
                        snapshot == null ? null : snapshot.messageId(),
                        snapshot == null ? null : snapshot.attemptId(),
                        e.getMessage(),
                        e);
            }
        });
    }

    private static TaskWorkFinalNotification enrichTaskWorkFinalNotification(MassSdkApplication app,
                                                                             TaskWorkFinalNotification notification) {
        if (notification == null || notification.finalSnapshot() == null) {
            return notification;
        }
        TaskWorkFinalSnapshot eventSnapshot = notification.finalSnapshot();
        try {
            return app.getTaskWorkFinal(eventSnapshot.taskId(), eventSnapshot.messageId())
                    .map(snapshot -> new TaskWorkFinalNotification(
                            notification.taskId(),
                            notification.sharedConfig(),
                            snapshot
                    ))
                    .orElse(notification);
        } catch (RuntimeException e) {
            log.warn("Task review read-model finality enrichment failed: taskId={}, messageId={}, reason={}",
                    eventSnapshot.taskId(),
                    eventSnapshot.messageId(),
                    e.getMessage(),
                    e);
            return notification;
        }
    }

    /**
     * Exposes the live SDK registry that the dev runtime actually registers into.
     */
    @Bean
    @Primary
    @Profile({"memory-local", "durable-local"})
    public ControlPlaneCatalog serverControlPlaneCatalog(MassSdkApplication app) {
        return app.catalog();
    }

    @Bean
    @Primary
    @Profile({"memory-local", "durable-local"})
    public PrincipalDirectory serverPrincipalDirectory(DefaultOperatorPrincipalDirectory operatorPrincipalDirectory,
                                                       MassSdkApplication app) {
        return new CompositePrincipalDirectory(List.of(operatorPrincipalDirectory, app));
    }

    @Bean
    @Primary
    @Profile({"memory-local", "durable-local"})
    public RuntimeDiagnosticsOperations serverRuntimeDiagnosticsOperations(MassSdkApplication app) {
        return app.runtimeDiagnostics();
    }

    private static List<String> describeConfiguredTransportAdapters(String webSocketUri,
                                                                    boolean webSocketEnabled,
                                                                    String socketAddress,
                                                                    boolean socketEnabled) {
        List<String> adapters = new ArrayList<>();
        adapters.add("socket(enabled=" + socketEnabled + ", address=" + socketAddress + ")");
        adapters.add("websocket(enabled=" + webSocketEnabled + ", address=" + webSocketUri + ")");
        return adapters;
    }

    private String redisUri() {
        StringBuilder uri = new StringBuilder("redis://");
        if (redisPassword != null && !redisPassword.isBlank()) {
            uri.append(':').append(redisPassword).append('@');
        }
        uri.append(redisHost).append(':').append(redisPort).append('/').append(Math.max(0, redisDatabase));
        return uri.toString();
    }

    private java.util.function.Supplier<TransportDeliveryStore> resolveTransportDeliveryStoreFactory() {
        String normalizedMode = normalizeInfraMode(transportDeliveryStore, "memory");
        if ("redis".equals(normalizedMode)) {
            return () -> new RedisTransportDeliveryStore(
                    redisUri(),
                    transportDeliveryRedisNamespace,
                    transportDeliveryMaxQueuedItems,
                    transportDeliveryMaxItemsPerRoute
            );
        }
        requireDurableLocalInfraMode("mass.transport.delivery.store", "redis", normalizedMode);
        if ("memory".equals(normalizedMode)) {
            return null;
        }
        throw new IllegalArgumentException(
                "Unsupported mass.transport.delivery.store: " + transportDeliveryStore
        );
    }

    private java.util.function.Supplier<WorkerPresenceStore> resolveTransportPresenceStoreFactory() {
        String normalizedMode = normalizeInfraMode(transportPresenceStore, "memory");
        if ("redis".equals(normalizedMode)) {
            return () -> new RedisWorkerPresenceStore(
                    redisUri(),
                    transportPresenceRedisNamespace,
                    transportPresenceLeaseMillis,
                    transportNodeId
            );
        }
        requireDurableLocalInfraMode("mass.transport.presence.store", "redis", normalizedMode);
        if ("memory".equals(normalizedMode)) {
            return null;
        }
        throw new IllegalArgumentException(
                "Unsupported mass.transport.presence.store: " + transportPresenceStore
        );
    }

    private String normalizeInfraMode(String rawValue, String defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    private void requireDurableLocalInfraMode(String property, String requiredValue, String actualValue) {
        if (isDurableLocalProfile()) {
            throw new IllegalStateException("durable-local requires " + property + "=" + requiredValue
                    + " (was " + actualValue + ")");
        }
    }

    private boolean isDurableLocalProfile() {
        if (environment == null) {
            return false;
        }
        return Arrays.asList(effectiveProfiles(environment)).contains("durable-local");
    }

}
