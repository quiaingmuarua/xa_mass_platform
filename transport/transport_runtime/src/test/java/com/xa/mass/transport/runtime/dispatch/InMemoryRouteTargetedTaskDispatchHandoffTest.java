package com.xa.mass.transport.runtime.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryRouteTargetedTaskDispatchHandoffTest {

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryRouteTargetedTaskDispatchHandoff(0));
    }

    @Test
    void submitAndPollRoundTrip() throws Exception {
        InMemoryRouteTargetedTaskDispatchHandoff handoff = new InMemoryRouteTargetedTaskDispatchHandoff(2);
        RouteTargetedTaskDispatchBatch batch = RouteTargetedDispatchFixtures.batch(
                "route-1",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("msg-1", "worker-1")
        );

        handoff.submit(batch);

        RouteTargetedTaskDispatchBatch polled = handoff.poll(100L);
        assertNotNull(polled);
        assertEquals("route-1", polled.routeKey());
        assertEquals(List.of("msg-1"), RouteTargetedDispatchFixtures.messages(polled));
    }
}
