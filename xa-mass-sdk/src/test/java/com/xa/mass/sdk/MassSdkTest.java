package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
                app::loadMockData,
                app::publishTaskEvents
        );

        for (Executable operation : operations) {
            Assertions.assertThrows(IllegalStateException.class, operation);
        }
    }
}
