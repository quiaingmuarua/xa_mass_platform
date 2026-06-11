package com.xa.mass.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.route.TransportRouteOwnerInspectionView;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.RedisTaskResultIngestChannel;
import com.xa.mass.transport.runtime.RedisTransportDispatchFailureChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBatch;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBinding;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchHandoff;
import com.xa.mass.transport.runtime.node.InMemoryTransportNodeRegistry;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import com.xa.mass.transport.worker.WorkerAdapter;
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

        InMemoryTransportRouteOwnerStore nodeOneRouteOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");
        InMemoryTransportRouteOwnerStore nodeTwoRouteOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "node-2");
        nodeOneRouteOwnerStore.claimRouteOwner("worker-1", "websocket", routeKey(), "conn-1", "connected");
        nodeTwoRouteOwnerStore.claimRouteOwner("worker-2", "websocket", routeKey(), "conn-2", "connected");
        CombinedRouteOwnerStore routeOwnerStore = new CombinedRouteOwnerStore(
                List.of(nodeOneRouteOwnerStore, nodeTwoRouteOwnerStore)
        );

        InMemoryTransportNodeRegistry nodeRegistry = new InMemoryTransportNodeRegistry();
        nodeRegistry.register("node-1", List.of("websocket"), 1L);
        nodeRegistry.register("node-2", List.of("websocket"), 1L);

        CapturingRouteTargetedHandoff handoff = new CapturingRouteTargetedHandoff();
        TransportConfig transport = disabledEngineProducerTransport();
        transport.setRouteOwnerStoreFactory(() -> routeOwnerStore);
        transport.setTransportNodeRegistryFactory(() -> nodeRegistry);
        transport.setRouteTargetedTaskDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTaskResultIngestChannel.class));
        transport.setDispatchFailureInboxFactory(() -> mock(RedisTransportDispatchFailureChannel.class));

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

            assertEquals(2, handoff.submitted.size());
            RouteTargetedTaskDispatchBatch firstBatch = handoff.submitted.get(0);
            assertEquals(routeKey(), firstBatch.routeKey());
            assertEquals(List.of("msg-1"), messages(firstBatch));
            assertEquals("node-1", firstBatch.targetTransportNodeId());
            RouteTargetedTaskDispatchBatch secondBatch = handoff.submitted.get(1);
            assertEquals(routeKey(), secondBatch.routeKey());
            assertEquals(List.of("msg-2"), messages(secondBatch));
            assertEquals("node-2", secondBatch.targetTransportNodeId());
        } finally {
            app.stop();
        }
    }

    @Test
    void transportConsumerDrainsOnlyLocallyReadyRouteBatches() throws Exception {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(false);

        LocalRouteTargetedHandoff handoff = new LocalRouteTargetedHandoff("node-1");
        handoff.submit(new RouteTargetedTaskDispatchBatch(
                context(),
                routeKey(),
                "node-2",
                List.of(delivery("msg-node-2", "worker-2"))
        ));
        handoff.submit(new RouteTargetedTaskDispatchBatch(
                context(),
                routeKey(),
                "node-1",
                List.of(delivery("msg-node-1", "worker-1"))
        ));

        RecordingAdapter adapter = new RecordingAdapter("websocket", 1);
        TransportConfig transport = disabledTransportConsumerTransport("node-1");
        transport.setRouteTargetedTaskDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTaskResultIngestChannel.class));
        transport.setDispatchFailureInboxFactory(() -> mock(RedisTransportDispatchFailureChannel.class));
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(new CapturingMassEngine(engine), transport, engine);

        app.start();
        try {
            assertTrue(adapter.awaitDispatch(2, TimeUnit.SECONDS), "transport consumer should drain locally ready route inbox");
            assertEquals(List.of("msg-node-1"), adapter.dispatchedMessageIds());
            assertEquals(List.of(routeKey()), adapter.dispatchedRouteKeys());
            assertEquals(List.of("msg-node-2"), messages(handoff.pollForNode("node-2")));
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

    private static TransportConfig disabledTransportConsumerTransport(String transportNodeId) {
        TransportConfig transport = new TransportConfig();
        transport.setRuntimeRole(TransportRuntimeRole.TRANSPORT_CONSUMER);
        transport.setTransportNodeId(transportNodeId);
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
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        );
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "demo.event", Map.of());
    }

    private static TaskDispatchBinding binding(String messageId, String workerId) {
        return TaskDispatchBinding.workerLevelWithTransportEvidence(
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
                "websocket",
                WorkerTransportHints.REALTIME,
                null,
                "test-fixture"
        );
    }

    private static RouteTargetedTaskDispatchBinding delivery(String messageId, String workerId) {
        return new RouteTargetedTaskDispatchBinding(routeKey(), "websocket", binding(messageId, workerId));
    }

    private static String routeKey() {
        return CanonicalWorkerGroupRouteKeyCodec.encode("demo-workers");
    }

    private static List<String> messages(RouteTargetedTaskDispatchBatch batch) {
        return batch == null
                ? List.of()
                : batch.deliveryBindings().stream()
                .map(RouteTargetedTaskDispatchBinding::dispatchBinding)
                .map(TaskDispatchBinding::messageId)
                .toList();
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

    private static final class CapturingRouteTargetedHandoff implements RouteTargetedTaskDispatchHandoff {
        private final List<RouteTargetedTaskDispatchBatch> submitted = new ArrayList<>();

        @Override
        public void submit(RouteTargetedTaskDispatchBatch batch) {
            submitted.add(batch);
        }

        @Override
        public RouteTargetedTaskDispatchBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    private static final class LocalRouteTargetedHandoff implements RouteTargetedTaskDispatchHandoff {
        private final String localTransportNodeId;
        private final Queue<RouteTargetedTaskDispatchBatch> batches = new ConcurrentLinkedQueue<>();
        private volatile boolean running = true;

        private LocalRouteTargetedHandoff(String localTransportNodeId) {
            this.localTransportNodeId = localTransportNodeId;
        }

        @Override
        public void submit(RouteTargetedTaskDispatchBatch batch) {
            if (!running) {
                throw new IllegalStateException("handoff is stopped");
            }
            batches.add(batch);
        }

        @Override
        public RouteTargetedTaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
            do {
                RouteTargetedTaskDispatchBatch local = pollForNode(localTransportNodeId);
                if (local != null || timeoutMillis <= 0L) {
                    return local;
                }
                Thread.sleep(10L);
            } while (System.nanoTime() < deadline && running);
            return null;
        }

        private RouteTargetedTaskDispatchBatch pollForNode(String transportNodeId) {
            for (RouteTargetedTaskDispatchBatch batch : List.copyOf(batches)) {
                if (batch.targetTransportNodeId().equals(transportNodeId)
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
        public void contribute(TransportAdapterBootstrapContext context) {
            context.registerTransportBinding(TransportBinding.builder(adapter)
                    .routeKeyResolver(context.getRouteKeyResolver())
                    .build());
        }
    }

    private static final class RecordingAdapter implements WorkerAdapter {
        private final String adapterId;
        private final CountDownLatch dispatchLatch;
        private final List<String> dispatchedMessageIds = Collections.synchronizedList(new ArrayList<>());
        private final List<String> dispatchedRouteKeys = Collections.synchronizedList(new ArrayList<>());

        private RecordingAdapter(String adapterId, int expectedDispatches) {
            this.adapterId = adapterId;
            this.dispatchLatch = new CountDownLatch(expectedDispatches);
        }

        @Override
        public String protocol() {
            return adapterId;
        }

        @Override
        public String transportHint() {
            return WorkerTransportHints.REALTIME;
        }

        @Override
        public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
            List<DispatchOutcome> outcomes = new ArrayList<>();
            for (TransportDispatchEnvelope envelope : envelopes) {
                dispatchedMessageIds.add(envelope.getPacket().messageId());
                dispatchedRouteKeys.add(envelope.getRouteKey());
                outcomes.add(DispatchOutcome.sent(adapterId, envelope));
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

        private List<String> dispatchedRouteKeys() {
            synchronized (dispatchedRouteKeys) {
                return List.copyOf(dispatchedRouteKeys);
            }
        }
    }

    private static final class CombinedRouteOwnerStore implements TransportRouteOwnerStore,
            WorkerDispatchRouteOwnerView,
            TransportRouteOwnerInspectionView {
        private final List<InMemoryTransportRouteOwnerStore> stores;

        private CombinedRouteOwnerStore(List<InMemoryTransportRouteOwnerStore> stores) {
            this.stores = stores;
        }

        @Override
        public TransportRouteOwnerRecord claimRouteOwner(String workerId,
                                                         String adapterId,
                                                         String routeKey,
                                                         String connectionId,
                                                         String reason) {
            return stores.getFirst().claimRouteOwner(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public TransportRouteOwnerRecord refreshHeartbeat(String workerId,
                                                          String adapterId,
                                                          String routeKey,
                                                          String connectionId,
                                                          String reason) {
            return stores.getFirst().refreshHeartbeat(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public TransportRouteOwnerRecord releaseRouteOwner(String workerId,
                                                           String adapterId,
                                                           String routeKey,
                                                           String connectionId,
                                                           String reason) {
            return stores.getFirst().releaseRouteOwner(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public TransportRouteOwnerRecord getLatestOwnerByWorker(String workerId) {
            return listActiveRouteOwners().stream()
                    .filter(owner -> workerId.equals(owner.getWorkerId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<WorkerDispatchRouteOwner> currentOwners(String routeKey) {
            List<WorkerDispatchRouteOwner> owners = new ArrayList<>();
            for (InMemoryTransportRouteOwnerStore store : stores) {
                owners.addAll(store.currentOwners(routeKey));
            }
            return List.copyOf(owners);
        }

        @Override
        public List<TransportRouteOwnerRecord> listActiveRouteOwners() {
            List<TransportRouteOwnerRecord> owners = new ArrayList<>();
            for (InMemoryTransportRouteOwnerStore store : stores) {
                owners.addAll(store.listActiveRouteOwners());
            }
            return List.copyOf(owners);
        }

        @Override
        public int pruneExpired() {
            return stores.stream().mapToInt(InMemoryTransportRouteOwnerStore::pruneExpired).sum();
        }

        @Override
        public long getLeaseMillis() {
            return stores.stream().mapToLong(InMemoryTransportRouteOwnerStore::getLeaseMillis).min().orElse(30_000L);
        }
    }
}
