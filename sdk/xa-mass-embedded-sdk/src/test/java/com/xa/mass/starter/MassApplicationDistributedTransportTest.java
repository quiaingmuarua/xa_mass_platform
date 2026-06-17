package com.xa.mass.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.sdk.worker.TaskDispatchDeliveryCorrelationCodec;
import com.xa.mass.sdk.worker.TaskDispatchPayloadCodec;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandBatch;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandReference;
import com.xa.mass.transport.runtime.delivery.DeliveryQueueOffer;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MassApplicationDistributedTransportTest {

    @Test
    void engineProducerSubmitsAssignedBatchByGroupRouteKey() {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(true);
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-1"));
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-2"));

        CapturingDeliveryCommandHandoff handoff = new CapturingDeliveryCommandHandoff();
        TransportConfig transport = disabledEngineProducerTransport();
        transport.setDeliveryCommandHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTransportResultIngressChannel.class));
        transport.setDeliveryFailureInboxFactory(() -> mock(RedisTransportDeliveryFailureChannel.class));

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
            DeliveryCommandBatch firstBatch = handoff.submitted.get(0);
            assertEquals(deliveryQueueKey(), firstBatch.deliveryQueueKey());
            assertEquals(List.of("msg-1", "msg-2"), messages(firstBatch));
            assertEquals("worker-1", firstBatch.commands().getFirst().getSelectedWorkerId());
            assertEquals("worker-2", firstBatch.commands().get(1).getSelectedWorkerId());
        } finally {
            app.stop();
        }
    }

    @Test
    void transportConsumerDrainsOnlyLocallyReadySelectedWorkerBatches() throws Exception {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(false);

        LocalDeliveryCommandHandoff handoff = new LocalDeliveryCommandHandoff("worker-1");
        handoff.enqueue(deliveryBatch("msg-remote", "worker-2"));
        handoff.enqueue(deliveryBatch("msg-local", "worker-1"));
        RecordingAdapter adapter = new RecordingAdapter("websocket", 1);
        TransportConfig transport = disabledTransportConsumerTransport();
        transport.setDeliveryCommandHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTransportResultIngressChannel.class));
        transport.setDeliveryFailureInboxFactory(() -> mock(RedisTransportDeliveryFailureChannel.class));
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(new CapturingMassEngine(engine), transport, engine);

        app.start();
        try {
            assertTrue(adapter.awaitDispatch(2, TimeUnit.SECONDS), "transport consumer should drain bucket queue and demux selected worker locally");
            assertEquals(List.of("msg-local"), adapter.dispatchedMessageIds());
            assertEquals(List.of("worker-1"), adapter.dispatchedWorkerIds());
            assertEquals(List.of("msg-remote"), messages(handoff.pollForSelectedWorker("worker-2")));
        } finally {
            app.stop();
        }
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

    private static TransportConfig disabledTransportConsumerTransport() {
        TransportConfig transport = new TransportConfig();
        transport.setRuntimeRole(TransportRuntimeRole.TRANSPORT_CONSUMER);
        transport.getBundledWebSocketAdapterConfig().setEnabled(false);
        transport.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        transport.getBundledSocketAdapterConfig().setEnabled(false);
        transport.getBundledSocketAdapterConfig().setServerEnabled(false);
        return transport;
    }

    private static Worker worker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("demo-workers");
        worker.setAdapterId("websocket");
        worker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        return worker;
    }

    private static WorkerDeclarationRecord workerDeclaration(String workerId) {
        Worker worker = worker(workerId);
        return new WorkerDeclarationRecord(
                worker.getWorkerId(),
                worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes()
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
                "test-fixture"
        );
    }

    private static DeliveryCommandBatch deliveryBatch(String messageId, String workerId) {
        DeliveryCommand command = deliveryCommand(messageId, workerId);
        return new DeliveryCommandBatch(
                deliveryQueueKey(),
                List.of(new DeliveryCommandReference(deliveryQueueKey(), command.getCommandId())),
                List.of(command)
        );
    }

    private static DeliveryCommand deliveryCommand(String messageId, String workerId) {
        TaskDispatchBinding binding = binding(messageId, workerId);
        String commandId = "cmd-" + messageId;
        return new DeliveryCommand(
                commandId,
                "demo-workers",
                workerId,
                new TaskDispatchPayloadCodec().encode(context(), binding, workerId),
                new TaskDispatchDeliveryCorrelationCodec().encode(context(), binding),
                0L,
                System.currentTimeMillis()
        );
    }

    private static String deliveryQueueKey() {
        return "bucket:ZGVtby13b3JrZXJz";
    }

    private static List<String> messages(DeliveryCommandBatch batch) {
        return batch == null
                ? List.of()
                : batch.commands().stream()
                .map(command -> new TaskDispatchPayloadCodec().decode(command.getPayload(), command.getCorrelationRef())
                        .getMessageId())
                .toList();
    }

    private static DispatchOutcome outcome(DeliveryCommandBatch batch,
                                           DeliveryCommand item,
                                           DispatchOutcomeStatus status,
                                           boolean retryable,
                                           String reason) {
        return DispatchOutcome.fromCommand(
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

    private static final class CapturingDeliveryCommandHandoff implements TransportDeliveryCommandHandoff {
        private final List<DeliveryCommandBatch> submitted = new ArrayList<>();

        @Override
        public List<DispatchOutcome> offer(DeliveryQueueOffer offer) {
            DeliveryCommandBatch batch = new DeliveryCommandBatch(offer.deliveryQueueKey(), offer.commands());
            submitted.add(batch);
            return batch.items().stream()
                    .map(item -> outcome(batch, item, DispatchOutcomeStatus.QUEUED, false, null))
                    .toList();
        }

        @Override
        public DeliveryCommandBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    private static final class LocalDeliveryCommandHandoff implements TransportDeliveryCommandHandoff {
        private final String localSelectedWorkerId;
        private final Queue<DeliveryCommandBatch> batches = new ConcurrentLinkedQueue<>();
        private volatile boolean running = true;

        private LocalDeliveryCommandHandoff(String localSelectedWorkerId) {
            this.localSelectedWorkerId = localSelectedWorkerId;
        }

        private void enqueue(DeliveryCommandBatch batch) {
            batches.add(batch);
        }

        @Override
        public List<DispatchOutcome> offer(DeliveryQueueOffer offer) {
            DeliveryCommandBatch batch = new DeliveryCommandBatch(offer.deliveryQueueKey(), offer.commands());
            if (!running) {
                return batch.items().stream()
                        .map(item -> outcome(batch, item, DispatchOutcomeStatus.SHUTDOWN, true, "handoff is stopped"))
                        .toList();
            }
            batches.add(batch);
            return batch.items().stream()
                    .map(item -> outcome(batch, item, DispatchOutcomeStatus.QUEUED, false, null))
                    .toList();
        }

        @Override
        public DeliveryCommandBatch poll(long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
            do {
                DeliveryCommandBatch local = pollForSelectedWorker(localSelectedWorkerId);
                if (local != null || timeoutMillis <= 0L) {
                    return local;
                }
                Thread.sleep(10L);
            } while (System.nanoTime() < deadline && running);
            return null;
        }

        private DeliveryCommandBatch pollForSelectedWorker(String selectedWorkerId) {
            for (DeliveryCommandBatch batch : List.copyOf(batches)) {
                if (!batch.items().isEmpty()
                        && batch.items().getFirst().getSelectedWorkerId().equals(selectedWorkerId)
                        && batches.remove(batch)) {
                    return batch;
                }
            }
            return null;
        }

        @Override
        public void shutdown() {
            running = false;
        }
    }

    private static final class RecordingAdapterBootstrap implements TransportAdapterBootstrap {
        private final RecordingAdapter adapter;

        private RecordingAdapterBootstrap(RecordingAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
            context.getEndpointLeaseStore().claimEndpointLease(new TransportEndpointLeaseClaim(
                    "worker-1",
                    "demo-workers",
                    adapter.adapterId(),
                    "route-worker-1",
                    "session-worker-1",
                    "test"
            ));
            return TransportAdapterContribution.builder()
                    .addTransportBinding(TransportBinding.builder(
                            adapter.adapterId(),
                            WorkerTransportHints.REALTIME,
                            adapter
                    ).build())
                    .build();
        }
    }

    private static final class RecordingAdapter implements AdapterCommandExecutor {
        private final String adapterId;
        private final CountDownLatch dispatchLatch;
        private final List<String> dispatchedMessageIds = Collections.synchronizedList(new ArrayList<>());
        private final List<String> dispatchedWorkerIds = Collections.synchronizedList(new ArrayList<>());

        private RecordingAdapter(String adapterId, int expectedDispatches) {
            this.adapterId = adapterId;
            this.dispatchLatch = new CountDownLatch(expectedDispatches);
        }

        public String protocol() {
            return adapterId;
        }

        public String adapterId() {
            return adapterId;
        }

        public String transportHint() {
            return WorkerTransportHints.REALTIME;
        }

        @Override
        public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
            TaskDispatchPayloadCodec codec = new TaskDispatchPayloadCodec();
            List<DispatchOutcome> outcomes = new ArrayList<>();
            for (DeliveryCommand command : commands) {
                dispatchedMessageIds.add(codec.decode(command.getPayload(), command.getCorrelationRef()).getMessageId());
                dispatchedWorkerIds.add(command.getSelectedWorkerId());
                outcomes.add(DispatchOutcome.delivered(command));
                dispatchLatch.countDown();
            }
            return List.copyOf(outcomes);
        }

        private boolean awaitDispatch(long timeout, TimeUnit unit) throws InterruptedException {
            return dispatchLatch.await(timeout, unit);
        }

        private List<String> dispatchedMessageIds() {
            synchronized (dispatchedMessageIds) {
                return List.copyOf(dispatchedMessageIds);
            }
        }

        private List<String> dispatchedWorkerIds() {
            synchronized (dispatchedWorkerIds) {
                return List.copyOf(dispatchedWorkerIds);
            }
        }
    }

}
