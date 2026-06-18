package com.xa.mass.transport.runtime;

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
    void selectedWorkerSendReturnsFalseWhenNoRegistryCanDeliver() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        TestRegistry websocket = new TestRegistry(
                false
        );
        TestRegistry socket = new TestRegistry(
                false
        );

        registry.register("websocket", websocket);
        registry.register("socket", socket);

        assertFalse(registry.sendToSelectedWorker("worker-a", "{\"hello\":1}"));
        assertTrue(websocket.selectedWorkerSendInvoked);
        assertTrue(socket.selectedWorkerSendInvoked);
    }

    @Test
    void selectedWorkerSendStopsAtFirstRegistryThatCanDeliver() {
        CompositeWorkerEndpointRegistry registry = new CompositeWorkerEndpointRegistry();
        TestRegistry websocket = new TestRegistry(
                true
        );
        TestRegistry socket = new TestRegistry(
                true
        );

        registry.register("websocket", websocket);
        registry.register("socket", socket);

        assertTrue(registry.sendToSelectedWorker("worker-a", "{\"hello\":1}"));
        assertTrue(websocket.selectedWorkerSendInvoked);
        assertEquals("worker-a", websocket.lastSelectedWorkerId);
        assertFalse(socket.selectedWorkerSendInvoked);
    }

    @Test
    void diagnosticsAreAggregatedByDedicatedInspectorComposite() {
        CompositeWorkerEndpointInspector inspector = new CompositeWorkerEndpointInspector();
        inspector.register(() -> List.of(new WorkerEndpointSnapshot(
                "route-a",
                "worker-a",
                true,
                "endpoint-a",
                "websocket"
        )));
        inspector.register(() -> List.of(new WorkerEndpointSnapshot(
                "route-b",
                "worker-b",
                true,
                "endpoint-b",
                "socket"
        )));

        assertEquals(2, inspector.listWorkerEndpoints().size());
    }

    private static final class TestRegistry implements WorkerEndpointRegistry {
        private final boolean sendResult;
        private boolean selectedWorkerSendInvoked;
        private String lastSelectedWorkerId;

        private TestRegistry(boolean sendResult) {
            this.sendResult = sendResult;
        }

        @Override
        public boolean sendToSelectedWorker(String selectedWorkerId, String message) {
            selectedWorkerSendInvoked = true;
            lastSelectedWorkerId = selectedWorkerId;
            return sendResult;
        }

        @Override
        public int getActiveConnectionCount() {
            return 0;
        }

        @Override
        public void shutdown() {
        }
    }
}
