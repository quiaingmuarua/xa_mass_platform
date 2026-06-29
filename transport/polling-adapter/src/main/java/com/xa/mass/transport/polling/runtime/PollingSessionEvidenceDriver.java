package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.runtime.embedded.AdapterSessionIdentity;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;

import java.util.Objects;

/**
 * Polling pull-session evidence driver.
 *
 * <p>The SDK session owns the user-facing session action; this driver owns the
 * transport evidence projection for that action.</p>
 */
public final class PollingSessionEvidenceDriver implements PullSessionEvidenceDriver {

    private final AdapterSessionEvidencePublisher sessionEvidencePublisher;

    public PollingSessionEvidenceDriver(AdapterSessionEvidencePublisher sessionEvidencePublisher) {
        this.sessionEvidencePublisher = Objects.requireNonNull(sessionEvidencePublisher, "sessionEvidencePublisher");
    }

    @Override
    public boolean connect(String workerId,
                           String deliveryBucketId,
                           String sessionToken,
                           String reason) {
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedSessionToken = requireText(sessionToken, "sessionToken");
        String normalizedReason = requireText(reason, "reason");
        sessionEvidencePublisher.connected(
                new AdapterSessionIdentity(deliveryBucketId, normalizedWorkerId),
                normalizedSessionToken,
                normalizedReason
        );
        return true;
    }

    @Override
    public boolean heartbeat(String workerId,
                             String deliveryBucketId,
                             String sessionToken,
                             String reason) {
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedSessionToken = requireText(sessionToken, "sessionToken");
        String normalizedReason = requireText(reason, "reason");
        return sessionEvidencePublisher.heartbeat(
                new AdapterSessionIdentity(deliveryBucketId, normalizedWorkerId),
                normalizedSessionToken,
                normalizedReason
        );
    }

    @Override
    public boolean disconnect(String workerId,
                              String deliveryBucketId,
                              String sessionToken,
                              String reason) {
        String normalizedWorkerId = requireText(workerId, "workerId");
        String normalizedSessionToken = requireText(sessionToken, "sessionToken");
        String normalizedReason = requireText(reason, "reason");
        return sessionEvidencePublisher.disconnected(
                new AdapterSessionIdentity(deliveryBucketId, normalizedWorkerId),
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
