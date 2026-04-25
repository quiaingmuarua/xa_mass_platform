package com.xa.mass.mock;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.command.core.CommandDefinition;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.mock.bootstrap.MockRuntimeDataLoader;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import com.xa.mass.mock.command.tool.ToolCommandRoutes;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.EventDefinition;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verified mainline Spring Boot entry for the full mock runtime.
 */
@SpringBootApplication(scanBasePackages = {"com.xa.mass.mock", "com.xa.mass.api"})
public class MockApplicationSpringBootApp {

    private static final Logger log = LoggerFactory.getLogger(MockApplicationSpringBootApp.class);
    private static final Gson GSON = new Gson();
    private static final String DEV_CRAWLER_PROJECT = "crawlerApp";
    private static final String DEV_CRAWLER_EVENT = "crawler.fetch-page";
    private static final String DEV_EXTERNAL_WORKER_ID = "node-worker-api-001";
    private static final String DEV_EXTERNAL_WORKER_KEY = "node-worker-key";
    private static final String DEV_TASK_SUBMITTER_ID = "crawler-submitter";
    private static final String DEV_TASK_SUBMITTER_KEY = "crawler-submitter-key";

    @Value("${mass.websocket.port:18088}")
    private int massWebSocketPort;

    @Value("${mass.websocket.max-connections:1000}")
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
        log.info("WebSocket adapter: ws://localhost:{}/ws", webSocketPort);
        log.info("==============================");
    }

    @Bean(destroyMethod = "stop")
    @Profile("dev")
    public MassSdkApplication fullStackRuntimeApplication() {
        return MassSdk.builder()
                .projectCatalogBootstrap(new ProjectEventCatalogRegistry())
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(massWebSocketPort)
                                .enabled(true)
                                .maxConnections(maxConnections))
                        .inputQueue(new InMemoryMessageQueue<>("input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", WorkerTransportMessage.class)))
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
            log.info("Starting internal WebSocket adapter + engine runtime");
            try {
                registerDevAppCatalog(app);
                registerDevAppSubmitters(app);
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
                log.info("Dev demo SDK submitters registered: task submitter={} external worker={} workerId={}",
                        DEV_TASK_SUBMITTER_KEY,
                        DEV_EXTERNAL_WORKER_KEY,
                        DEV_EXTERNAL_WORKER_ID);
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

    private void registerDevAppCatalog(MassSdkApplication app) {
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("demo.dispatch")
                .name("Demo Dispatch")
                .description("Dispatch a generic demo work item to an online demo worker.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(List.of("demoApp", "testApp", "otherApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("demo.dispatch.gb")
                .name("Demo Dispatch (GB)")
                .description("Dispatch a generic demo work item to the GB demo lane.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(List.of("demoApp", "otherApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Dispatch a crawler fetch request to an SDK-created pull worker.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(List.of("crawlerApp"))
                .build());
        registerMockTaskDefinitions(app);
        registerRuntimeToolDefinitions(app);

        app.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Default demo project. Event catalog is registered through the SDK runtime.")
                .eventCodes(List.of(
                        "demo.dispatch",
                        "demo.dispatch.gb",
                        "mock.state.get",
                        "mock.delay.response",
                        "mock.drop.outbound",
                        "mock.task.result.status",
                        "mock.disconnect",
                        "mock.reset"
                ))
                .build());

        app.registerProject(ProjectMetadata.builder()
                .code("testApp")
                .name("Test App")
                .description("Test project used by regression and E2E fixtures.")
                .eventCodes(List.of(
                        "demo.dispatch",
                        "mock.state.get",
                        "mock.delay.response",
                        "mock.drop.outbound",
                        "mock.task.result.status",
                        "mock.disconnect",
                        "mock.reset"
                ))
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

    private void registerDevAppSubmitters(MassSdkApplication app) {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId(DEV_TASK_SUBMITTER_ID)
                .credential(DEV_TASK_SUBMITTER_KEY)
                .permissions(List.of(TaskSubmitterContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of(DEV_CRAWLER_PROJECT))
                .eventScopes(List.of(DEV_CRAWLER_EVENT))
                .build());
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId(DEV_EXTERNAL_WORKER_ID)
                .credential(DEV_EXTERNAL_WORKER_KEY)
                .permissions(List.of(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(DEV_CRAWLER_PROJECT))
                .eventScopes(List.of(DEV_CRAWLER_EVENT))
                .attributes(Map.of("workerId", DEV_EXTERNAL_WORKER_ID))
                .build());
    }

    private void registerMockTaskDefinitions(MassSdkApplication app) {
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("mock.state.get")
                .name("Mock State Get")
                .description("Fetch the current mock fault-injection state from a targeted worker through the task path.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "testApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("mock.delay.response")
                .name("Mock Delay Response")
                .description("Update future mock task response delay on a targeted worker through the task path.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "testApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("mock.drop.outbound")
                .name("Mock Drop Outbound")
                .description("Update future mock outbound task-result drop mode on a targeted worker through the task path.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "testApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("mock.task.result.status")
                .name("Mock Task Result Status")
                .description("Override future mock task result status on a targeted worker through the task path.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "testApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("mock.disconnect")
                .name("Mock Disconnect")
                .description("Disconnect a targeted mock worker after its task result is returned.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "testApp"))
                .build());
        registerCatalogTaskDefinition(app, EventDefinition.builder()
                .code("mock.reset")
                .name("Mock Reset")
                .description("Reset mock fault-injection state on a targeted worker through the task path.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp", "testApp"))
                .build());
    }

    private void registerCatalogTaskDefinition(MassSdkApplication app,
                                               EventDefinition definition) {
        app.registerEventDefinition(definition);
    }

    private void registerRuntimeToolDefinitions(MassSdkApplication app) {
        for (CommandDefinition<JsonObject, Map<String, Object>> definition : ToolCommandRoutes.definitions()) {
            app.registerEventDefinition(toRuntimeToolEventDefinition(definition));
        }
    }

    private EventDefinition toRuntimeToolEventDefinition(CommandDefinition<JsonObject, Map<String, Object>> definition) {
        CommandDefinition.Descriptor descriptor = definition.getDescriptor();
        return EventDefinition.builder()
                .code(definition.getEvent())
                .name(humanizeEventName(descriptor.getEvent()))
                .description(descriptor.getSummary())
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of())
                .projectCodes(List.of())
                .handler((request, principal) -> {
                    MockCommandRuntime.initialize();
                    JsonObject payloadJson = toJsonObject(request.getPayload());
                    Map<String, Object> result = definition.getHandler().handle(
                            definition.getResolver().apply(payloadJson),
                            CommandContext.getInstance()
                    );
                    return EventResponse.success(result, request.getRequestId());
                })
                .build();
    }

    private JsonObject toJsonObject(Map<String, Object> payload) {
        return GSON.toJsonTree(payload == null ? Map.of() : payload).getAsJsonObject();
    }

    private String humanizeEventName(String eventCode) {
        String[] segments = eventCode.split("\\.");
        List<String> words = new ArrayList<>();
        for (String segment : segments) {
            if (!segment.isBlank()) {
                words.add(Character.toUpperCase(segment.charAt(0)) + segment.substring(1));
            }
        }
        return String.join(" ", words);
    }
}
