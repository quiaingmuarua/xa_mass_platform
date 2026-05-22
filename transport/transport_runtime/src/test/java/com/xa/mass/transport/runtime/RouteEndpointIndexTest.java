package com.xa.mass.transport.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteEndpointIndexTest {

    @Test
    void replacingRouteWithNewHandleDropsOldReverseBinding() {
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
        assertEquals("handle-b", index.entryForRoute("route-1").handle());
        assertNull(index.bindingForHandle("handle-a"));
        assertEquals("route-1", index.bindingForHandle("handle-b").routeKey());
    }

    @Test
    void removeByHandleOnlyClearsCurrentRouteOwner() {
        RouteEndpointIndex<String, String> index = new RouteEndpointIndex<>();
        index.bind("route-1", "worker-1", "handle-a", "endpoint-a", endpoint -> true);
        index.bind("route-1", "worker-1", "handle-b", "endpoint-b", endpoint -> true);

        RouteEndpointIndex.RemoveResult<String, String> removedOld = index.removeByHandle("handle-a");
        RouteEndpointIndex.RemoveResult<String, String> removedCurrent = index.removeByHandle("handle-b");

        assertNull(removedOld.binding());
        assertTrue(removedCurrent.removedCurrentRoute());
        assertNull(index.endpointForRoute("route-1"));
    }
}
