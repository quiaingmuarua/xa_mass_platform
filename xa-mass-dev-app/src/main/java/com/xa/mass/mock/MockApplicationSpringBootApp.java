package com.xa.mass.mock;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.mock.bootstrap.MockRuntimeDataLoader;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Verified mainline Spring Boot entry for the full mock runtime.
 */
@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock", "com.xa.mass.api"})
public class MockApplicationSpringBootApp {

    private static final Logger log = LoggerFactory.getLogger(MockApplicationSpringBootApp.class);

    @Value("${mass.websocket.port:18088}")
    private int massWebSocketPort;

    @Value("${mass.gateway.max-connections:1000}")
    private int maxConnections;

    @Value("${mass.engine.worker-threads:8}")
    private int workerThreads;

    @Value("${mass.mock.data.workers:mock/mock_workers.json}")
    private String workersConfigPath;

    @Value("${mass.mock.data.tasks:mock/mock_tasks.json}")
    private String tasksConfigPath;

    @Value("${mass.mock.data.worker-contexts:mock/mock_worker_contexts.json}")
    private String workerContextsConfigPath;

    @Value("${mass.mock.data.rules:mock/mock_rules.json}")
    private String rulesConfigPath;

    public static void main(String[] args) {
        String profile = System.getProperty("spring.profiles.active");
        if (profile == null || profile.isBlank()) {
            profile = "dev";
            System.setProperty("spring.profiles.active", profile);
        }

        log.info("Starting mock full-stack application");
        log.info("Active profile: {}", profile);

        ConfigurableApplicationContext context = SpringApplication.run(MockApplicationSpringBootApp.class, args);
        Environment environment = context.getEnvironment();
        String httpPort = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8088"));
        String webSocketPort = environment.getProperty("mass.websocket.port", "18088");

        log.info("Mock full-stack application started");
        log.info("==============================");
        log.info("HTTP control console: http://localhost:{}/", httpPort);
        log.info("HTTP API docs: http://localhost:{}/doc.html", httpPort);
        log.info("Gateway WebSocket: ws://localhost:{}/ws", webSocketPort);
        log.info("==============================");
    }

    @Bean(destroyMethod = "stop")
    @Profile("dev")
    public MassSdkApplication fullStackRuntimeApplication() {
        return MassSdk.builder()
                .projectEventCatalog(new ProjectEventCatalogRegistry())
                .server(massWebSocketPort)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(maxConnections)
                        .inputQueue(new InMemoryMessageQueue<>("input", Envelope.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", Envelope.class)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(workerThreads)
                        .bootstrapDataProvider(mockRuntimeDataLoader()))
                .build();
    }

    @Bean
    @Profile("dev")
    public MockRuntimeDataLoader mockRuntimeDataLoader() {
        return new MockRuntimeDataLoader(
                workersConfigPath,
                workerContextsConfigPath,
                tasksConfigPath,
                rulesConfigPath
        );
    }

    @Bean
    @Profile("dev")
    public CommandLineRunner fullStackStarter(MassSdkApplication app, MockRuntimeDataLoader mockRuntimeDataLoader) {
        return args -> {
            log.info("Starting internal gateway + engine runtime");
            try {
                registerDevAppCatalog(app);
                app.start();
                if (!app.isRunning()) {
                    throw new IllegalStateException("MassApplication failed to start properly");
                }

                Thread.sleep(1000L);

                try {
                    mockRuntimeDataLoader.loadInto(app);
                    LogUtils.clearMdc();
                    log.info("Mock data loaded");
                } catch (Exception e) {
                    LogUtils.clearMdc();
                    log.warn("Mock data load failed but startup will continue: {}", e.getMessage());
                }

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
    public ProjectEventCatalog devAppProjectEventCatalog(MassSdkApplication app) {
        return app.projectEventCatalog();
    }

    private void registerDevAppCatalog(MassSdkApplication app) {
        app.registerEvent(EventMetadata.builder()
                .code("demo.dispatch")
                .name("Demo Dispatch")
                .description("Dispatch a generic demo work item to an online demo worker.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());

        app.registerEvent(EventMetadata.builder()
                .code("demo.dispatch.gb")
                .name("Demo Dispatch (GB)")
                .description("Dispatch a generic demo work item to the GB demo lane.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        app.registerEvent(EventMetadata.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Dispatch a crawler fetch request to an SDK-created pull worker.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());

        app.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Default demo project. Event catalog is registered through the SDK runtime.")
                .eventCodes(List.of("demo.dispatch", "demo.dispatch.gb"))
                .build());

        app.registerProject(ProjectMetadata.builder()
                .code("testApp")
                .name("Test App")
                .description("Test project used by regression and E2E fixtures.")
                .eventCodes(List.of("demo.dispatch"))
                .build());

        app.registerProject(ProjectMetadata.builder()
                .code("otherApp")
                .name("Other App")
                .description("Secondary demo project used by the dev validation shell.")
                .eventCodes(List.of("demo.dispatch", "demo.dispatch.gb"))
                .build());
        app.registerProject(ProjectMetadata.builder()
                .code("crawlerApp")
                .name("Crawler")
                .description("Crawler worker lab project for SDK-created pull worker scenarios.")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
    }
}
