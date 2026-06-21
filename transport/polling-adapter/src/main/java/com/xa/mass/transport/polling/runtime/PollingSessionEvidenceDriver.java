package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;
import com.xa.mass.transport.runtime.lease.TransportEndpointLeasePublisher;
import com.xa.mass.transport.runtime.lease.WorkerPresenceSessionPublisher;

import java.util.Locale;
import java.util.Objects;

/**
 * Polling pull-session evidence driver.
 *
 * <p>The SDK session owns the user-facing session action; this driver owns the
 * transport evidence projection for that action.</p>
 */
public final class PollingSessionEvidenceDriver implements PullSessionEvidenceDriver {

    private final TransportEndpointLeasePublisher endpointLeasePublisher;
    private final WorkerPresenceSessionPublisher workerPresencePublisher;

    public PollingSessionEvidenceDriver(String adapterId,
                                        String adapterMailboxKey,
                                        TransportEndpointLeaseStore endpointLeaseStore,
                                        WorkerPresenceIngress workerPresenceIngress) {
        String normalizedAdapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(normalizedAdapterId);
        this.endpointLeasePublisher.setEndpointLeaseStore(Objects.requireNonNull(endpointLeaseStore,
                "endpointLeaseStore"));
        this.workerPresencePublisher = new WorkerPresenceSessionPublisher(
                normalizedAdapterId,
                requireText(adapterMailboxKey, "adapterMailboxKey"));
        this.workerPresencePublisher.setWorkerPresenceIngress(workerPresenceIngress);
    }

    @Override
    public boolean connect(String workerId,
                           String deliveryBucketId,
                           String endpointAddress,
                           String sessionToken,
                           String reason) {
        String normalizedEndpointAddress = requireText(endpointAddress, "endpointAddress");
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedSessionToken = requireText(sessionToken, "sessionToken");
        String normalizedReason = requireText(reason, "reason");
        workerPresencePublisher.sessionConnected(
                normalizedWorkerId,
                normalizedEndpointAddress,
                normalizedSessionToken,
                normalizedReason,
                normalizedSessionToken
        );
        endpointLeasePublisher.claim(normalizedWorkerId, deliveryBucketId, normalizedEndpointAddress,
                normalizedSessionToken, normalizedReason);
        return true;
    }

    @Override
    public boolean heartbeat(String workerId,
                             String deliveryBucketId,
                             String endpointAddress,
                             String sessionToken,
                             String reason) {
        String normalizedEndpointAddress = requireText(endpointAddress, "endpointAddress");
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedSessionToken = requireText(sessionToken, "sessionToken");
        String normalizedReason = requireText(reason, "reason");
        workerPresencePublisher.sessionHeartbeat(
                normalizedWorkerId,
                normalizedEndpointAddress,
                normalizedSessionToken,
                normalizedReason,
                normalizedSessionToken
        );
        return endpointLeasePublisher.refresh(
                normalizedWorkerId,
                deliveryBucketId,
                normalizedEndpointAddress,
                normalizedSessionToken,
                normalizedReason
        );
    }

    @Override
    public boolean disconnect(String workerId,
                              String deliveryBucketId,
                              String endpointAddress,
                              String sessionToken,
                              String reason) {
        String normalizedEndpointAddress = requireText(endpointAddress, "endpointAddress");
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedSessionToken = requireText(sessionToken, "sessionToken");
        String normalizedReason = requireText(reason, "reason");
        workerPresencePublisher.sessionDisconnected(
                normalizedWorkerId,
                normalizedEndpointAddress,
                normalizedSessionToken,
                normalizedReason,
                normalizedSessionToken
        );
        return endpointLeasePublisher.release(
                normalizedWorkerId,
                deliveryBucketId,
                normalizedEndpointAddress,
                normalizedSessionToken,
                normalizedReason
        );
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
