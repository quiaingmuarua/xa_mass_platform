package com.xa.mass.transport.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteEndpointIndexTest {

    @Test
    void sameRouteKeepsMultipleHandles() {
        RouteEndpointIndex<String, String> index = new RouteEndpointIndex<>();

        RouteEndpointIndex.BindResult<String, String> first = index.bind(
                "route-1",
                "worker-1",
                "handle-a",
                "endpoint-a",
                endpoint -> true
        );
        RouteEndpointIndex.BindResult<String, String> second = index.bind(
                "route-1",
                "worker-1",
                "handle-b",
                "endpoint-b",
                endpoint -> true
        );

        assertFalse(first.unchanged());
        assertFalse(second.unchanged());
        assertEquals(2, index.entriesForRoute("route-1").size());
        assertEquals("route-1", index.bindingForHandle("handle-a").routeKey());
        assertEquals("route-1", index.bindingForHandle("handle-b").routeKey());
    }

    @Test
    void removeByHandleOnlyClearsMatchingConsumer() {
        RouteEndpointIndex<String, String> index = new RouteEndpointIndex<>();
        index.bind("route-1", "worker-1", "handle-a", "endpoint-a", endpoint -> true);
        index.bind("route-1", "worker-1", "handle-b", "endpoint-b", endpoint -> true);

        RouteEndpointIndex.RemoveResult<String, String> removedFirst = index.removeByHandle("handle-a");

        assertTrue(removedFirst.removedCurrentRoute());
        assertEquals(1, index.entriesForRoute("route-1").size());

        RouteEndpointIndex.RemoveResult<String, String> removedSecond = index.removeByHandle("handle-b");

        assertTrue(removedSecond.removedCurrentRoute());
        assertTrue(index.entriesForRoute("route-1").isEmpty());
    }

    @Test
    void workerIndexSelectsOnlyMatchingWorkerUnderSharedRoute() {
        RouteEndpointIndex<String, String> index = new RouteEndpointIndex<>();
        index.bind("shared-route", "worker-1", "handle-a", "endpoint-a", endpoint -> true);
        index.bind("shared-route", "worker-2", "handle-b", "endpoint-b", endpoint -> true);

        assertEquals("endpoint-b", index.entryForWorker("worker-2").endpoint());
        assertEquals(1, index.entriesForWorker("worker-2").size());

        index.removeByHandle("handle-b");

        assertTrue(index.entriesForWorker("worker-2").isEmpty());
        assertEquals("endpoint-a", index.entryForWorker("worker-1").endpoint());
    }

    @Test
    void handleLookupReturnsCurrentEntry() {
        RouteEndpointIndex<String, String> index = new RouteEndpointIndex<>();
        index.bind("route-1", "worker-1", "handle-a", "endpoint-a", endpoint -> true);

        RouteEndpointIndex.Entry<String, String> entry = index.entryForHandle("handle-a");

        assertEquals("route-1", entry.routeKey());
        assertEquals("worker-1", entry.workerId());
        assertEquals("endpoint-a", entry.endpoint());

        index.removeByHandle("handle-a");

        assertNull(index.entryForHandle("handle-a"));
    }
}
