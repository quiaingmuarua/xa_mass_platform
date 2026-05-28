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
    void prefersWorkerRegisteredAdapterWhenAvailable() throws Exception {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("websocket", "socket"), 2L);
        presence.markOnline("worker-1", "websocket", "route-ws", "conn-ws", "connected");
        Thread.sleep(2L);
        presence.markOnline("worker-1", "socket", "route-socket", "conn-socket", "connected");
        WorkerResourceRecord worker = worker("worker-1", "websocket", WorkerTransportHints.REALTIME);

        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes,
                Map.of("websocket", WorkerTransportHints.REALTIME, "socket", WorkerTransportHints.REALTIME)
        );

        assertEquals("websocket", selector.selectRoute(worker).orElseThrow().adapterId());
    }

    @Test
    void fallsBackToTransportHintFamilyThenNewestRoute() throws Exception {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("polling", "websocket"), 2L);
        presence.markOnline("worker-1", "polling", "route-poll", "conn-poll", "connected");
        Thread.sleep(2L);
        presence.markOnline("worker-1", "websocket", "route-ws", "conn-ws", "connected");
        WorkerResourceRecord worker = worker("worker-1", "missing-adapter", WorkerTransportHints.POLLING);

        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes,
                Map.of("polling", WorkerTransportHints.POLLING, "websocket", WorkerTransportHints.REALTIME)
        );

        assertEquals("polling", selector.selectRoute(worker).orElseThrow().adapterId());

        assertEquals("websocket", selector.selectRoute(
                worker("worker-1", "missing-adapter", null)).orElseThrow().adapterId());
    }

    @Test
    void excludesOfflineNodesAndMissingRoutes() {
        InMemoryWorkerPresenceStore presence = new InMemoryWorkerPresenceStore(30_000L, "node-1");
        InMemoryTransportNodeRegistry nodes = new InMemoryTransportNodeRegistry();
        nodes.register("node-1", List.of("websocket"), 1L);
        presence.markOnline("worker-1", "websocket", "route-ws", "conn-ws", "connected");
        nodes.markOffline("node-1");
        WorkerResourceRecord worker = worker("worker-1", "websocket", WorkerTransportHints.REALTIME);

        WorkerDispatchRouteSelector selector = new WorkerDispatchRouteSelector(
                presence,
                nodes,
                Map.of("websocket", WorkerTransportHints.REALTIME)
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
}
