package com.xa.mass.transport.lease;

import java.util.Optional;

/**
 * Transport-owned endpoint lease write surface.
 *
 * <p>This store owns only current connection/session evidence for already
 * selected workers. It is not worker lifecycle truth, scheduling truth, or a
 * post-assignment routing engine.</p>
 */
public interface TransportEndpointLeaseStore extends TransportEndpointLeaseView {

    TransportEndpointLeaseConsumerEvidence claimEndpointLease(TransportEndpointLeaseClaim claim);

    Optional<TransportEndpointLeaseConsumerEvidence> refreshEndpointLease(TransportEndpointLeaseHeartbeat heartbeat);

    boolean releaseEndpointLease(TransportEndpointLeaseRelease release);

    default long getLeaseMillis() {
        return 30_000L;
    }
}
