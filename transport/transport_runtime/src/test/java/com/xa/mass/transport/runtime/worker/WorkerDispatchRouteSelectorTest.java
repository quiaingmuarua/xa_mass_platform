package com.xa.mass.transport.runtime.worker;

import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.node.InMemoryTransportNodeRegistry;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerDispatchRouteSelectorTest {

    @Test
    void selectsCurrentOwnerForCanonicalWorkerSubject() {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("websocket", "socket"), 2L);
        String routeKey = routeKey("group-1", "worker-1");
        presence.markOnline("worker-1", "websocket", routeKey, "conn-ws", "connected");
        presence.markOnline("worker-1", "socket", routeKey, "conn-socket", "reconnected");
        WorkerResourceRecord worker = worker("worker-1", "websocket", WorkerTransportHints.REALTIME);

        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes,
                WorkerDispatchRouteSelectorTest::routeKey
        );

        assertEquals("socket", selector.selectRoute(worker).orElseThrow().adapterId());
        assertEquals(routeKey, selector.selectRoute(worker).orElseThrow().routeKey());
    }

    @Test
    void requiresWorkerGroupEvidenceToResolveRouteKey() {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("polling"), 1L);
        presence.markOnline("worker-1", "polling", routeKey("group-1", "worker-1"), "conn-poll", "connected");
        WorkerResourceRecord worker = workerWithoutGroup("worker-1", "polling", WorkerTransportHints.POLLING);

        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes,
                WorkerDispatchRouteSelectorTest::routeKey
        );

        assertTrue(selector.selectRoute(worker).isEmpty());
    }

    @Test
    void excludesOfflineNodesAndMissingRoutes() {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("websocket"), 1L);
        presence.markOnline("worker-1", "websocket", routeKey("group-1", "worker-1"), "conn-ws", "connected");
        nodes.markOffline("node-1");
        WorkerResourceRecord worker = worker("worker-1", "websocket", WorkerTransportHints.REALTIME);

        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes,
                WorkerDispatchRouteSelectorTest::routeKey
        );

        assertTrue(selector.selectRoute(worker).isEmpty());
        assertTrue(selector.selectRoute(worker("missing-worker", "websocket", WorkerTransportHints.REALTIME)).isEmpty());
    }

    private static WorkerResourceRecord worker(String workerId, String adapterId, String transportHint) {
        return new WorkerResourceRecord(
                workerId,
                null,
                null,
                null,
                List.of(),
                List.of(),
                "group-1",
                null,
                adapterId,
                transportHint,
                1,
                Map.of(),
                null,
                null
        );
    }

    private static WorkerResourceRecord workerWithoutGroup(String workerId, String adapterId, String transportHint) {
        return new WorkerResourceRecord(
                workerId,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                adapterId,
                transportHint,
                1,
                Map.of(),
                null,
                null
        );
    }

    private static String routeKey(String workerGroupId, String workerId) {
        return "route:" + workerId;
    }

    private static java.util.Optional<String> routeKey(WorkerResourceRecord worker) {
        if (worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(routeKey(worker.workerGroupId(), worker.workerId()));
    }
}
