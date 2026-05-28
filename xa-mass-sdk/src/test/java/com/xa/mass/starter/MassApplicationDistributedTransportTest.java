package com.xa.mass.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.NodeTargetedTaskDispatchHandoff;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.storage.api.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.presence.WorkerDispatchRouteOwner;
import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.RedisTaskResultIngestChannel;
import com.xa.mass.transport.runtime.RedisTransportDispatchFailureChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.node.InMemoryTransportNodeRegistry;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MassApplicationDistributedTransportTest {

    @Test
    void engineProducerRoutesAssignedBatchToSelectedTransportNodeInbox() {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(true);
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-1"));
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-2"));

        InMemoryWorkerPresenceStore nodeOnePresence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryWorkerPresenceStore nodeTwoPresence = new InMemoryWorkerPresenceStore(30_000L, "node-2");
        nodeOnePresence.markOnline("worker-1", "websocket", "route-1", "conn-1", "connected");
        nodeTwoPresence.markOnline("worker-2", "websocket", "route-2", "conn-2", "connected");
        CombinedPresenceStore presenceStore = new CombinedPresenceStore(List.of(nodeOnePresence, nodeTwoPresence));

        InMemoryTransportNodeRegistry nodeRegistry = new InMemoryTransportNodeRegistry();
        nodeRegistry.register("node-1", List.of("websocket"), 1L);
        nodeRegistry.register("node-2", List.of("websocket"), 1L);

        CapturingNodeTargetedHandoff handoff = new CapturingNodeTargetedHandoff();
        TransportConfig transport = disabledEngineProducerTransport();
        transport.setPresenceStoreFactory(() -> presenceStore);
        transport.setTransportNodeRegistryFactory(() -> nodeRegistry);
        transport.setTaskDispatchHandoffFactory(() -> handoff);
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

            assertEquals(List.of("msg-1"), messages(handoff.submittedByNode.get("node-1")));
            assertEquals(List.of("msg-2"), messages(handoff.submittedByNode.get("node-2")));
            assertEquals(WorkerReachabilityState.ONLINE,
                    engine.getWorkerReachabilityView().getWorkerReachability("worker-1"));

            nodeRegistry.markOffline("node-1");
            assertEquals(WorkerReachabilityState.OFFLINE,
                    engine.getWorkerReachabilityView().getWorkerReachability("worker-1"));
        } finally {
            app.stop();
        }
    }

    @Test
    void transportConsumerDrainsOnlyItsOwnTransportNodeInbox() throws Exception {
        EngineConfig engine = new EngineConfig();
        engine.setEnabled(false);
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-1"));
        engine.getWorkerDeclarationStore().addWorker(workerDeclaration("worker-2"));

        LocalNodeTargetedHandoff handoff = new LocalNodeTargetedHandoff("node-1");
        handoff.submit("node-2", new TaskDispatchBatch(context(), List.of(binding("msg-node-2", "worker-2"))));
        handoff.submit("node-1", new TaskDispatchBatch(context(), List.of(binding("msg-node-1", "worker-1"))));

        RecordingAdapter adapter = new RecordingAdapter("websocket", 1);
        TransportConfig transport = disabledTransportConsumerTransport("node-1");
        transport.setTaskDispatchHandoffFactory(() -> handoff);
        transport.setTaskResultInboxFactory(() -> mock(RedisTaskResultIngestChannel.class));
        transport.setDispatchFailureInboxFactory(() -> mock(RedisTransportDispatchFailureChannel.class));
        transport.setPrimaryTransportAdapterBootstrap(new RecordingAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(new CapturingMassEngine(engine), transport, engine);

        app.start();
        try {
            assertTrue(adapter.awaitDispatch(2, TimeUnit.SECONDS), "transport consumer should drain its local node inbox");
            assertEquals(List.of("msg-node-1"), adapter.dispatchedMessageIds());
            assertTrue(handoff.polledNodes().stream().allMatch("node-1"::equals));
            assertEquals(List.of("msg-node-2"), messages(handoff.poll("node-2", 0)));
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
        return new TaskDispatchBinding(
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
                "batch-1"
        );
    }

    private static List<String> messages(TaskDispatchBatch batch) {
        return batch == null
                ? List.of()
                : batch.dispatchBindings().stream().map(TaskDispatchBinding::messageId).toList();
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

    private static final class CapturingNodeTargetedHandoff implements NodeTargetedTaskDispatchHandoff {
        private final Map<String, TaskDispatchBatch> submittedByNode = new LinkedHashMap<>();

        @Override
        public void submit(String transportNodeId, TaskDispatchBatch batch) {
            submittedByNode.put(transportNodeId, batch);
        }

        @Override
        public TaskDispatchBatch poll(String transportNodeId, long timeoutMillis) {
            return null;
        }

        @Override
        public void submit(TaskDispatchBatch batch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskDispatchBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    private static final class LocalNodeTargetedHandoff implements NodeTargetedTaskDispatchHandoff {
        private final String localTransportNodeId;
        private final Map<String, BlockingQueue<TaskDispatchBatch>> batchesByNode = new ConcurrentHashMap<>();
        private final Queue<String> polledNodes = new ConcurrentLinkedQueue<>();
        private volatile boolean running = true;

        private LocalNodeTargetedHandoff(String localTransportNodeId) {
            this.localTransportNodeId = localTransportNodeId;
        }

        @Override
        public void submit(String transportNodeId, TaskDispatchBatch batch) {
            if (!running) {
                throw new IllegalStateException("handoff is stopped");
            }
            batchesByNode.computeIfAbsent(transportNodeId, ignored -> new LinkedBlockingQueue<>()).add(batch);
        }

        @Override
        public TaskDispatchBatch poll(String transportNodeId, long timeoutMillis) throws InterruptedException {
            polledNodes.add(transportNodeId);
            BlockingQueue<TaskDispatchBatch> queue =
                    batchesByNode.computeIfAbsent(transportNodeId, ignored -> new LinkedBlockingQueue<>());
            if (timeoutMillis <= 0L) {
                return queue.poll();
            }
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void submit(TaskDispatchBatch batch) {
            submit(localTransportNodeId, batch);
        }

        @Override
        public TaskDispatchBatch poll(long timeoutMillis) throws InterruptedException {
            return poll(localTransportNodeId, timeoutMillis);
        }

        @Override
        public void shutdown() {
            running = false;
        }

        private List<String> polledNodes() {
            return List.copyOf(polledNodes);
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
                    .routeKeyResolver((dispatchBinding, routeContext) -> dispatchBinding.workerId())
                    .build());
        }
    }

    private static final class RecordingAdapter implements WorkerAdapter {
        private final String adapterId;
        private final CountDownLatch dispatchLatch;
        private final List<String> dispatchedMessageIds = Collections.synchronizedList(new ArrayList<>());

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
    }

    private static final class CombinedPresenceStore implements WorkerPresenceStore {
        private final List<InMemoryWorkerPresenceStore> stores;

        private CombinedPresenceStore(List<InMemoryWorkerPresenceStore> stores) {
            this.stores = stores;
        }

        @Override
        public WorkerPresence markOnline(String workerId, String adapterId, String routeKey, String connectionId, String reason) {
            return stores.getFirst().markOnline(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public WorkerPresence refreshHeartbeat(String workerId, String adapterId, String routeKey, String connectionId, String reason) {
            return stores.getFirst().refreshHeartbeat(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public WorkerPresence markOffline(String workerId, String adapterId, String routeKey, String connectionId, String reason) {
            return stores.getFirst().markOffline(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public WorkerPresence getPresence(String workerId) {
            return listActivePresences().stream()
                    .filter(presence -> workerId.equals(presence.getWorkerId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public boolean isRouteOnline(String adapterId, String routeKey) {
            return stores.stream().anyMatch(store -> store.isRouteOnline(adapterId, routeKey));
        }

        @Override
        public List<WorkerPresence> listActivePresences() {
            List<WorkerPresence> presences = new ArrayList<>();
            for (InMemoryWorkerPresenceStore store : stores) {
                presences.addAll(store.listActivePresences());
            }
            return List.copyOf(presences);
        }

        @Override
        public List<WorkerDispatchRouteOwner> findOwners(String workerId) {
            List<WorkerDispatchRouteOwner> owners = new ArrayList<>();
            for (InMemoryWorkerPresenceStore store : stores) {
                owners.addAll(store.findOwners(workerId));
            }
            return List.copyOf(owners);
        }

        @Override
        public int pruneExpired() {
            return stores.stream().mapToInt(InMemoryWorkerPresenceStore::pruneExpired).sum();
        }

        @Override
        public long getLeaseMillis() {
            return stores.stream().mapToLong(InMemoryWorkerPresenceStore::getLeaseMillis).min().orElse(30_000L);
        }
    }
}
