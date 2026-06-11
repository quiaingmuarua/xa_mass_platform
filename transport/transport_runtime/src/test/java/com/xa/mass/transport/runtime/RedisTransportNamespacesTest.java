package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryStore;
import com.xa.mass.transport.runtime.dispatch.RedisNodeTargetedTaskDispatchHandoff;
import com.xa.mass.transport.runtime.dispatch.RedisTaskDispatchHandoff;
import com.xa.mass.transport.runtime.node.RedisTransportNodeRegistry;
import com.xa.mass.transport.runtime.presence.RedisWorkerPresenceStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisTransportNamespacesTest {

    @Test
    void transportRedisDefaultsUseComponentVersionedNamespaces() {
        assertEquals("xa:mass:transport:delivery:v1", RedisTransportNamespaces.DELIVERY);
        assertEquals("xa:mass:transport:presence:v2", RedisTransportNamespaces.PRESENCE);
        assertEquals("xa:mass:transport:nodes:v1", RedisTransportNamespaces.NODES);
        assertEquals("xa:mass:transport:dispatch-node:v1", RedisTransportNamespaces.DISPATCH_NODE);
        assertEquals("xa:mass:transport:dispatch-handoff:v1", RedisTransportNamespaces.DISPATCH_HANDOFF);
        assertEquals("xa:mass:transport:result-inbox:v1", RedisTransportNamespaces.RESULT_INBOX);
        assertEquals("xa:mass:transport:dispatch-failure:v1", RedisTransportNamespaces.DISPATCH_FAILURE);

        assertEquals(RedisTransportNamespaces.DELIVERY, RedisTransportDeliveryStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.PRESENCE, RedisWorkerPresenceStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.NODES, RedisTransportNodeRegistry.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DISPATCH_NODE, RedisNodeTargetedTaskDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DISPATCH_HANDOFF, RedisTaskDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.RESULT_INBOX, RedisTaskResultIngestChannel.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DISPATCH_FAILURE, RedisTransportDispatchFailureChannel.DEFAULT_NAMESPACE_PREFIX);
    }
}
