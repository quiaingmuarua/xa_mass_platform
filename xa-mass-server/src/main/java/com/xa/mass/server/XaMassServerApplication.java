package com.xa.mass.server;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.storage.jdbc.JdbcStorageMode;
import com.xa.mass.storage.jdbc.JdbcStorageRuntime;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.PrincipalDirectory;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.api.auth.CompositePrincipalDirectory;
import com.xa.mass.api.auth.DefaultOperatorPrincipalDirectory;
import com.xa.mass.server.auth.jdbc.JdbcSubmitterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;

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

    @Value("${mass.runtime.event-handler-timeout-ms:0}")
    private long eventHandlerTimeoutMillis;

    @Value("${mass.runtime.transport-max-pending-tasks:10000}")
    private int transportRuntimeMaxPendingTasks;

    @Value("${mass.runtime.event-max-pending-tasks:10000}")
    private int eventRuntimeMaxPendingTasks;

    @Value("${mass.storage.mode:memory}")
    private String storageMode;

    @Value("${mass.storage.jdbc.url:jdbc:h2:mem:xa_mass;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false}")
    private String storageJdbcUrl;

    @Value("${mass.storage.jdbc.username:sa}")
    private String storageJdbcUsername;

    @Value("${mass.storage.jdbc.password:}")
    private String storageJdbcPassword;

    public static void main(String[] args) {
        String profile = System.getProperty("spring.profiles.active");
        if (profile == null || profile.isBlank()) {
            profile = "dev";
            System.setProperty("spring.profiles.active", profile);
        }

        log.info("Starting XA Mass server");
        log.info("Active profile: {}", profile);

        ConfigurableApplicationContext context = SpringApplication.run(XaMassServerApplication.class, args);
        Environment environment = context.getEnvironment();
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
    @Profile("dev")
    public JdbcStorageRuntime jdbcStorageRuntime() {
        return JdbcStorageRuntime.create(
                JdbcStorageMode.parse(storageMode),
                storageJdbcUrl,
                storageJdbcUsername,
                storageJdbcPassword
        );
    }

    @Bean(destroyMethod = "stop")
    @Profile("dev")
    public MassSdkApplication fullStackRuntimeApplication(ObjectProvider<MassBootstrapDataProvider> bootstrapDataProvider,
                                                          JdbcStorageRuntime jdbcStorageRuntime) {
        MassSdk.Builder builder = MassSdk.builder();
        if (jdbcStorageRuntime.isEnabled()) {
            builder.submitterRegistry(new JdbcSubmitterRegistry(
                    jdbcStorageRuntime.dataSource(),
                    JdbcStorageMode.parse(storageMode)
            ));
        }
        return builder
                .projectCatalogBootstrap(new ProjectEventCatalogRegistry())
                .transport(transport -> transport
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
                        .inputQueue(new InMemoryMessageQueue<>("input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", TransportOutboundMessage.class)))
                .engine(engine -> {
                    engine.enabled(true).workerThreads(workerThreads);
                    if (jdbcStorageRuntime.isEnabled()) {
                        TaskScheduler scheduler = new SimpleTaskScheduler();
                        engine.scheduler(scheduler)
                                .taskManager(new TaskManager(
                                        scheduler,
                                        jdbcStorageRuntime.taskStorage(),
                                        new InMemoryTaskWorkRuntime()))
                                .workerManager(new WorkerManager(jdbcStorageRuntime.workerStorage()))
                                .ruleManager(RuleManagerFactory.getDefaultRuleManager(jdbcStorageRuntime.ruleStorage()));
                    }
                    MassBootstrapDataProvider provider = bootstrapDataProvider.getIfAvailable();
                    if (provider != null) {
                        engine.bootstrapDataProvider(provider);
                    }
                })
                .build();
    }

    @Bean
    @Profile("dev")
    @Order(0)
    public CommandLineRunner fullStackStarter(MassSdkApplication app, JdbcStorageRuntime jdbcStorageRuntime) {
        return args -> {
            log.info("Starting embedded transport runtime + engine");
            try {
                if (jdbcStorageRuntime.isEnabled()) {
                    jdbcStorageRuntime.recoverRuntimeResidue();
                }
                app.start();
                if (!app.isRunning()) {
                    throw new IllegalStateException("MassApplication failed to start properly");
                }

                Thread.sleep(1000L);

                try {
                    app.publishTaskEvents();
                    LogUtils.clearMdc();
                    log.info("Initial task events published");
                } catch (Exception e) {
                    LogUtils.clearMdc();
                    log.warn("Initial task event publish failed: {}", e.getMessage());
                }

                LogUtils.clearMdc();
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
                LogUtils.clearMdc();
                log.error("Startup interrupted", e);
                throw new RuntimeException("Startup process was interrupted", e);
            } catch (RuntimeException e) {
                LogUtils.clearMdc();
                log.error("Full-stack startup failed: {}", e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                LogUtils.clearMdc();
                log.error("Full-stack startup failed", e);
                throw new RuntimeException("Failed to start full-stack services", e);
            }
        };
    }

    /**
     * Exposes the live SDK registry that the dev runtime actually registers into.
     */
    @Bean
    @Primary
    @Profile("dev")
    public SdkMetadataCatalog devAppMetadataCatalog(MassSdkApplication app) {
        return app.metadataCatalog();
    }

    @Bean
    @Primary
    @Profile("dev")
    public PrincipalDirectory serverPrincipalDirectory(DefaultOperatorPrincipalDirectory operatorPrincipalDirectory,
                                                       MassSdkApplication app) {
        return new CompositePrincipalDirectory(List.of(operatorPrincipalDirectory, app));
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

}

