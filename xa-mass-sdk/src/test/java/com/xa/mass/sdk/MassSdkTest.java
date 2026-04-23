package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.SubmitterMetadata;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.PlatformEventCodes;
import com.xa.mass.sdk.event.SdkEventDefinition;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MassSdkTest {

    @Test
    void builderCreatesConsumerFacingApplicationHandle() {
        MassSdkApplication app = MassSdk.builder()
                .transportServer(19090, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void customTransportServerFactoryOverridesDefaultWebSocketAdapter() {
        AtomicReference<TransportServerFactoryContext> capturedContext = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("transport-input", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("transport-output", Envelope.class);

        TransportServerFactory<TransportServerFactoryContext> factory = context -> {
            capturedContext.set(context);
            return new TransportServer() {
                @Override
                public void start(int port) {
                    started.set(true);
                }

                @Override
                public void stop() {
                    stopped.set(true);
                }

                @Override
                public boolean isRunning() {
                    return started.get() && !stopped.get();
                }
            };
        };

        MassSdkApplication app = MassSdk.builder()
                .transportServer(19092, "/custom-transport")
                .gateway(gateway -> gateway
                        .enabled(false)
                        .transportServerEnabled(true)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .transportServerFactory(factory))
                .engine(engine -> engine.enabled(false))
                .build();

        try {
            app.start();
            assertTrue(app.isRunning());
            assertNotNull(capturedContext.get());
            Assertions.assertEquals(19092, capturedContext.get().getPort());
            Assertions.assertEquals("/custom-transport", capturedContext.get().getEndpointPath());
        } finally {
            app.stop();
        }

        assertTrue(started.get());
        assertTrue(stopped.get());
    }

    @Test
    void developmentFactoryWrapsRuntimeApplication() {
        MassSdkApplication app = MassSdk.development(18080);

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void apiModeFailsFastBecauseTransportIsNotImplemented() {
        UnsupportedOperationException staticError = assertThrows(
                UnsupportedOperationException.class,
                () -> MassSdk.apiMode(18082, "http://input", "http://output", "test-key")
        );
        assertTrue(staticError.getMessage().contains("not implemented"));

        UnsupportedOperationException builderError = assertThrows(
                UnsupportedOperationException.class,
                () -> MassSdk.builder()
                        .gateway(gateway -> gateway.apiMode("http://input", "http://output", "test-key"))
        );
        assertTrue(builderError.getMessage().contains("not implemented"));
    }

    @Test
    void engineDependentHelpersFailFastWhenEngineIsUnavailable() {
        MassSdkApplication app = MassSdk.builder()
                .transportServer(19091, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false))
                .engine(engine -> engine.enabled(false))
                .build();

        Assertions.assertThrows(IllegalStateException.class,
                () -> app.createTask(MassTaskCreateRequest.builder().build()));
        assertEngineOperationsFailFast(app);
    }

    @Test
    void engineDependentHelpersFailFastBeforeStart() {
        MassSdkApplication app = MassSdk.development(18081);

        Assertions.assertThrows(IllegalStateException.class, () -> app.addWorker(null));
        assertEngineOperationsFailFast(app);
    }

    @Test
    void createTaskUsesSdkRequestAsPrimaryContract() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        Task createdTask = new Task();

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.createTask(any(TaskCreateRequestDto.class))).thenReturn(createdTask);

        MassSdkApplication app = new MassSdkApplication(delegate);
        MassTaskCreateRequest request = MassTaskCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .taskName("sdk-task")
                .sharedConfig(Map.of("textContent", "hello", "routingCode", "us"))
                .inputs(List.of(
                        Map.of("target", "target-a"),
                        Map.of("target", "target-b")
                ))
                .batchSize(2)
                .defaultMsgMaxRetryCount(5)
                .openEnded(true)
                .maxRuntimeSeconds(600)
                .build();

        Task result = app.createTask(request);

        assertSame(createdTask, result);
        var captor = org.mockito.ArgumentCaptor.forClass(TaskCreateRequestDto.class);
        verify(engine).createTask(captor.capture());
        TaskCreateRequestDto dto = captor.getValue();
        Assertions.assertEquals("agent", dto.getUserId());
        Assertions.assertEquals("demoApp", dto.getProject());
        Assertions.assertEquals("sdk-task", dto.getTaskName());
        Assertions.assertEquals(Map.of("textContent", "hello", "routingCode", "us"), dto.getSharedConfig());
        Assertions.assertEquals(List.of(
                Map.of("target", "target-a"),
                Map.of("target", "target-b")
        ), dto.getInputs());
        Assertions.assertEquals(2, dto.getBatchSize());
        Assertions.assertEquals(5, dto.getDefaultMsgMaxRetryCount());
        Assertions.assertTrue(dto.isOpenEnded());
        Assertions.assertEquals(600, dto.getMaxRuntimeSeconds());
    }

    @Test
    void registerWorkerUsesSdkContractAndStartsOffline() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);
        app.registerWorker(WorkerRegistration.builder()
                .workerId("crawler-worker-001")
                .workerGroupId("crawler")
                .supportedProjects(List.of("crawlerApp"))
                .supportedEventCodes(List.of("crawler.fetch-page"))
                .transportHint("polling")
                .attributes(Map.of("type", "crawler"))
                .build());

        var captor = org.mockito.ArgumentCaptor.forClass(Worker.class);
        verify(engine).addWorker(captor.capture());
        Worker worker = captor.getValue();
        Assertions.assertEquals("crawler-worker-001", worker.getWorkerId());
        Assertions.assertEquals("crawler", worker.getWorkerGroupId());
        Assertions.assertEquals(List.of("crawlerApp"), worker.getSupportedProjects());
        Assertions.assertEquals(List.of("crawler.fetch-page"), worker.getSupportedEventCodes());
        Assertions.assertEquals("polling", worker.getOnlineStrategy());
        Assertions.assertEquals(Map.of("type", "crawler"), worker.getAttributes());
        Assertions.assertEquals(WorkerStatus.OFFLINE, worker.getStatus());
    }

    @Test
    void registerWorkerContextUsesSdkContractAndStartsIdle() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);
        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId("ctx-crawler-worker-001")
                .workerId("crawler-worker-001")
                .routingTags(Set.of("web", "us"))
                .attributes(Map.of("region", "us"))
                .build());

        var captor = org.mockito.ArgumentCaptor.forClass(WorkerContext.class);
        verify(engine).addWorkerContext(captor.capture());
        WorkerContext workerContext = captor.getValue();
        Assertions.assertEquals("ctx-crawler-worker-001", workerContext.getWorkerContextId());
        Assertions.assertEquals("crawler-worker-001", workerContext.getWorkerId());
        Assertions.assertEquals(Set.of("web", "us"), workerContext.getRoutingTags());
        Assertions.assertEquals(Map.of("region", "us"), workerContext.getAttributes());
        Assertions.assertEquals(WorkerContextStatus.IDLE, workerContext.getStatus());
    }

    @Test
    void replaceDefaultRulesUsesOpenRuntimeCapability() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = new EngineConfig();
        RuleManager<Map<String, Object>> ruleManager = config.getRuleManager();
        RuleDefinition replacement = new RuleDefinition();
        replacement.setId("sdk_rule");
        replacement.setName("sdk_rule");
        replacement.setType(RuleType.QL_EXPRESS);
        replacement.setContent("true");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);

        MassSdkApplication app = new MassSdkApplication(delegate);
        app.replaceDefaultRules(List.of(replacement));

        Assertions.assertEquals(List.of(replacement), ruleManager.getDefaultRules());
    }

    @Test
    void engineConfigRejectsSchedulerMismatchAfterTaskManagerIsConfigured() {
        EngineConfig config = new EngineConfig();
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        config.setScheduler(scheduler);
        config.setTaskManager(new com.xa.mass.engine.TaskManager(scheduler));

        assertThrows(IllegalStateException.class,
                () -> config.setScheduler(new SimpleTaskScheduler()));
    }

    @Test
    void engineConfigRejectsTaskManagerSchedulerMismatchAfterSchedulerIsConfigured() {
        EngineConfig config = new EngineConfig();
        config.setScheduler(new SimpleTaskScheduler());

        assertThrows(IllegalArgumentException.class,
                () -> config.setTaskManager(new com.xa.mass.engine.TaskManager(new SimpleTaskScheduler())));
    }

    @Test
    void createTaskSupportsModeAwareSdkRequest() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        Task createdTask = new Task();

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.createTask(any(TaskCreateRequestDto.class))).thenReturn(createdTask);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);
        MassTaskRequest request = MassTaskRequest.streaming("demoApp", "crawler-stream")
                .userId("agent")
                .eventCode("crawler.fetch-page")
                .payloadType(PayloadType.JSON)
                .sharedConfig(Map.of("routingCode", "us"))
                .jsonInputs(List.of(
                        Map.of("url", "https://example.test/page-1"),
                        Map.of("url", "https://example.test/page-2")
                ))
                .batchSize(1)
                .defaultMsgMaxRetryCount(2)
                .maxRuntimeSeconds(60)
                .build();

        Task result = app.createTask(request);

        assertSame(createdTask, result);
        var captor = org.mockito.ArgumentCaptor.forClass(TaskCreateRequestDto.class);
        verify(engine).createTask(captor.capture());
        TaskCreateRequestDto dto = captor.getValue();
        Assertions.assertEquals("agent", dto.getUserId());
        Assertions.assertEquals("demoApp", dto.getProject());
        Assertions.assertEquals("crawler-stream", dto.getTaskName());
        Assertions.assertTrue(dto.isOpenEnded());
        Assertions.assertEquals(Map.of(
                "routingCode", "us",
                "_sdk", Map.of(
                        "eventCode", "crawler.fetch-page",
                        "payloadType", "JSON",
                        "taskMode", "STREAMING"
                )
        ), dto.getSharedConfig());
        Assertions.assertEquals(List.of(
                Map.of("type", "json", "data", Map.of("url", "https://example.test/page-1")),
                Map.of("type", "json", "data", Map.of("url", "https://example.test/page-2"))
        ), dto.getInputs());
    }

    @Test
    void resourceOperationsAllowSdkLevelProjectAndEventRegistrationWithoutRuntimeStart() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        EventMetadata eventMetadata = EventMetadata.builder()
                .code("bot.command")
                .name("Bot Command")
                .description("Handle a telegram-style bot command")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .defaultRoutingCode("bot")
                .build();
        ProjectMetadata projectMetadata = ProjectMetadata.builder()
                .code("botApp")
                .name("Bot App")
                .description("Bot-oriented sdk catalog entry")
                .eventCodes(List.of("bot.command"))
                .build();

        app.registerEvent(eventMetadata);
        app.registerProject(projectMetadata);

        Assertions.assertTrue(app instanceof ResourceOperations);
        Assertions.assertEquals(eventMetadata, app.getEvent("bot.command"));
        Assertions.assertEquals(projectMetadata, app.getProject("botApp"));
        Assertions.assertTrue(app.hasEvent("bot.command"));
        Assertions.assertTrue(app.hasProject("botApp"));
        Assertions.assertTrue(app.projectSupportsEvent("botApp", "bot.command"));
        Assertions.assertFalse(app.projectSupportsEvent("botApp", "crawler.fetch-page"));
        Assertions.assertTrue(app.listProjects().stream().anyMatch(project -> "demoApp".equals(project.getCode())));
        Assertions.assertTrue(app.listEvents().stream().anyMatch(event -> PlatformEventCodes.META_EVENTS_LIST.equals(event.getCode())));
        Assertions.assertEquals(List.of(eventMetadata), app.getEventsForProject("botApp"));
    }

    @Test
    void sdkEventDefinitionBecomesSingleSourceForMetadataScopeAndHandler() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerProject(ProjectMetadata.builder()
                .code("botApp")
                .name("Bot App")
                .description("bot project")
                .eventCodes(List.of("bot.command"))
                .build());
        app.registerEventDefinition(SdkEventDefinition.builder()
                .metadata(EventMetadata.builder()
                        .code("bot.command")
                        .name("Bot Command")
                        .description("handle a bot command directly")
                        .payloadTypes(List.of(PayloadType.JSON))
                        .taskModes(List.of(TaskMode.SINGLE_RUN))
                        .build())
                .projectCodes(List.of("botApp"))
                .handler((request, principal) -> EventResponse.success(
                        Map.of(
                                "event", request.getEvent().value(),
                                "project", request.getProject(),
                                "userId", principal == null ? null : principal.getUserId()
                        ),
                        request.getRequestId()
                ))
                .build());

        app.grantClientEventPermissions("client-a", List.of("bot.command"));
        app.grantUserEventPermissions("user-a", List.of("bot.command"));

        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("bot.command")
                        .project("botApp")
                        .requestId("req-bot-command")
                        .payload(Map.of("text", "/start"))
                        .build(),
                EventPrincipal.of("client-a", "user-a")
        );

        assertTrue(response.isSuccess());
        assertEquals("req-bot-command", response.getRequestId());
        assertEquals("bot.command", ((Map<?, ?>) response.getData()).get("event"));
        assertTrue(app.listEvents().stream().anyMatch(event -> "bot.command".equals(event.getCode())));
        assertEquals(List.of("bot.command"),
                app.getEventsForProject("botApp").stream().map(EventMetadata::getCode).toList());

        ProjectEventCatalog catalog = app.projectEventCatalog();
        assertTrue(catalog.listEvents().stream().anyMatch(event -> "bot.command".equals(event.getCode())));
        assertEquals(List.of("bot.command"),
                catalog.getEventsForProject("botApp").stream().map(EventMetadata::getCode).toList());
    }

    @Test
    void submitterOperationsAllowCredentialBasedSubmitterRegistration() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        SubmitterRegistration submitterRegistration = SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("test-api-key")
                .userId("bot-user")
                .projectScope("telegramApp")
                .attributes(Map.of("channel", "telegram"))
                .build();

        app.registerSubmitter(submitterRegistration);

        Assertions.assertTrue(app instanceof AuthProvider);
        Assertions.assertTrue(app.hasSubmitter("telegram-bot"));
        SubmitterMetadata submitterMetadata = SubmitterMetadata.from(submitterRegistration);
        Assertions.assertEquals(List.of(submitterMetadata), app.listSubmitters());
        Assertions.assertEquals(submitterMetadata, app.getSubmitter("telegram-bot"));
        TaskSubmitterContext submitterContext = app.authenticateSubmitter("test-api-key");
        Assertions.assertNotNull(submitterContext);
        Assertions.assertEquals("telegram-bot", submitterContext.getPrincipalId());
        Assertions.assertEquals("bot-user", submitterContext.getUserId());
        Assertions.assertEquals("telegramApp", submitterContext.getProjectScope());
        Assertions.assertEquals(List.of("task:create"), submitterContext.getPermissions());
        Assertions.assertEquals(List.of("telegramApp"), submitterContext.getProjectScopes());
        Assertions.assertEquals(List.of(), submitterContext.getEventScopes());
        Assertions.assertEquals(Map.of("channel", "telegram"), submitterContext.getAttributes());
        Assertions.assertEquals(submitterContext.getPrincipalId(), app.authenticate("test-api-key").getPrincipalId());
    }

    @Test
    void submitterOperationsAllowMultipleApiKeysForSameUserWithDifferentScopes() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-read-key")
                .credential("crawler-read-secret")
                .userId("crawler-user")
                .permissions(List.of("metadata:view"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-create-key")
                .credential("crawler-create-secret")
                .userId("crawler-user")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());

        TaskSubmitterContext readKey = app.authenticateSubmitter("crawler-read-secret");
        TaskSubmitterContext createKey = app.authenticateSubmitter("crawler-create-secret");

        Assertions.assertNotNull(readKey);
        Assertions.assertNotNull(createKey);
        Assertions.assertEquals("crawler-user", readKey.getUserId());
        Assertions.assertEquals("crawler-user", createKey.getUserId());
        Assertions.assertFalse(readKey.hasPermission("task:create"));
        Assertions.assertTrue(createKey.hasPermission("task:create"));
        Assertions.assertEquals("crawler-read-key", readKey.getPrincipalId());
        Assertions.assertEquals("crawler-create-key", createKey.getPrincipalId());
    }

    @Test
    void submitterQueryApisDoNotExposeCredential() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("test-api-key")
                .projectScope("telegramApp")
                .build());

        SubmitterMetadata metadata = app.getSubmitter("telegram-bot");

        Assertions.assertNotNull(metadata);
        Assertions.assertEquals("telegram-bot", metadata.getPrincipalId());
        Assertions.assertFalse(metadata.toString().contains("test-api-key"));
    }

    @Test
    void dispatchEventRequiresClientAndUserIntersection() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.grantClientEventPermissions("client-a", List.of(PlatformEventCodes.META_EVENTS_LIST));
        app.grantUserEventPermissions("user-a", List.of(PlatformEventCodes.META_EVENTS_LIST));

        EventResponse allowed = app.dispatchEvent(
                EventRequest.builder()
                        .event(PlatformEventCodes.META_EVENTS_LIST)
                        .requestId("req-1")
                        .build(),
                EventPrincipal.of("client-a", "user-a")
        );

        assertTrue(allowed.isSuccess());
        Assertions.assertEquals("req-1", allowed.getRequestId());
        Assertions.assertTrue(allowed.getData() instanceof List<?>);

        EventResponse denied = app.dispatchEvent(
                EventRequest.builder()
                        .event(PlatformEventCodes.META_EVENTS_LIST)
                        .requestId("req-2")
                        .build(),
                EventPrincipal.of("client-a", "missing-user")
        );

        assertFalse(denied.isSuccess());
        Assertions.assertEquals("FORBIDDEN", denied.getCode());
    }

    @Test
    void dispatchEventRejectsCatalogEventWhenProjectDoesNotSupportIt() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        registerExampleTaskCatalog(app);
        app.grantClientEventPermissions("client-a", List.of("crawler.fetch-page"));
        app.grantUserEventPermissions("user-a", List.of("crawler.fetch-page"));

        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("crawler.fetch-page")
                        .project("telegramApp")
                        .payload(Map.of("url", "https://example.test"))
                        .requestId("req-catalog-deny")
                        .build(),
                EventPrincipal.of("client-a", "user-a")
        );

        assertFalse(response.isSuccess());
        Assertions.assertEquals("FORBIDDEN", response.getCode());
    }

    @Test
    void dispatchEventCanCreateCatalogTaskThroughEventEntry() {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MassSdkApplication app = MassSdk.builder()
                .transportServer(0, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false).inputQueue(inputQueue).outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            registerExampleTaskCatalog(app);
            app.start();
            app.grantClientEventPermissions("client-a", List.of("crawler.fetch-page"));
            app.grantUserEventPermissions("user-a", List.of("crawler.fetch-page"));

            EventResponse response = app.dispatchEvent(
                    EventRequest.builder()
                            .event("crawler.fetch-page")
                            .project("crawlerApp")
                            .headers(Map.of("taskName", "crawler-fetch-via-event"))
                            .payload(Map.of("url", "https://example.test/page-1"))
                            .requestId("req-catalog-create")
                            .build(),
                    EventPrincipal.of("client-a", "user-a")
            );

            assertTrue(response.isSuccess());
            Assertions.assertEquals("req-catalog-create", response.getRequestId());
            Assertions.assertInstanceOf(Task.class, response.getData());
            Task task = (Task) response.getData();
            Assertions.assertEquals("crawlerApp", task.getProject());
            Assertions.assertEquals("crawler-fetch-via-event", task.getTaskName());
            Assertions.assertEquals(1, app.getTaskMessages(task.getTid()).size());
            Assertions.assertEquals("json", app.getTaskMessages(task.getTid()).get(0).getInput().get("type"));
        } finally {
            app.stop();
        }
    }

    @Test
    void disabledSubmitterCannotAuthenticate() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("disabled-bot")
                .credential("disabled-key")
                .projectScope("telegramApp")
                .enabled(false)
                .build());

        Assertions.assertNull(app.authenticateSubmitter("disabled-key"));
    }

    @Test
    void duplicateCredentialAcrossDifferentSubmittersIsRejected() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("shared-key")
                .build());

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> app.registerSubmitter(SubmitterRegistration.builder()
                        .principalId("sms-bot")
                        .credential("shared-key")
                        .build()));
    }

    @Test
    void createTaskRejectsUnsupportedSdkProjectAndEventContract() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> app.createTask(MassTaskRequest.singleRun("missingApp", "unknown-task")
                        .jsonInputs(List.of(Map.of("target", "value")))
                        .build()));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> app.createTask(MassTaskRequest.singleRun("telegramApp", "crawler-task")
                        .eventCode("crawler.fetch-page")
                        .jsonInputs(List.of(Map.of("url", "https://example.test")))
                        .build()));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> app.createTask(MassTaskRequest.singleRun("rcsApp", "sms-task")
                        .eventCode("sms.acquire-number")
                        .payloadType(PayloadType.TEXT)
                        .textInputs(List.of("hello"))
                        .build()));
    }

    @Test
    void registerProjectMakesCustomProjectExecutableForEngineTaskCreation() {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MassSdkApplication app = MassSdk.builder()
                .transportServer(0, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false).inputQueue(inputQueue).outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerProject(ProjectMetadata.builder()
                    .code("botAppExecutableTest")
                    .name("Bot App Executable Test")
                    .description("custom runtime project")
                    .eventCodes(List.of("chatbot.reply"))
                    .build());

            Task task = app.createTask(MassTaskCreateRequest.builder()
                    .userId("bot-agent")
                    .project("botAppExecutableTest")
                    .taskName("custom-project-task")
                    .inputs(List.of(Map.of("target", "chat-1")))
                    .batchSize(1)
                    .build());

            assertNotNull(task);
            Assertions.assertEquals("botAppExecutableTest", task.getProject());
        } finally {
            app.stop();
        }
    }

    @Test
    void createTaskSupportsCustomRegisteredProjectAndEventCatalog() {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MassSdkApplication app = MassSdk.builder()
                .transportServer(0, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false).inputQueue(inputQueue).outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerEvent(EventMetadata.builder()
                    .code("bot.command")
                    .name("Bot Command")
                    .description("custom bot command")
                    .payloadTypes(List.of(PayloadType.TEXT))
                    .taskModes(List.of(TaskMode.SINGLE_RUN))
                    .build());
            app.registerProject(ProjectMetadata.builder()
                    .code("botAppCatalogTest")
                    .name("Bot App Catalog Test")
                    .description("custom runtime project")
                    .eventCodes(List.of("bot.command"))
                    .build());

            Task task = app.createTask(MassTaskRequest.singleRun("botAppCatalogTest", "bot-command-task")
                    .userId("bot-agent")
                    .eventCode("bot.command")
                    .textInputs(List.of("/start"))
                    .build());

            assertNotNull(task);
            Assertions.assertEquals("botAppCatalogTest", task.getProject());
        } finally {
            app.stop();
        }
    }

    private static void registerExampleTaskCatalog(MassSdkApplication app) {
        app.registerEvent(EventMetadata.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        app.registerEvent(EventMetadata.builder()
                .code("sms.acquire-number")
                .name("SMS Acquire Number")
                .description("Example SMS acquire number task event.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .build());
        app.registerEvent(EventMetadata.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());

        app.registerProject(ProjectMetadata.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("Example crawler project.")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        app.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Example demo project.")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        app.registerProject(ProjectMetadata.builder()
                .code("telegramApp")
                .name("Telegram App")
                .description("Example telegram project.")
                .eventCodes(List.of("chatbot.reply"))
                .build());
        app.registerProject(ProjectMetadata.builder()
                .code("rcsApp")
                .name("RCS App")
                .description("Example RCS project.")
                .eventCodes(List.of("sms.acquire-number", "chatbot.reply"))
                .build());
    }

    @Test
    void sdkRegistrationNormalizesWorkerAndContextContracts() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);
        Map<String, String> workerAttributes = new LinkedHashMap<>();
        workerAttributes.put(" type ", "crawler");
        workerAttributes.put(" ", "ignored");
        workerAttributes.put("null-value", null);
        app.registerWorker(WorkerRegistration.builder()
                .workerId(" crawler-worker-001 ")
                .workerGroupId(" crawler ")
                .supportedProjects(Arrays.asList(" crawlerApp ", "crawlerApp", " "))
                .supportedEventCodes(Arrays.asList(" crawler.fetch-page ", "crawler.fetch-page", " "))
                .transportHint(" POLLING ")
                .attributes(workerAttributes)
                .build());

        Map<String, String> contextAttributes = new LinkedHashMap<>();
        contextAttributes.put(" region ", "us");
        contextAttributes.put("", "ignored");
        LinkedHashSet<String> routingTags = new LinkedHashSet<>(Arrays.asList(" ROUTE-US ", "route-us", " "));
        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId(" ctx-crawler-worker-001 ")
                .workerId(" crawler-worker-001 ")
                .routingTags(routingTags)
                .attributes(contextAttributes)
                .build());

        var workerCaptor = org.mockito.ArgumentCaptor.forClass(Worker.class);
        verify(engine).addWorker(workerCaptor.capture());
        Worker worker = workerCaptor.getValue();
        Assertions.assertEquals("crawler-worker-001", worker.getWorkerId());
        Assertions.assertEquals("crawler", worker.getWorkerGroupId());
        Assertions.assertEquals(List.of("crawlerApp"), worker.getSupportedProjects());
        Assertions.assertEquals(List.of("crawler.fetch-page"), worker.getSupportedEventCodes());
        Assertions.assertEquals("polling", worker.getOnlineStrategy());
        Assertions.assertEquals(Map.of("type", "crawler"), worker.getAttributes());

        var contextCaptor = org.mockito.ArgumentCaptor.forClass(WorkerContext.class);
        verify(engine).addWorkerContext(contextCaptor.capture());
        WorkerContext workerContext = contextCaptor.getValue();
        Assertions.assertEquals("ctx-crawler-worker-001", workerContext.getWorkerContextId());
        Assertions.assertEquals("crawler-worker-001", workerContext.getWorkerId());
        Assertions.assertEquals(Set.of("route-us"), workerContext.getRoutingTags());
        Assertions.assertEquals(Map.of("region", "us"), workerContext.getAttributes());
    }

    @Test
    void pullWorkerSessionCompletesTaskWithoutWebsocketPush() throws Exception {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MassSdkApplication app = MassSdk.builder()
                .transportServer(0, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false).inputQueue(inputQueue).outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(2))
                .build();

        try {
            app.start();

            RuleDefinition rule = new RuleDefinition();
            rule.setId("polling-online-project");
            rule.setName("polling-online-project");
            rule.setType(RuleType.QL_EXPRESS);
            rule.setContent("isWorkerAvailable && supportsProject");
            app.replaceDefaultRules(List.of(rule));

            app.registerWorker(WorkerRegistration.builder()
                    .workerId("polling-worker-1")
                    .supportedProjects(List.of("demoApp"))
                    .transportHint("polling")
                    .build());

            PullWorkerSession session = app.pullWorker("polling-worker-1");
            session.connect();

            Task task = app.createTask(MassTaskCreateRequest.builder()
                    .userId("crawler-agent")
                    .project("demoApp")
                    .taskName("fetch-page")
                    .sharedConfig(Map.of("mode", "pull"))
                    .inputs(List.of(Map.of("url", "https://example.test/page-1")))
                    .batchSize(1)
                    .build());

            assertTrue(app.approveTask(task.getTid()));

            TaskDispatchItem dispatchItem = waitFor(
                    Duration.ofSeconds(5),
                    () -> {
                        List<TaskDispatchItem> polled = session.poll(1);
                        return polled.isEmpty() ? null : polled.get(0);
                    }
            );
            assertNotNull(dispatchItem);
            Assertions.assertEquals(task.getTid(), dispatchItem.getTaskId());
            Assertions.assertEquals("https://example.test/page-1", dispatchItem.getInput().get("url"));
            Assertions.assertEquals("pull", dispatchItem.getSharedConfig().get("mode"));

            assertTrue(session.submitResult(
                    dispatchItem,
                    true,
                    "fetched",
                    Map.of("httpStatus", 200, "bodyLength", 42)
            ));

            Task terminalTask = waitFor(
                    Duration.ofSeconds(5),
                    () -> {
                        Task current = app.getTask(task.getTid());
                        return current != null && current.getStatus() == TaskStatus.TERMINAL ? current : null;
                    }
            );

            assertNotNull(terminalTask);
            Assertions.assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, terminalTask.getTerminalReason());

            TaskMsg finalMessage = app.getTaskMessages(task.getTid()).get(0);
            Assertions.assertEquals("SUCCESS", finalMessage.getStatus().name());
            Assertions.assertEquals(200, finalMessage.getOutput().get("httpStatus"));
        } finally {
            app.stop();
        }
    }

    @Test
    void massTaskRequestConvenienceBuildersExposeExpectedModeAndInputShape() {
        MassTaskRequest textRequest = MassTaskRequest.singleRun("demoApp", "chatbot")
                .userId("agent")
                .payloadType(PayloadType.TEXT)
                .textInputs(List.of("hello", "world"))
                .build();
        MassTaskRequest jsonRequest = MassTaskRequest.streaming("demoApp", "crawler")
                .userId("agent")
                .payloadType(PayloadType.JSON)
                .jsonInputs(List.of(Map.of("target", "https://example.test")))
                .build();

        Assertions.assertEquals(TaskMode.SINGLE_RUN, textRequest.getMode());
        Assertions.assertFalse(textRequest.isStreaming());
        Assertions.assertEquals(List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "text", "text", "world")
        ), textRequest.toEngineInputs());

        Assertions.assertEquals(TaskMode.STREAMING, jsonRequest.getMode());
        Assertions.assertTrue(jsonRequest.isStreaming());
        Assertions.assertEquals(List.of(
                Map.of("type", "json", "data", Map.of("target", "https://example.test"))
        ), jsonRequest.toEngineInputs());
    }

    @Test
    void sdkEscapeHatchesStayDeprecated() throws NoSuchMethodException {
        Set<java.lang.reflect.Method> escapeHatches = Set.of(
                MassSdkApplication.class.getDeclaredMethod("unwrap"),
                MassSdkApplication.class.getDeclaredMethod("getEngine"),
                MassSdkApplication.class.getDeclaredMethod("getTaskManager"),
                MassSdkApplication.class.getDeclaredMethod("getWorkerManager"),
                MassSdk.Builder.class.getDeclaredMethod("unwrap"),
                MassSdk.GatewayOptions.class.getDeclaredMethod("unwrap"),
                MassSdk.EngineOptions.class.getDeclaredMethod("unwrap")
        );

        for (java.lang.reflect.Method method : escapeHatches) {
            Assertions.assertTrue(method.isAnnotationPresent(Deprecated.class),
                    method.getDeclaringClass().getSimpleName() + "." + method.getName() + " must remain deprecated");
        }
    }

    private static void assertEngineOperationsFailFast(MassSdkApplication app) {
        List<Executable> operations = List.of(
                () -> app.getTask("task-1"),
                app::getAllTasks,
                () -> app.getTasksByStatus(TaskStatus.READY),
                () -> app.approveTask("task-1"),
                () -> app.rejectTask("task-1"),
                () -> app.blockTask("task-1"),
                () -> app.pauseTask("task-1"),
                () -> app.resumeTaskDetailed("task-1"),
                () -> app.resumeTask("task-1"),
                () -> app.cancelTask("task-1"),
                () -> app.terminateTask("task-1", TaskTerminalReason.MANUAL_CANCELLED),
                () -> app.appendTaskItems("task-1", List.of()),
                () -> app.sealTask("task-1"),
                () -> app.getTaskMessages("task-1"),
                () -> app.resolveTaskStateFromMessages("task-1"),
                () -> app.validateTaskState("task-1"),
                () -> app.getWorker("worker-1"),
                app::getAllWorkers,
                app::getAllWorkerContexts,
                () -> app.getWorkerContexts("worker-1"),
                () -> app.getWorkerContextById("context-1"),
                () -> app.isWorkerLocked("worker-1"),
                () -> app.isWorkerOnline("worker-1"),
                () -> app.registerWorker(WorkerRegistration.builder().workerId("worker-1").build()),
                () -> app.registerWorkerContext(WorkerContextRegistration.builder()
                        .workerContextId("context-1")
                        .workerId("worker-1")
                        .build()),
                () -> app.pullWorker("worker-1"),
                () -> app.replaceDefaultRules(List.of()),
                app::publishTaskEvents
        );

        for (Executable operation : operations) {
            Assertions.assertThrows(IllegalStateException.class, operation);
        }
    }

    private static <T> T waitFor(Duration timeout, ThrowingSupplier<T> supplier) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(50L);
        }
        return supplier.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
