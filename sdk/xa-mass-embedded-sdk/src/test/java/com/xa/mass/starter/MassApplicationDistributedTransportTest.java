package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDispatchBatch;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

        CapturingDeliveryCommandHandoff handoff = new CapturingDeliveryCommandHandoff();
        TransportConfig transport = disabledEngineProducerTransport();
        transport.setDispatchQueueFactory(() -> handoff);
        transport.setTaskResultIngressQueueFactory(() -> mock(RedisTransportResultIngressChannel.class));

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

            assertEquals(1, handoff.submitted.size());
            AdapterMailboxDispatchBatch firstBatch = handoff.submitted.get(0);
            assertEquals(adapterMailboxKey(), firstBatch.adapterMailboxKey());
            assertEquals(List.of("msg-1", "msg-2"), messages(firstBatch));
            assertEquals("worker-1", firstBatch.items().getFirst().selectedWorkerId());
            assertEquals("worker-2", firstBatch.items().get(1).selectedWorkerId());
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

        CapturingDeliveryCommandHandoff handoff = new CapturingDeliveryCommandHandoff();
        TransportConfig transport = disabledEngineProducerTransport();
        transport.setDispatchQueueFactory(() -> handoff);
        transport.setTaskResultIngressQueueFactory(() -> mock(RedisTransportResultIngressChannel.class));

        CapturingMassEngine massEngine = new CapturingMassEngine(engine);
        MassApplication app = new MassApplication(massEngine, transport, engine);

        app.start();
        try {
            TaskDispatchBatchListener listener = massEngine.listenerRef.get();
            assertNotNull(listener);

            listener.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

            assertEquals(0, handoff.submitted.size());
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
        transport.getBundledWebSocketAdapterConfig().setEnabled(false);
        transport.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        transport.getBundledSocketAdapterConfig().setEnabled(false);
        transport.getBundledSocketAdapterConfig().setServerEnabled(false);
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

    private static List<String> messages(AdapterMailboxDispatchBatch batch) {
        return batch == null
                ? List.of()
                : batch.items().stream()
                .map(item -> new TaskDispatchDeliveryCorrelationCodec().decode(item.correlationRef())
                .messageId())
                .toList();
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

    private static DispatchOutcome outcome(DispatchMessage item,
                                           DispatchOutcomeStatus status,
                                           boolean retryable,
                                           String reason) {
        return DispatchOutcomeFactory.fromItem(
                item,
                status,
                retryable,
                reason
        );
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

    private static final class CapturingDeliveryCommandHandoff implements TransportDispatchQueue {
        private final List<AdapterMailboxDispatchBatch> submitted = new ArrayList<>();

        @Override
        public List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items) {
            AdapterMailboxDispatchBatch batch = new AdapterMailboxDispatchBatch(dispatchQueueKey, items);
            submitted.add(batch);
            return batch.items().stream()
                    .map(item -> outcome(item, DispatchOutcomeStatus.QUEUED, false, null))
                    .toList();
        }

        @Override
        public List<DispatchMessage> poll(String adapterMailboxKey, int maxItems, long timeoutMillis) {
            return List.of();
        }

        @Override
        public void shutdown() {
        }
    }

}
