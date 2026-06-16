package com.xa.mass.transport.lease;

/**
 * Optional bucket-scoped maintenance surface for endpoint lease stores.
 */
public interface TransportEndpointLeaseMaintenance {

    int pruneExpired(String deliveryBucketId, int maxItems);
}
