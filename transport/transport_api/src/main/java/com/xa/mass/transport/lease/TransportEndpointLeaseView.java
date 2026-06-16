package com.xa.mass.transport.lease;

import java.util.Optional;

/**
 * Diagnostic read surface for current endpoint lease metadata.
 */
public interface TransportEndpointLeaseView {

    Optional<TransportEndpointLeaseViewRecord> currentEndpointLease(String deliveryBucketId, String workerId);
}
