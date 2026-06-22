package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.RedisTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.lease.RedisTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisTransportNamespacesTest {

    @Test
    void transportRedisDefaultsUseComponentVersionedNamespaces() {
        assertEquals("xa:mass:transport:delivery:v1", RedisTransportNamespaces.DELIVERY);
        assertEquals("xa:mass:transport:dispatch:v1", RedisTransportNamespaces.DISPATCH);
        assertEquals("xa:mass:transport:delivery-failure:v1", RedisTransportNamespaces.DELIVERY_FAILURE);
        assertEquals("xa:mass:transport:endpoint-lease:v1", RedisTransportNamespaces.ENDPOINT_LEASE);
        assertEquals("xa:mass:transport:result-inbox:v1", RedisTransportNamespaces.RESULT_INBOX);

        assertEquals(RedisTransportNamespaces.DELIVERY, RedisTransportDeliveryStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DISPATCH, RedisTransportDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.DELIVERY_FAILURE, RedisTransportDeliveryFailureChannel.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.ENDPOINT_LEASE, RedisTransportEndpointLeaseStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.RESULT_INBOX, RedisTransportResultIngressChannel.DEFAULT_NAMESPACE_PREFIX);
    }
}
