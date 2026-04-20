package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.gateway.queue.Envelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MassSdkTest {

    @Test
    void builderCreatesConsumerFacingApplicationHandle() {
        MassSdkApplication app = MassSdk.builder()
                .server(19090, "/sdk-ws")
                .gateway(gateway -> gateway.enabled(false))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app);
        assertNotNull(app.unwrap());
        assertFalse(app.isRunning());
    }

    @Test
    void developmentFactoryWrapsRuntimeApplication() {
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue<>("Envelope", Envelope.class);

        MassSdkApplication app = MassSdk.development(18080, inputQueue, outputQueue);

        assertNotNull(app);
        assertNotNull(app.unwrap());
        assertFalse(app.isRunning());
        assertNotNull(app.getEngine());
    }

    @Test
    void engineDependentHelpersFailFastWhenEngineIsUnavailable() {
        MassSdkApplication app = MassSdk.builder()
                .server(19091, "/sdk-ws")
                .gateway(gateway -> gateway.enabled(false))
                .engine(engine -> engine.enabled(false))
                .build();

        Assertions.assertThrows(IllegalStateException.class, () -> app.createTask(null));
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
                () -> app.isWorkerOnline("worker-1")
        );

        for (Executable operation : operations) {
            Assertions.assertThrows(IllegalStateException.class, operation);
        }
    }
}
