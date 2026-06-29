package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassApplicationDistributedTransportTest {

    @Test
    void engineProducerSubmitsAssignedBatchByGroupRouteKey() {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(true);
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-1"));
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-2"));
        engine.setWorkerDeliveryTargetResolver(selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                selectedWorkerId,
                adapterMailboxKey(),
                Long.MAX_VALUE
        )));

        TransportConfig transport = disabledEngineProducerTransport();

        CapturingMassEngine massEngine = new CapturingMassEngine(engine);
        MassApplication app = new MassApplication(massEngine, transport, engine);

        app.start();
        try {
            TaskDispatchBatchListener listener = massEngine.listenerRef.get();
            assertNotNull(listener);

            listener.onTaskDispatchBatch(context(), List.of(
                    binding("msg-1", "worker-1"),
                    binding("msg-2", "worker-2")
            ));
        } finally {
            app.stop();
        }
    }

    @Test
    void engineProducerRejectsMismatchedSelectedWorkerDeliveryTargetEvidence() {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(true);
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-1"));
        engine.setWorkerDeliveryTargetResolver(selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                "other-worker",
                adapterMailboxKey(),
                Long.MAX_VALUE
        )));

        TransportConfig transport = disabledEngineProducerTransport();

        CapturingMassEngine massEngine = new CapturingMassEngine(engine);
        MassApplication app = new MassApplication(massEngine, transport, engine);

        app.start();
        try {
            TaskDispatchBatchListener listener = massEngine.listenerRef.get();
            assertNotNull(listener);

            listener.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));
        } finally {
            app.stop();
        }
    }

    @Test
    void engineProducerRequiresExplicitWorkerDeliveryTargetResolver() {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(true);

        TransportConfig transport = disabledEngineProducerTransport();
        CapturingMassEngine massEngine = new CapturingMassEngine(engine);
        MassApplication app = new MassApplication(massEngine, transport, engine);

        RuntimeException failure = assertThrows(RuntimeException.class, app::start);
        assertTrue(hasCauseMessage(failure, "engine-producer runtime requires an explicit worker delivery target resolver"));
    }

    private static TransportConfig disabledEngineProducerTransport() {
        TransportConfig transport = new TransportConfig();
        transport.setRuntimeRole(TransportRuntimeRole.ENGINE_PRODUCER);
        transport.getBundledWebSocketAdapterDeclaration().setEnabled(false);
        transport.getBundledWebSocketAdapterDeclaration().setServerEnabled(false);
        transport.getBundledSocketAdapterDeclaration().setEnabled(false);
        transport.getBundledSocketAdapterDeclaration().setServerEnabled(false);
        return transport;
    }

    private static WorkerDeclarationRecord workerDeclaration(String workerId) {
        return new WorkerDeclarationRecord(
                workerId,
                "demo-workers",
                "realtime",
                null,
                1,
                Map.of()
        );
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "demo.event", Map.of());
    }

    private static TaskDispatchBinding binding(String messageId, String workerId) {
        return TaskDispatchBinding.workerLevelWithEvidence(
                "task-1",
                messageId,
                "demo.event",
                Map.of(),
                null,
                0,
                "attempt-" + messageId,
                1,
                "lease-" + messageId,
                workerId,
                "batch-1",
                "demo-workers",
                null,
                null,
                null,
                "test-fixture"
        );
    }

    private static String adapterMailboxKey() {
        return "websocket";
    }

    private static boolean hasCauseMessage(Throwable failure, String expectedMessage) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(expectedMessage)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class CapturingMassEngine extends MassEngine {
        private final AtomicReference<TaskDispatchBatchListener> listenerRef = new AtomicReference<>();
        private boolean running;

        private CapturingMassEngine(EngineConfig config) {
            super(config);
        }

        @Override
        public void start(TaskDispatchBatchListener dispatchBatchListener) {
            listenerRef.set(dispatchBatchListener);
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

}
