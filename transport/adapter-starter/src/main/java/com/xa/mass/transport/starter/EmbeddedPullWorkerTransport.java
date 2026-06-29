package com.xa.mass.transport.starter;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.TransportResultIngressChannel;

import java.util.Objects;

/**
 * Stable embedded pull-worker transport resolved by adapter-starter.
 */
public final class EmbeddedPullWorkerTransport {

    private final String workerId;
    private final String workerGroupId;
    private final String adapterId;
    private final String transportHint;
    private final DeliveryPullChannel deliveryPullChannel;
    private final TransportResultIngressChannel resultIngressChannel;
    private final PullSessionEvidencePort pullSessionEvidencePort;

    public EmbeddedPullWorkerTransport(String workerId,
                                       String workerGroupId,
                                       String adapterId,
                                       String transportHint,
                                       DeliveryPullChannel deliveryPullChannel,
                                       TransportResultIngressChannel resultIngressChannel,
                                       PullSessionEvidencePort pullSessionEvidencePort) {
        this.workerId = requireText(workerId, "workerId");
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.adapterId = requireText(adapterId, "adapterId");
        this.transportHint = requireText(transportHint, "transportHint");
        this.deliveryPullChannel = Objects.requireNonNull(deliveryPullChannel, "deliveryPullChannel");
        this.resultIngressChannel = Objects.requireNonNull(resultIngressChannel, "resultIngressChannel");
        this.pullSessionEvidencePort = Objects.requireNonNull(pullSessionEvidencePort, "pullSessionEvidencePort");
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

    public PullSessionEvidencePort getPullSessionEvidencePort() {
        return pullSessionEvidencePort;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
