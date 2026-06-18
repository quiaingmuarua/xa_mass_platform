package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;

import java.util.Objects;

/**
 * Runtime-owned pull transport binding resolved for one worker.
 */
public final class ResolvedPullWorkerTransport {

    private final String workerId;
    private final String workerGroupId;
    private final String adapterId;
    private final String transportHint;
    private final DeliveryPullChannel deliveryPullChannel;
    private final TransportResultIngressChannel resultIngressChannel;
    private final PullSessionEvidenceDriver pullSessionEvidenceDriver;

    public ResolvedPullWorkerTransport(String workerId,
                                       String workerGroupId,
                                       String adapterId,
                                       String transportHint,
                                       DeliveryPullChannel deliveryPullChannel,
                                       TransportResultIngressChannel resultIngressChannel,
                                       PullSessionEvidenceDriver pullSessionEvidenceDriver) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.workerGroupId = Objects.requireNonNull(workerGroupId, "workerGroupId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.transportHint = Objects.requireNonNull(transportHint, "transportHint");
        this.deliveryPullChannel = Objects.requireNonNull(deliveryPullChannel, "deliveryPullChannel");
        this.resultIngressChannel = Objects.requireNonNull(resultIngressChannel, "resultIngressChannel");
        this.pullSessionEvidenceDriver = Objects.requireNonNull(pullSessionEvidenceDriver,
                "pullSessionEvidenceDriver");
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public DeliveryPullChannel getDeliveryPullChannel() {
        return deliveryPullChannel;
    }

    public TransportResultIngressChannel getResultIngressChannel() {
        return resultIngressChannel;
    }

    public PullSessionEvidenceDriver getPullSessionEvidenceDriver() {
        return pullSessionEvidenceDriver;
    }

}
