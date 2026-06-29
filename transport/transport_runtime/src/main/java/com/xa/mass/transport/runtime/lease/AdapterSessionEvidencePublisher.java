package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.runtime.embedded.AdapterSessionIdentity;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;

import java.util.Locale;

/**
 * Narrow adapter-facing capability for publishing worker session evidence.
 *
 * <p>Concrete adapters observe protocol sessions. They may publish those
 * observations through this capability, but they do not own the endpoint lease
 * store or worker-runtime dispatch eligibility.</p>
 */
public final class AdapterSessionEvidencePublisher {

    private final TransportEndpointLeasePublisher endpointLeasePublisher;
    private final CurrentSessionDisconnectSink disconnectSink;

    public AdapterSessionEvidencePublisher(String adapterId,
                                           TransportEndpointLeaseStore endpointLeaseStore,
                                           CurrentSessionDisconnectSink disconnectSink) {
        String normalizedAdapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(normalizedAdapterId);
        this.endpointLeasePublisher.setEndpointLeaseStore(endpointLeaseStore);
        this.disconnectSink = disconnectSink != null ? disconnectSink : CurrentSessionDisconnectSink.NOOP;
    }

    public static AdapterSessionEvidencePublisher noop(String adapterId) {
        return new AdapterSessionEvidencePublisher(
                adapterId,
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP
        );
    }

    public long leaseMillis() {
        return endpointLeasePublisher.getLeaseMillis();
    }

    public void connected(AdapterSessionIdentity identity,
                          String sessionToken,
                          String reason) {
        AdapterSessionIdentity requiredIdentity = requireIdentity(identity);
        endpointLeasePublisher.claim(
                requiredIdentity.workerId(),
                requiredIdentity.deliveryBucketId(),
                sessionToken,
                reason
        );
    }

    public boolean heartbeat(AdapterSessionIdentity identity,
                             String sessionToken,
                             String reason) {
        AdapterSessionIdentity requiredIdentity = requireIdentity(identity);
        return endpointLeasePublisher.refresh(
                requiredIdentity.workerId(),
                requiredIdentity.deliveryBucketId(),
                sessionToken,
                reason
        );
    }

    public boolean disconnected(AdapterSessionIdentity identity,
                                String sessionToken,
                                String reason) {
        AdapterSessionIdentity requiredIdentity = requireIdentity(identity);
        boolean releasedCurrent = endpointLeasePublisher.release(
                requiredIdentity.workerId(),
                requiredIdentity.deliveryBucketId(),
                sessionToken,
                reason
        );
        if (releasedCurrent) {
            disconnectSink.currentSessionDisconnected(
                    requiredIdentity.deliveryBucketId(),
                    requiredIdentity.workerId(),
                    firstNonBlank(reason, "transport session disconnected"),
                    System.currentTimeMillis()
            );
        }
        return releasedCurrent;
    }

    private static AdapterSessionIdentity requireIdentity(AdapterSessionIdentity identity) {
        if (identity == null) {
            throw new NullPointerException("identity");
        }
        return identity;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback;
    }
}
