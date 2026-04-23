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
import com.xa.mass.sdk.worker.PollingWorkerSession;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.TransportServerFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                .transportHint("polling")
                .attributes(Map.of("type", "crawler"))
                .build());

        var captor = org.mockito.ArgumentCaptor.forClass(Worker.class);
        verify(engine).addWorker(captor.capture());
        Worker worker = captor.getValue();
        Assertions.assertEquals("crawler-worker-001", worker.getWorkerId());
        Assertions.assertEquals("crawler", worker.getWorkerGroupId());
        Assertions.assertEquals(List.of("crawlerApp"), worker.getSupportedProjects());
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
    void runtimeConvenienceOperationsAvoidEscapeHatchCalls() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);
        app.loadMockData();
        app.publishTaskEvents();

        verify(delegate).loadMockData();
        verify(delegate).publishTaskEvents();
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
    void pollingWorkerSessionCompletesTaskWithoutWebsocketPush() throws Exception {
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
    void pollingWorkerCompatibilityEntryRemainsAvailable() throws Exception {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MassSdkApplication app = MassSdk.builder()
                .transportServer(0, "/sdk-transport")
                .gateway(gateway -> gateway.enabled(false).transportServerEnabled(false).inputQueue(inputQueue).outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            PollingWorkerSession session = app.pollingWorker("compat-worker");
            Assertions.assertEquals("compat-worker", session.workerId());
        } finally {
            app.stop();
        }
    }

    @Test
    void sdkEscapeHatchesStayDeprecated() throws NoSuchMethodException {
        Set<java.lang.reflect.Method> escapeHatches = Set.of(
                MassSdkApplication.class.getDeclaredMethod("unwrap"),
                MassSdkApplication.class.getDeclaredMethod("getEngine"),
                MassSdkApplication.class.getDeclaredMethod("getTaskManager"),
                MassSdkApplication.class.getDeclaredMethod("getWorkerManager"),
                MassSdkApplication.class.getDeclaredMethod("loadMockData"),
                MassSdk.Builder.class.getDeclaredMethod("unwrap"),
                MassSdk.GatewayOptions.class.getDeclaredMethod("unwrap"),
                MassSdk.EngineOptions.class.getDeclaredMethod("unwrap"),
                MassSdk.EngineOptions.class.getDeclaredMethod("mockData", String.class),
                MassSdk.EngineOptions.class.getDeclaredMethod("mockData", String.class, String.class, String.class, String.class)
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
                () -> app.pollingWorker("worker-1"),
                app::loadMockData,
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
