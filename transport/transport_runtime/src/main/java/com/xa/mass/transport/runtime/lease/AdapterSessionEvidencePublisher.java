package com.xa.mass.transport.runtime.lease;

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
                                           String adapterMailboxKey,
                                           TransportEndpointLeaseStore endpointLeaseStore,
                                           CurrentSessionDisconnectSink disconnectSink) {
        String normalizedAdapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        requireText(adapterMailboxKey, "adapterMailboxKey");
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(normalizedAdapterId);
        this.endpointLeasePublisher.setEndpointLeaseStore(endpointLeaseStore);
        this.disconnectSink = disconnectSink != null ? disconnectSink : CurrentSessionDisconnectSink.NOOP;
    }

    public static AdapterSessionEvidencePublisher noop(String adapterId, String adapterMailboxKey) {
        return new AdapterSessionEvidencePublisher(
                adapterId,
                adapterMailboxKey,
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP
        );
    }

    public long leaseMillis() {
        return endpointLeasePublisher.getLeaseMillis();
    }

    public void connected(String workerId,
                          String deliveryBucketId,
                          String sessionToken,
                          String reason,
                          String traceId) {
        endpointLeasePublisher.claim(workerId, deliveryBucketId, sessionToken, reason);
    }

    public boolean heartbeat(String workerId,
                             String deliveryBucketId,
                             String sessionToken,
                             String reason,
                             String traceId) {
        return endpointLeasePublisher.refresh(workerId, deliveryBucketId, sessionToken, reason);
    }

    public boolean disconnected(String workerId,
                                String deliveryBucketId,
                                String sessionToken,
                                String reason,
                                String traceId) {
        boolean releasedCurrent = endpointLeasePublisher.release(workerId, deliveryBucketId, sessionToken, reason);
        if (releasedCurrent) {
            disconnectSink.currentSessionDisconnected(
                    deliveryBucketId,
                    workerId,
                    firstNonBlank(reason, "transport session disconnected"),
                    System.currentTimeMillis()
            );
        }
        return releasedCurrent;
    }

    public void claimEndpoint(String workerId,
                              String deliveryBucketId,
                              String sessionToken,
                              String reason) {
        endpointLeasePublisher.claim(workerId, deliveryBucketId, sessionToken, reason);
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
