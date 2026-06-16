package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;

/**
 * Internal starter assembly entry for pull worker sessions.
 *
 * <p>Public worker/session callers should use MassApplication or MassSdk
 * open-pull-worker methods so transport endpoint lease and consumer registry
 * details stay out of the SDK-facing constructor surface.</p>
 */
public final class InternalPullWorkerSessions {

    private InternalPullWorkerSessions() {
    }

    public static PullWorkerSession open(String workerId,
                                         String workerGroupId,
                                         String adapterId,
                                         String sessionToken,
                                         DeliveryPullChannel deliveryPullChannel,
                                         TransportResultIngressChannel resultIngressChannel,
                                         TransportEndpointLeaseStore endpointLeaseStore,
                                         DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                         WorkerPresenceIngress workerPresenceIngress,
                                         String transportHint) {
        return new PullWorkerSession(
                workerId,
                workerGroupId,
                adapterId,
                sessionToken,
                deliveryPullChannel,
                resultIngressChannel,
                endpointLeaseStore,
                deliveryCommandConsumerRegistry,
                workerPresenceIngress,
                transportHint
        );
    }
}
