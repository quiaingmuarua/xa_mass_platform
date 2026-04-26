package com.xa.mass.starter.transport;

import com.xa.mass.transport.WorkerEndpointRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
