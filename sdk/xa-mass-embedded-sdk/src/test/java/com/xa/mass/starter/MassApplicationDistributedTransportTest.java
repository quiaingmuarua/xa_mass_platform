package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerAvailability;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDispatchBatch;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        transport.setDispatchHandoffFactory(() -> handoff);
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
        transport.setDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTransportResultIngressChannel.class));
        transport.setDeliveryFailureInboxFactory(() -> mock(RedisTransportDeliveryFailureChannel.class));

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

    @Test
    void embeddedRuntimeResolvesDeliveryTargetFromWorkerTransportBinding() throws Exception {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(true);
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-1"));

        LocalDeliveryCommandHandoff handoff = new LocalDeliveryCommandHandoff();
        RecordingAdapter adapter = new RecordingAdapter("websocket", 1);
        TransportConfig transport = disabledTransportConsumerTransport();
        transport.setRuntimeRole(TransportRuntimeRole.EMBEDDED);
        transport.setDispatchHandoffFactory(() -> handoff);
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        CapturingMassEngine massEngine = new CapturingMassEngine(engine);
        MassApplication app = new MassApplication(massEngine, transport, engine);

        app.start();
        try {
            TaskDispatchBatchListener listener = massEngine.listenerRef.get();
            assertNotNull(listener);

            listener.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

            assertTrue(adapter.awaitDispatch(2, TimeUnit.SECONDS),
                    "embedded runtime should resolve selected worker to adapter mailbox from worker transport binding");
            assertEquals(List.of("msg-1"), adapter.dispatchedMessageIds());
            assertEquals(List.of("worker-1"), adapter.dispatchedWorkerIds());
        } finally {
            app.stop();
        }
    }

    @Test
    void transportConsumerDrainsItsAdapterMailbox() throws Exception {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(false);

        LocalDeliveryCommandHandoff handoff = new LocalDeliveryCommandHandoff();
        handoff.enqueue(deliveryBatch("msg-other-mailbox", "worker-2", "other-mailbox"));
        handoff.enqueue(deliveryBatch("msg-local", "worker-1"));
        RecordingAdapter adapter = new RecordingAdapter("websocket", 1);
        TransportConfig transport = disabledTransportConsumerTransport();
        transport.setDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTransportResultIngressChannel.class));
        transport.setDeliveryFailureInboxFactory(() -> mock(RedisTransportDeliveryFailureChannel.class));
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(new CapturingMassEngine(engine), transport, engine);

        app.start();
        try {
            assertTrue(adapter.awaitDispatch(2, TimeUnit.SECONDS), "transport consumer should drain its adapter mailbox");
            assertEquals(List.of("msg-local"), adapter.dispatchedMessageIds());
            assertEquals(List.of("worker-1"), adapter.dispatchedWorkerIds());
            assertEquals(List.of("msg-other-mailbox"), messages(handoff.pollForMailbox("other-mailbox")));
        } finally {
            app.stop();
        }
    }

    @Test
    void transportConsumerRemovesMailboxConsumerAvailabilityOnStop() {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(false);

        LocalDeliveryCommandHandoff handoff = new LocalDeliveryCommandHandoff();
        RecordingAdapter adapter = new RecordingAdapter("websocket", 0);
        TransportConfig transport = disabledTransportConsumerTransport();
        transport.setDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTransportResultIngressChannel.class));
        transport.setDeliveryFailureInboxFactory(() -> mock(RedisTransportDeliveryFailureChannel.class));
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(new CapturingMassEngine(engine), transport, engine);

        app.start();
        List<String> claimedMailboxKeys = handoff.claimedMailboxKeys();
        assertTrue(claimedMailboxKeys.contains(adapterMailboxKey()));

        app.stop();
        assertEquals(claimedMailboxKeys, handoff.releasedMailboxKeys());
    }

    @Test
    void transportConsumerRefreshesMailboxConsumerAvailability() throws Exception {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(false);

        LocalDeliveryCommandHandoff handoff = new LocalDeliveryCommandHandoff();
        RecordingAdapter adapter = new RecordingAdapter("websocket", 0);
        TransportConfig transport = disabledTransportConsumerTransport();
        transport.setAdapterMailboxConsumerAvailabilityMillis(300L);
        transport.setDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTransportResultIngressChannel.class));
        transport.setDeliveryFailureInboxFactory(() -> mock(RedisTransportDeliveryFailureChannel.class));
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(new CapturingMassEngine(engine), transport, engine);

        app.start();
        try {
            assertTrue(handoff.awaitMailboxClaimCount(adapterMailboxKey(), 2, 2, TimeUnit.SECONDS));
            List<AdapterMailboxConsumerAvailability> claims = handoff.claimedConsumerAvailabilities().stream()
                    .filter(lease -> adapterMailboxKey().equals(lease.adapterMailboxKey()))
                    .toList();
            assertTrue(claims.size() >= 2);
            AdapterMailboxConsumerAvailability first = claims.getFirst();
            AdapterMailboxConsumerAvailability last = claims.getLast();
            assertEquals(first.adapterMailboxKey(), last.adapterMailboxKey());
            assertEquals(first.consumerId(), last.consumerId());
            assertTrue(last.availableUntilEpochMillis() > first.availableUntilEpochMillis());
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

    private static AdapterMailboxDispatchBatch deliveryBatch(String messageId, String workerId) {
        return deliveryBatch(messageId, workerId, adapterMailboxKey());
    }

    private static AdapterMailboxDispatchBatch deliveryBatch(String messageId, String workerId, String adapterMailboxKey) {
        DispatchMessage item = dispatchItem(messageId, workerId);
        return new AdapterMailboxDispatchBatch(adapterMailboxKey, List.of(item));
    }

    private static DispatchMessage dispatchItem(String messageId, String workerId) {
        TaskDispatchBinding binding = binding(messageId, workerId);
        String commandId = "cmd-" + messageId;
        return new DispatchMessage(
                commandId,
                workerId,
                new TaskDispatchPayloadEncoder().encode(
                        context(),
                        binding,
                        commandId,
                        new TaskDispatchDeliveryCorrelationCodec().encode(context(), binding)),
                new TaskDispatchDeliveryCorrelationCodec().encode(context(), binding),
                0L,
                System.currentTimeMillis()
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

    private static List<String> messages(List<DispatchMessage> items) {
        return items == null
                ? List.of()
                : items.stream()
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

    private static final class CapturingDeliveryCommandHandoff implements TransportDispatchHandoff {
        private final List<AdapterMailboxDispatchBatch> submitted = new ArrayList<>();

        @Override
        public List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch) {
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

    private static final class LocalDeliveryCommandHandoff implements TransportDispatchHandoff,
            AdapterMailboxConsumerRegistry {
        private final Queue<AdapterMailboxDispatchBatch> batches = new ConcurrentLinkedQueue<>();
        private final List<AdapterMailboxConsumerAvailability> claimedConsumers =
                Collections.synchronizedList(new ArrayList<>());
        private final List<AdapterMailboxConsumerAvailability> releasedConsumers =
                Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running = true;

        private LocalDeliveryCommandHandoff() {
        }

        private void enqueue(AdapterMailboxDispatchBatch batch) {
            batches.add(batch);
        }

        @Override
        public List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch) {
            if (!running) {
                return batch.items().stream()
                        .map(item -> outcome(item, DispatchOutcomeStatus.SHUTDOWN, true, "handoff is stopped"))
                        .toList();
            }
            batches.add(batch);
            return batch.items().stream()
                    .map(item -> outcome(item, DispatchOutcomeStatus.QUEUED, false, null))
                    .toList();
        }

        @Override
        public List<DispatchMessage> poll(String adapterMailboxKey, int maxItems, long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
            do {
                AdapterMailboxDispatchBatch local = pollForMailbox(adapterMailboxKey);
                if (local != null) {
                    return local.items().stream().limit(maxItems).toList();
                }
                if (timeoutMillis <= 0L) {
                    return List.of();
                }
                Thread.sleep(10L);
            } while (System.nanoTime() < deadline && running);
            return List.of();
        }

        private AdapterMailboxDispatchBatch pollForMailbox(String adapterMailboxKey) {
            for (AdapterMailboxDispatchBatch batch : List.copyOf(batches)) {
                if (adapterMailboxKey.equals(batch.adapterMailboxKey())
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

        @Override
        public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
            claimedConsumers.add(lease);
        }

        @Override
        public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
            releasedConsumers.add(lease);
        }

        private List<String> claimedMailboxKeys() {
            synchronized (claimedConsumers) {
                return claimedConsumers.stream()
                        .map(AdapterMailboxConsumerAvailability::adapterMailboxKey)
                        .toList();
            }
        }

        private List<AdapterMailboxConsumerAvailability> claimedConsumerAvailabilities() {
            synchronized (claimedConsumers) {
                return List.copyOf(claimedConsumers);
            }
        }

        private boolean awaitMailboxClaimCount(String adapterMailboxKey,
                                               int expected,
                                               long timeout,
                                               TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                synchronized (claimedConsumers) {
                    if (mailboxClaimCount(adapterMailboxKey) >= expected) {
                        return true;
                    }
                }
                Thread.sleep(10L);
            }
            synchronized (claimedConsumers) {
                return mailboxClaimCount(adapterMailboxKey) >= expected;
            }
        }

        private long mailboxClaimCount(String adapterMailboxKey) {
            return claimedConsumers.stream()
                    .filter(lease -> adapterMailboxKey.equals(lease.adapterMailboxKey()))
                    .count();
        }

        private List<String> releasedMailboxKeys() {
            synchronized (releasedConsumers) {
                return releasedConsumers.stream()
                        .map(AdapterMailboxConsumerAvailability::adapterMailboxKey)
                        .toList();
            }
        }
    }

    private static final class RecordingAdapterBootstrap implements TransportAdapterBootstrap {
        private final RecordingAdapter adapter;

        private RecordingAdapterBootstrap(RecordingAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public TransportAdapterDescriptor descriptor() {
            return new TransportAdapterDescriptor(adapter.adapterId(), WorkerTransportHints.REALTIME);
        }

        @Override
        public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
            context.sessionEvidence().publisher().claimEndpoint(
                    "worker-1",
                    "demo-workers",
                    "session-worker-1",
                    "test"
            );
            return TransportAdapterContribution.builder()
                    .addTransportBinding(TransportBinding.builder(
                            adapter.adapterId(),
                            WorkerTransportHints.REALTIME
                    )
                            .adapterMailboxKey(context.mailbox().assignedMailboxKey())
                            .build())
                    .addAdapterMailboxConsumer(context.mailbox().consumer(
                            adapter.adapterId(),
                            adapter
                    ))
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
        public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
            List<DispatchOutcome> outcomes = new ArrayList<>();
            for (DispatchMessage item : items) {
                dispatchedMessageIds.add(new TaskDispatchDeliveryCorrelationCodec()
                        .decode(item.correlationRef())
                        .messageId());
                dispatchedWorkerIds.add(item.selectedWorkerId());
                outcomes.add(DispatchOutcomeFactory.delivered(item));
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
