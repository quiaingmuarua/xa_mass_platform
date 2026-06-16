package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.node.RedisTransportNodeRegistry;
import com.xa.mass.transport.runtime.lease.RedisTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisTransportNamespacesTest {

    @Test
    void transportRedisDefaultsUseComponentVersionedNamespaces() {
        assertEquals("xa:mass:transport:delivery:v1", RedisTransportNamespaces.DELIVERY);
        assertEquals("xa:mass:transport:delivery-command:v1", RedisTransportNamespaces.DELIVERY_COMMAND);
        assertEquals("xa:mass:transport:delivery-failure:v1", RedisTransportNamespaces.DELIVERY_FAILURE);
        assertEquals("xa:mass:transport:endpoint-lease:v1", RedisTransportNamespaces.ENDPOINT_LEASE);
        assertEquals("xa:mass:transport:nodes:v1", RedisTransportNamespaces.NODES);
        assertEquals("xa:mass:transport:result-inbox:v1", RedisTransportNamespaces.RESULT_INBOX);

        assertEquals(RedisTransportNamespaces.DELIVERY, RedisTransportDeliveryStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DELIVERY_COMMAND, RedisTransportDeliveryCommandHandoff.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DELIVERY_FAILURE, RedisTransportDeliveryFailureChannel.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.ENDPOINT_LEASE, RedisTransportEndpointLeaseStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.NODES, RedisTransportNodeRegistry.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.RESULT_INBOX, RedisTransportResultIngressChannel.DEFAULT_NAMESPACE_PREFIX);
    }
}
