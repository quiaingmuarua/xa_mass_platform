package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;

import java.util.Locale;

/**
 * Narrow adapter-facing capability for publishing worker session evidence.
 *
 * <p>Concrete adapters observe protocol sessions. They may publish those
 * observations through this capability, but they do not own the endpoint lease
 * store or worker-runtime presence ingress.</p>
 */
public final class AdapterSessionEvidencePublisher {

    private final TransportEndpointLeasePublisher endpointLeasePublisher;
    private final WorkerPresenceSessionPublisher workerPresencePublisher;

    public AdapterSessionEvidencePublisher(String adapterId,
                                           String adapterMailboxKey,
                                           TransportEndpointLeaseStore endpointLeaseStore,
                                           WorkerPresenceIngress workerPresenceIngress) {
        String normalizedAdapterId = requireText(adapterId, "adapterId").toLowerCase(Locale.ROOT);
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(normalizedAdapterId);
        this.endpointLeasePublisher.setEndpointLeaseStore(endpointLeaseStore);
        this.workerPresencePublisher = new WorkerPresenceSessionPublisher(
                normalizedAdapterId,
                requireText(adapterMailboxKey, "adapterMailboxKey")
        );
        this.workerPresencePublisher.setWorkerPresenceIngress(workerPresenceIngress);
    }

    public static AdapterSessionEvidencePublisher noop(String adapterId, String adapterMailboxKey) {
        return new AdapterSessionEvidencePublisher(
                adapterId,
                adapterMailboxKey,
                new InMemoryTransportEndpointLeaseStore(),
                NoopWorkerPresenceIngress.INSTANCE
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
        workerPresencePublisher.sessionConnected(workerId, sessionToken, reason, traceId);
        endpointLeasePublisher.claim(workerId, deliveryBucketId, sessionToken, reason);
    }

    public boolean heartbeat(String workerId,
                             String deliveryBucketId,
                             String sessionToken,
                             String reason,
                             String traceId) {
        workerPresencePublisher.sessionHeartbeat(workerId, sessionToken, reason, traceId);
        return endpointLeasePublisher.refresh(workerId, deliveryBucketId, sessionToken, reason);
    }

    public boolean disconnected(String workerId,
                                String deliveryBucketId,
                                String sessionToken,
                                String reason,
                                String traceId) {
        workerPresencePublisher.sessionDisconnected(workerId, sessionToken, reason, traceId);
        return endpointLeasePublisher.release(workerId, deliveryBucketId, sessionToken, reason);
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
}
