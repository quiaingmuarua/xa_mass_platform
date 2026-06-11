package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryStore;
import com.xa.mass.transport.runtime.dispatch.RedisRouteTargetedTaskDispatchHandoff;
import com.xa.mass.transport.runtime.node.RedisTransportNodeRegistry;
import com.xa.mass.transport.runtime.route.RedisTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisTransportNamespacesTest {

    @Test
    void transportRedisDefaultsUseComponentVersionedNamespaces() {
        assertEquals("xa:mass:transport:delivery:v1", RedisTransportNamespaces.DELIVERY);
        assertEquals("xa:mass:transport:route-owner:v1", RedisTransportNamespaces.ROUTE_OWNER);
        assertEquals("xa:mass:transport:nodes:v1", RedisTransportNamespaces.NODES);
        assertEquals("xa:mass:transport:dispatch-route:v1", RedisTransportNamespaces.DISPATCH_ROUTE);
        assertEquals("xa:mass:transport:result-inbox:v1", RedisTransportNamespaces.RESULT_INBOX);
        assertEquals("xa:mass:transport:dispatch-failure:v1", RedisTransportNamespaces.DISPATCH_FAILURE);

        assertEquals(RedisTransportNamespaces.DELIVERY, RedisTransportDeliveryStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.ROUTE_OWNER, RedisTransportRouteOwnerStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.NODES, RedisTransportNodeRegistry.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DISPATCH_ROUTE, RedisRouteTargetedTaskDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.RESULT_INBOX, RedisTaskResultIngestChannel.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DISPATCH_FAILURE, RedisTransportDispatchFailureChannel.DEFAULT_NAMESPACE_PREFIX);
    }
}
