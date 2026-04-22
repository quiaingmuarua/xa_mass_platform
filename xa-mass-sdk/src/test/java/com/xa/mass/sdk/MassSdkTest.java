package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MassSdkTest {

    @Test
    void builderCreatesConsumerFacingApplicationHandle() {
        MassSdkApplication app = MassSdk.builder()
                .server(19090, "/sdk-ws")
                .gateway(gateway -> gateway.enabled(false))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void developmentFactoryWrapsRuntimeApplication() {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);

        MassSdkApplication app = MassSdk.development(18080, inputQueue, outputQueue);

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void engineDependentHelpersFailFastWhenEngineIsUnavailable() {
        MassSdkApplication app = MassSdk.builder()
                .server(19091, "/sdk-ws")
                .gateway(gateway -> gateway.enabled(false))
                .engine(engine -> engine.enabled(false))
                .build();

        Assertions.assertThrows(IllegalStateException.class,
                () -> app.createTask(MassTaskCreateRequest.builder().build()));
        assertEngineOperationsFailFast(app);
    }

    @Test
    void engineDependentHelpersFailFastBeforeStart() {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);

        MassSdkApplication app = MassSdk.development(18081, inputQueue, outputQueue);

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
                .sharedConfig(Map.of("textContent", "hello"))
                .inputs(List.of(
                        Map.of("target", "target-a"),
                        Map.of("target", "target-b")
                ))
                .routingCode("us")
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
        Assertions.assertEquals(Map.of("textContent", "hello"), dto.getSharedConfig());
        Assertions.assertEquals(List.of(
                Map.of("target", "target-a"),
                Map.of("target", "target-b")
        ), dto.getInputs());
        Assertions.assertEquals("us", dto.getRoutingCode());
        Assertions.assertEquals(2, dto.getBatchSize());
        Assertions.assertEquals(5, dto.getDefaultMsgMaxRetryCount());
        Assertions.assertTrue(dto.isOpenEnded());
        Assertions.assertEquals(600, dto.getMaxRuntimeSeconds());
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
                .jsonInputs(List.of(
                        Map.of("url", "https://example.test/page-1"),
                        Map.of("url", "https://example.test/page-2")
                ))
                .routingCode("us")
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
                app::loadMockData,
                () -> app.replaceDefaultRules(List.of()),
                app::publishTaskEvents
        );

        for (Executable operation : operations) {
            Assertions.assertThrows(IllegalStateException.class, operation);
        }
    }
}
