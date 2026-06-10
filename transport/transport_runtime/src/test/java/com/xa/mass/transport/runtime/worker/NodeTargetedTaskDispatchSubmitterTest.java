package com.xa.mass.transport.runtime.worker;

import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.NodeTargetedTaskDispatchHandoff;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.storage.memory.InMemoryWorkerDeclarationStore;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.CanonicalWorkerRouteKeyCodec;
import com.xa.mass.transport.runtime.node.InMemoryTransportNodeRegistry;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeTargetedTaskDispatchSubmitterTest {

    @Test
    void splitsBatchBySelectedTransportNodeAndCompensatesMissingOwner() {
        InMemoryWorkerPresenceStore presenceNodeOne = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryWorkerPresenceStore presenceNodeTwo = new InMemoryWorkerPresenceStore(30_000L, "node-2");
        presenceNodeOne.markOnline("worker-1", "websocket", routeKey("worker-1"), "conn-1", "connected");
        presenceNodeTwo.markOnline("worker-2", "websocket", routeKey("worker-2"), "conn-2", "connected");
        CombinedRouteView routeView = new CombinedRouteView(List.of(presenceNodeOne, presenceNodeTwo));
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("websocket"), 1L);
        nodes.register("node-2", List.of("websocket"), 1L);
        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                routeView,
                nodes
        );
        CapturingNodeTargetedHandoff handoff = new CapturingNodeTargetedHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        NodeTargetedTaskDispatchSubmitter submitter = new NodeTargetedTaskDispatchSubmitter(
                handoff,
                workerResourceRuntime(
                        worker("worker-1"),
                        worker("worker-2"),
                        worker("worker-missing-route")),
                selector,
                (task, dispatchBindings, detail) -> {
                    compensated.addAll(dispatchBindings);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(context(), List.of(
                binding("msg-1", "worker-1"),
                binding("msg-2", "worker-2"),
                binding("msg-3", "worker-missing-route")
        ));

        assertEquals(List.of("msg-1"), messages(handoff.submittedByNode.get("node-1")));
        assertEquals(List.of("msg-2"), messages(handoff.submittedByNode.get("node-2")));
        assertEquals(List.of("msg-3"), compensated.stream().map(TaskDispatchBinding::messageId).toList());
    }

    @Test
    void compensatesWhenRouteOwnerNodeIsOffline() {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        presence.markOnline("worker-1", "websocket", routeKey("worker-1"), "conn-1", "connected");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("websocket"), 1L);
        nodes.markOffline("node-1");
        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes
        );
        CapturingNodeTargetedHandoff handoff = new CapturingNodeTargetedHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        List<String> details = new ArrayList<>();
        NodeTargetedTaskDispatchSubmitter submitter = new NodeTargetedTaskDispatchSubmitter(
                handoff,
                workerResourceRuntime(worker("worker-1")),
                selector,
                (task, dispatchBindings, detail) -> {
                    compensated.addAll(dispatchBindings);
                    details.add(detail);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(List.of(), new ArrayList<>(handoff.submittedByNode.keySet()));
        assertEquals(List.of("msg-1"), compensated.stream().map(TaskDispatchBinding::messageId).toList());
        assertEquals(List.of("transport route owner is unavailable after assignment"), details);
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

    private static Worker worker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("group-1");
        worker.setAdapterId("websocket");
        worker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        return worker;
    }

    private static WorkerManager workerResourceRuntime(Worker... workers) {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());
        if (workers != null) {
            for (Worker worker : workers) {
                workerManager.addWorker(workerResource(worker));
            }
        }
        return workerManager;
    }

    private static WorkerResourceRecord workerResource(Worker worker) {
        return new WorkerResourceRecord(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        );
    }

    private static List<String> messages(TaskDispatchBatch batch) {
        return batch == null
                ? List.of()
                : batch.dispatchBindings().stream().map(TaskDispatchBinding::messageId).toList();
    }

    private static final class CombinedRouteView implements com.xa.mass.transport.presence.WorkerDispatchRouteOwnerView {
        private final List<InMemoryWorkerPresenceStore> stores;

        private CombinedRouteView(List<InMemoryWorkerPresenceStore> stores) {
            this.stores = stores;
        }

        @Override
        public Optional<com.xa.mass.transport.presence.WorkerDispatchRouteOwner> currentOwner(String routeKey) {
            return stores.stream()
                    .map(store -> store.currentOwner(routeKey))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        }

        @Override
        public List<com.xa.mass.transport.presence.WorkerDispatchRouteOwner> findOwners(String workerId) {
            return stores.stream()
                    .flatMap(store -> store.findOwners(workerId).stream())
                    .toList();
        }
    }

    private static String routeKey(String workerId) {
        return CanonicalWorkerRouteKeyCodec.encode("group-1", workerId);
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
}
