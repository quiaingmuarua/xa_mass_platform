package com.xa.mass.transport.runtime;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CompositeWorkerEndpointRegistryTest {

    @Test
    void registerRejectsDifferentRegistryForSameAdapterId() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        WorkerEndpointRegistry first = mock(WorkerEndpointRegistry.class);
        WorkerEndpointRegistry second = mock(WorkerEndpointRegistry.class);

        registry.register("websocket", first);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("websocket", second)
        );

        assertEquals("Endpoint registry already registered for adapterId 'websocket'", error.getMessage());
    }

    @Test
    void registerAllowsReusingSameRegistryForSameAdapterId() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        WorkerEndpointRegistry existing = mock(WorkerEndpointRegistry.class);

        registry.register("websocket", existing);

        assertDoesNotThrow(() -> registry.register("websocket", existing));
    }

    @Test
    void adapterScopedOperationsRequireMatchingAdapter() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        TestRegistry websocket = new TestRegistry(
                List.of(new WorkerEndpointSnapshot("route-a", "worker-a", true, "endpoint-a", "websocket")),
                true,
                true
        );
        TestRegistry socket = new TestRegistry(
                List.of(new WorkerEndpointSnapshot("route-b", "worker-b", true, "endpoint-b", "socket")),
                false,
                false
        );

        registry.register("websocket", websocket);
        registry.register("socket", socket);

        assertFalse(registry.sendToAdapterRoute("unknown", "route-a", "{\"hello\":1}"));
        assertFalse(registry.isAdapterRouteOnline("unknown", "route-a"));
        assertFalse(websocket.sendInvoked);
        assertFalse(socket.sendInvoked);
    }

    @Test
    void adapterScopedRouteOperationsBypassCrossAdapterAmbiguity() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        TestRegistry websocket = new TestRegistry(
                List.of(new WorkerEndpointSnapshot("dup-route", "worker-a", true, "endpoint-a", "websocket")),
                true,
                true
        );
        TestRegistry socket = new TestRegistry(
                List.of(new WorkerEndpointSnapshot("dup-route", "worker-b", true, "endpoint-b", "socket")),
                false,
                false
        );

        registry.register("websocket", websocket);
        registry.register("socket", socket);

        assertTrue(registry.isAdapterRouteOnline("websocket", "dup-route"));
        assertTrue(registry.sendToAdapterRoute("websocket", "dup-route", "{\"hello\":1}"));
        assertTrue(websocket.sendInvoked);
        assertFalse(socket.sendInvoked);
    }

    @Test
    void selectedWorkerSendUsesAdapterScopedRegistryWithoutRouteFallback() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        TestRegistry websocket = new TestRegistry(
                List.of(new WorkerEndpointSnapshot("shared-route", "worker-a", true, "endpoint-a", "websocket")),
                true,
                true
        );
        TestRegistry socket = new TestRegistry(
                List.of(new WorkerEndpointSnapshot("shared-route", "worker-b", true, "endpoint-b", "socket")),
                true,
                true
        );

        registry.register("websocket", websocket);
        registry.register("socket", socket);

        assertTrue(registry.sendToSelectedWorker("websocket", "worker-a", "{\"hello\":1}"));
        assertTrue(websocket.selectedWorkerSendInvoked);
        assertEquals("worker-a", websocket.lastSelectedWorkerId);
        assertFalse(websocket.sendInvoked);
        assertFalse(socket.selectedWorkerSendInvoked);
    }

    private static final class TestRegistry
            implements WorkerEndpointRegistry, WorkerEndpointInspector, RawWorkerRouteEndpointRegistry {
        private final List<WorkerEndpointSnapshot> snapshots;
        private final boolean onlineResult;
        private final boolean sendResult;
        private boolean sendInvoked;
        private boolean selectedWorkerSendInvoked;
        private String lastSelectedWorkerId;

        private TestRegistry(List<WorkerEndpointSnapshot> snapshots,
                             boolean onlineResult,
                             boolean sendResult) {
            this.snapshots = snapshots;
            this.onlineResult = onlineResult;
            this.sendResult = sendResult;
        }

        @Override
        public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
            sendInvoked = true;
            return sendResult;
        }

        @Override
        public boolean sendToSelectedWorker(String adapterId, String selectedWorkerId, String message) {
            selectedWorkerSendInvoked = true;
            lastSelectedWorkerId = selectedWorkerId;
            return sendResult;
        }

        @Override
        public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
            return onlineResult;
        }

        @Override
        public int getActiveConnectionCount() {
            return 0;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
            return snapshots;
        }
    }
}
