package com.xa.mass.mock;

import com.xa.mass.mock.bootstrap.MockRuntimeDataLoader;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import com.xa.mass.mock.command.tool.ToolCommandRoutes;
import com.xa.mass.command.core.CommandDefinition;
import com.xa.mass.command.model.CommandContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.event.EventResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test-only bootstrap support so dev-app mainline startup can stay external.
 */
@Configuration
@Profile("dev")
public class TestDevBootstrapConfiguration {

    private static final Gson GSON = new Gson();

    @Value("${mass.mock.data.workers:mock/mock_workers.json}")
    private String workersConfigPath;

    @Value("${mass.mock.data.tasks:mock/mock_tasks.json}")
    private String tasksConfigPath;

    @Value("${mass.mock.data.worker-contexts:mock/mock_worker_contexts.json}")
    private String workerContextsConfigPath;

    @Value("${mass.mock.data.rules:mock/mock_rules.json}")
    private String rulesConfigPath;

    @Value("${mass.mock.bootstrap.load-workers:true}")
    private boolean loadBootstrapWorkers;

    @Value("${mass.mock.bootstrap.load-worker-contexts:true}")
    private boolean loadBootstrapWorkerContexts;

    @Value("${mass.mock.bootstrap.load-tasks:true}")
    private boolean loadBootstrapTasks;

    @Value("${mass.mock.bootstrap.load-rules:true}")
    private boolean loadBootstrapRules;

    @Bean
    @ConditionalOnProperty(prefix = "mass.mock.bootstrap", name = "enabled", havingValue = "true")
    public MassBootstrapDataProvider mockRuntimeDataLoader() {
        return new MockRuntimeDataLoader(
                workersConfigPath,
                workerContextsConfigPath,
                tasksConfigPath,
                rulesConfigPath,
                loadBootstrapWorkers,
                loadBootstrapWorkerContexts,
                loadBootstrapTasks,
                loadBootstrapRules
        );
    }

    @Bean
    @Order(10)
    @ConditionalOnProperty(prefix = "mass.mock.bootstrap", name = "enabled", havingValue = "true")
    public CommandLineRunner testFixtureLoadRunner(MassSdkApplication app,
                                                   MassBootstrapDataProvider bootstrapDataProvider) {
        return args -> bootstrapDataProvider.loadInto(app);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(prefix = "mass.mock.bootstrap", name = "register-dev-catalog", havingValue = "true")
    public CommandLineRunner testCatalogBootstrapRunner(MassSdkApplication app) {
        return args -> {
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
            registerCatalogTaskDefinition(app, EventDefinition.builder()
                    .code("stock.quote.fetch")
                    .name("Stock Quote Fetch")
                    .description("Dispatch a stock quote fetch request to an external crawler worker.")
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
                    .eventCodes(List.of("crawler.fetch-page", "stock.quote.fetch"))
                    .build());
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @ConditionalOnProperty(prefix = "mass.mock.bootstrap", name = "register-dev-submitters", havingValue = "true")
    public CommandLineRunner testSubmitterBootstrapRunner(MassSdkApplication app) {
        return args -> {
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("crawler-submitter")
                    .credential("crawler-submitter-key")
                    .permissions(List.of(TaskSubmitterContext.TASK_CREATE_PERMISSION))
                    .projectScopes(List.of("crawlerApp"))
                    .eventScopes(List.of("crawler.fetch-page", "stock.quote.fetch"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("node-worker-api-001")
                    .credential("node-worker-key")
                    .permissions(List.of(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION))
                    .projectScopes(List.of("crawlerApp"))
                    .eventScopes(List.of("crawler.fetch-page"))
                    .attributes(Map.of("workerId", "node-worker-api-001"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("node-worker-realtime-001")
                    .credential("node-worker-realtime-key")
                    .permissions(List.of(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION))
                    .projectScopes(List.of("crawlerApp"))
                    .eventScopes(List.of("crawler.fetch-page"))
                    .attributes(Map.of("workerId", "node-worker-realtime-001"))
                    .build());
            app.registerSubmitter(SubmitterRegistration.builder()
                    .principalId("stock-ws-worker-001")
                    .credential("stock-ws-worker-key")
                    .permissions(List.of(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION))
                    .projectScopes(List.of("crawlerApp"))
                    .eventScopes(List.of("stock.quote.fetch"))
                    .attributes(Map.of("workerId", "stock-ws-worker-001"))
                    .build());
        };
    }

    private void registerCatalogTaskDefinition(MassSdkApplication app, EventDefinition definition) {
        app.registerEventDefinition(definition);
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
