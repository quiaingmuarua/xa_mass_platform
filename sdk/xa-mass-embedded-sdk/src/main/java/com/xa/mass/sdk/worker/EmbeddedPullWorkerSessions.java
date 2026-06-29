package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.starter.PullSessionEvidencePort;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;

/**
 * Internal starter assembly entry for pull worker sessions.
 *
 * <p>Public worker/session callers should use MassApplication or MassSdk
 * open-pull-worker methods so transport endpoint lease and consumer registry
 * details stay out of the SDK-facing constructor surface.</p>
 */
public final class EmbeddedPullWorkerSessions {

    private EmbeddedPullWorkerSessions() {
    }

    public static EmbeddedPullWorkerSession open(String workerId,
                                         String workerGroupId,
                                         String sessionToken,
                                         DeliveryPullChannel deliveryPullChannel,
                                         TransportResultIngressChannel resultIngressChannel,
                                         PullSessionEvidencePort evidencePort,
                                         WorkerHeartbeatRuntime workerHeartbeatRuntime,
                                         String transportHint) {
        return new EmbeddedPullWorkerSession(
                workerId,
                workerGroupId,
                sessionToken,
                deliveryPullChannel,
                resultIngressChannel,
                evidencePort,
                workerHeartbeatRuntime,
                transportHint
        );
    }
}
