package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.delivery.RedisTransportDispatchHandoff;
import com.xa.mass.transport.runtime.lease.RedisTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisTransportNamespacesTest {

    @Test
    void transportRedisDefaultsUseComponentVersionedNamespaces() {
        assertEquals("xa:mass:transport:dispatch:v1", RedisTransportNamespaces.DISPATCH);
        assertEquals("xa:mass:transport:endpoint-lease:v1", RedisTransportNamespaces.ENDPOINT_LEASE);
        assertEquals("xa:mass:transport:result-ingress:v1", RedisTransportNamespaces.RESULT_INGRESS);

        assertEquals(RedisTransportNamespaces.DISPATCH, RedisTransportDispatchHandoff.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.ENDPOINT_LEASE, RedisTransportEndpointLeaseStore.DEFAULT_NAMESPACE_PREFIX);
        assertEquals(RedisTransportNamespaces.RESULT_INGRESS, RedisTransportResultIngressChannel.DEFAULT_NAMESPACE_PREFIX);
    }
}
